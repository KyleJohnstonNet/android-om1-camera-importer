package dev.om1.importer

import android.content.ContentValues
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.om1.importer.core.CameraFiles
import dev.om1.importer.core.JpegStructure
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit user-authorized recovery only. Ordinary test runs never enqueue cloud work. */
class VerifiedRecoveryUploadTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun report(message:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream","\n$message\n") })

    @Test fun uploadVerifiedCopies() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit approval required",args.getString("confirmedRecoveryUpload")=="yes")
        val directory=UUID.fromString(checkNotNull(args.getString("recoveryDirectory"))).toString()
        val ids=checkNotNull(args.getString("sourceIds")).split(',')
        val hashes=checkNotNull(args.getString("verifiedHashes")).split(',')
        require(ids.size in 1..2 && ids.size==hashes.size && ids.distinct().size==ids.size)
        require((ids+hashes).all { it.matches(Regex("[0-9a-f]{64}")) })
        val db=QueueStore.get(context)
        check(db.setting("uploadsEnabled","true")=="true") { "Uploads are paused; no settings changed." }
        PowerPolicy.check(context)
        val originals=File(context.filesDir,"originals").apply { check(isDirectory || mkdirs()) }
        // Verify every input before publishing any recovery rows to the upload worker.
        val plan=ids.zip(hashes).map { (id,hash) ->
            val source=db.rows("id=?",arrayOf(id)).single()
            check(source.state=="UPLOADED" && !source.mediaId.isNullOrBlank())
            check(source.account.isNotBlank() && source.account==db.setting("accountId"))
            check(source.sha!=hash && CameraFiles.jpeg(source.path))
            val name=source.path.substringAfterLast('/')
            val first=File(context.filesDir,"camera-diagnostics/$directory/1-$name")
            val second=File(context.filesDir,"camera-diagnostics/$directory/2-$name")
            for(file in listOf(first,second)) {
                check(file.length()==source.size && UploadPayload.sha(file)==hash) { "Verified diagnostic bytes changed." }
                file.inputStream().buffered().use(JpegStructure::verify)
            }
            check(originals.usableSpace>source.size+16*1024*1024)
            val destination=File(originals,"$hash.jpg")
            synchronized(OriginalFiles.lock) {
                if(destination.exists()) check(destination.length()==source.size && UploadPayload.sha(destination)==hash)
                else {
                    val staging=File.createTempFile("recovery-",".part",originals)
                    try {
                        first.inputStream().use { input -> staging.outputStream().use { out -> input.copyTo(out);out.fd.sync() } }
                        check(staging.length()==source.size && UploadPayload.sha(staging)==hash)
                        check(staging.renameTo(destination))
                    } finally { staging.delete() }
                }
            }
            source to hash
        }
        val recovery=publish(db,plan)
        recovery.forEach { id ->
            val row=db.rows("id=?",arrayOf(id)).single()
            report("Recovery queued: ${row.path.substringAfterLast('/')} state=${row.state}; original receipt preserved")
            runCatching { PhotoThumbnails.file(context,row.sha,row.local) }
        }
        UploadWorker.schedule(context,expedited=true)
        withTimeout(12*60*1000L) {
            while(true) {
                val rows=recovery.map { db.rows("id=?",arrayOf(it)).single() }
                for(row in rows) report("${row.path.substringAfterLast('/')} recovery: ${row.state}"+
                    (row.error?.let { " · $it" } ?: ""))
                if(rows.all { it.state=="UPLOADED" && !it.mediaId.isNullOrBlank() }) {
                    plan.forEach { (source,hash) ->
                        assertEquals(source,db.rows("id=?",arrayOf(source.id)).single())
                        assertEquals(hash,UploadPayload.sha(File(originals,"$hash.jpg")))
                    }
                    break
                }
                delay(10000)
            }
        }
        report("Both recovery bytes and Google Photos creation receipts verified. Existing cloud items untouched.")
    }

    /** Publish new rows and frozen original-byte payloads atomically; reruns reuse the same rows. */
    private fun publish(db:QueueStore,plan:List<Pair<PhotoRow,String>>):List<String> = synchronized(db) {
        val sql=db.writableDatabase
        check(sql.version==5)
        sql.beginTransaction()
        try {
            val ids=plan.map { (source,hash) ->
                val current=db.rows("id=?",arrayOf(source.id)).single()
                check(current==source && source.account==db.setting("accountId")) { "Source or account changed." }
                val id=QueueStore.digest("verified-recovery-v1|${source.id}|$hash")
                val existing=db.rows("id=?",arrayOf(id)).singleOrNull()
                if(existing==null) {
                    sql.insertOrThrow("photos",null,ContentValues().apply {
                        put("id",id);put("camera",source.camera);put("path",source.path);put("slot",source.slot)
                        put("size",source.size);put("stamp",source.stamp);put("discovered",System.currentTimeMillis())
                        put("account",source.account);put("album",source.album);put("state","READY")
                        put("local","$hash.jpg");put("sha",hash)
                    })
                    db.captureTime(source.id)?.let { (time,zone) ->
                        sql.insertOrThrow("capture_times",null,ContentValues().apply {
                            put("id",id);put("captured_at",time);put("zone",zone)
                        })
                    }
                    db.savePayload(id,PreparedPayload(null,source.size,hash,"User-authorized recovery from verified serial camera reads; original bytes frozen"))
                } else {
                    check(existing.path==source.path && existing.account==source.account && existing.album==source.album && existing.sha==hash)
                    check(db.payload(id)?.sha==hash)
                }
                id
            }
            sql.setTransactionSuccessful()
            ids
        } finally { sql.endTransaction() }
    }

    @Test fun recoveryPreservesOriginalReceiptAndDestinationAndIsIdempotent() {
        val name="recovery-test-${UUID.randomUUID()}.db"
        val db=QueueStore(context,name)
        try {
            db.set("accountId","owner")
            db.startSession(0,Long.MAX_VALUE,"recovery-album",zone="UTC")
            db.discover("camera",listOf(SourcePhoto("/DCIM/100OMSYS/TEST.JPG",1024,"20260912:102814",1)))
            val id=db.rows().single().id
            db.update(id,"state" to "UPLOADED","media_id" to "existing-cloud-item","sha" to "a".repeat(64))
            val original=db.rows().single()
            val recovery=publish(db,listOf(original to "b".repeat(64))).single()
            assertEquals(original,db.rows("id=?",arrayOf(id)).single())
            val row=db.rows("id=?",arrayOf(recovery)).single()
            assertEquals(original.account,row.account);assertEquals(original.album,row.album)
            assertEquals("READY",row.state);assertNull(row.mediaId);assertNull(row.uploadToken);assertNull(row.uploadUrl)
            assertEquals("b".repeat(64),db.payload(recovery)?.sha)
            assertEquals(recovery,publish(db,listOf(original to "b".repeat(64))).single())
            assertEquals(2,db.rows().size)
            db.set("accountId","another-owner")
            assertThrows(IllegalStateException::class.java) { publish(db,listOf(original to "c".repeat(64))) }
            assertEquals(2,db.rows().size)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
