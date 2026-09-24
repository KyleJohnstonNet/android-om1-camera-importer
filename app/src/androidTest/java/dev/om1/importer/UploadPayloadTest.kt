package dev.om1.importer

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import dev.om1.importer.core.GeoFix
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class UploadPayloadTest {
    @Test fun gpsCopyDoesNotChangeOriginalAndRetriesKeepExactBytes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="gps-test-${UUID.randomUUID()}.db"
        val db=QueueStore(context,name)
        val original=File.createTempFile("gps-test-",".jpg",context.cacheDir)
        var output:File?=null
        try {
            val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
            val exif=ExifInterface(original.path)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:09:12 10:28:14")
            exif.saveAttributes()
            val hash=UploadPayload.sha(original)
            val time=java.time.Instant.parse("2026-09-12T10:28:14Z").toEpochMilli()
            db.startSession(time-60_000,time+60_000,zone="UTC")
            db.discover(UUID.randomUUID().toString(),listOf(SourcePhoto("/DCIM/100OMSYS/TEST.JPG",original.length(),"20260912:102814",1)))
            val id=db.rows().single().id
            db.update(id,"state" to "READY","sha" to hash,"local" to "$hash.jpg")
            val row=db.rows().single();db.set("geotag","true")
            val (file,payload)=UploadPayload.prepare(context,db,row,original) { requested ->
                assertEquals(time,requested);GeoFix(time,37.123,-122.456,5.0)
            }
            output=file
            assertNotEquals(original.path,file.path);assertEquals(hash,UploadPayload.sha(original))
            assertFalse(ExifInterface(original.path).getLatLong(FloatArray(2)))
            val coordinates=FloatArray(2)
            assertTrue(ExifInterface(file.path).getLatLong(coordinates))
            assertEquals(37.123,coordinates[0].toDouble(),0.00002)
            assertEquals(-122.456,coordinates[1].toDouble(),0.00002)
            val originalPixels=android.graphics.BitmapFactory.decodeFile(original.path)
            val uploadPixels=android.graphics.BitmapFactory.decodeFile(file.path)
            try { assertTrue(originalPixels.sameAs(uploadPixels)) }
            finally { originalPixels.recycle();uploadPixels.recycle() }
            db.set("geotag","false")
            val retried=UploadPayload.prepare(context,db,row,original) { error("A frozen retry must not rematch GPS") }
            assertEquals(payload,retried.second);assertEquals(payload.sha,UploadPayload.sha(retried.first))
            assertThrows(IllegalStateException::class.java) { UploadPayload.addGps(file,GeoFix(time,0.0,0.0,1.0)) }
            UploadPayload.cleanup(context,db,row.copy(state="UPLOADED",mediaId="confirmed"))
            assertFalse(file.exists());assertTrue(original.exists())
        } finally { output?.delete();original.delete();db.close();context.deleteDatabase(name) }
    }
}
