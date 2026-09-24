package dev.om1.importer

import android.content.Context
import android.media.ExifInterface
import dev.om1.importer.core.GeoFix
import java.io.File
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class PreparedPayload(val file:String?,val size:Long,val sha:String,val note:String)

/** Never changes camera originals. Persist a frozen payload before the first network upload. */
object UploadPayload {
    private val exifTime=DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536);while(true) {
            val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun prepare(context:Context,db:QueueStore,row:PhotoRow,original:File,
        lookup:(Long)->GeoFix? = { LocationHistory.get(context).nearest(it) }):Pair<File,PreparedPayload> {
        val directory=File(context.filesDir,"upload-copies").apply { mkdirs() }
        fun resolve(payload:PreparedPayload):Pair<File,PreparedPayload> {
            check(payload.file==null || payload.file=="${row.id}.jpg") { "Invalid upload payload name." }
            val file=payload.file?.let { File(directory,it) } ?: original
            check(file.length()==payload.size && sha(file)==payload.sha) { "Frozen upload copy is missing or changed. It cannot safely be regenerated during a retry." }
            return file to payload
        }
        db.payload(row.id)?.let { return resolve(it) }
        // An upload already in progress before this feature must keep the original bytes.
        var note=if(row.state!="READY" || row.uploadUrl!=null || row.uploadToken!=null) "Original bytes retained for an existing upload"
            else if(db.setting("geotag")!="true") "Geotagging off at upload start" else "No capture time or nearby recorded GPS fix"
        var output:File?=null
        if(db.setting("geotag")=="true" && row.state=="READY" && row.uploadUrl==null && row.uploadToken==null) {
            val exif=ExifInterface(original.path)
            if(exif.getLatLong(FloatArray(2))) note="Existing photo GPS preserved"
            else {
                val timing=db.captureTime(row.id)
                val capture=runCatching {
                    val wall=exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { LocalDateTime.parse(it,exifTime) }
                    val offset=exif.getAttribute("OffsetTimeOriginal")?.let(ZoneOffset::of)
                    if(wall!=null && offset!=null) wall.toInstant(offset).toEpochMilli()
                    else if(wall!=null && timing!=null) {
                        val offsets=ZoneId.of(timing.second).rules.getValidOffsets(wall)
                        if(offsets.size==1) wall.toInstant(offsets.single()).toEpochMilli() else null
                    } else timing?.first
                }.getOrNull()
                val fix=capture?.let(lookup)
                if(fix!=null) {
                    check(directory.usableSpace>original.length()*2+16*1024*1024) { "Not enough space for a GPS upload copy." }
                    val staging=File.createTempFile("gps-",".part",directory)
                    try {
                        original.inputStream().use { input -> staging.outputStream().use { out -> input.copyTo(out);out.fd.sync() } }
                        addGps(staging,fix)
                        java.io.FileOutputStream(staging,true).use { it.fd.sync() }
                        staging.inputStream().buffered().use { dev.om1.importer.core.JpegStructure.verify(it) }
                        val dest=File(directory,"${row.id}.jpg")
                        // A previous crash may leave an uncommitted copy. No upload can reference it yet.
                        check(staging.renameTo(dest)) { "Could not finalize GPS upload copy." }
                        output=dest;note="GPS added from capture-time history; camera clock not independently verified"
                    } finally { staging.delete() }
                }
            }
        }
        val file=output ?: original
        val payload=PreparedPayload(output?.name,file.length(),sha(file),note)
        db.savePayload(row.id,payload)
        return file to payload
    }
    internal fun addGps(file:File,fix:GeoFix) {
        require(fix.usable())
        val exif=ExifInterface(file.path)
        check(!exif.getLatLong(FloatArray(2))) { "Do not overwrite existing GPS." }
        fun rational(value:Double):String {
            val absolute=kotlin.math.abs(value);val degrees=absolute.toInt();val minutes=((absolute-degrees)*60).toInt()
            val seconds=kotlin.math.round(((absolute-degrees)*60-minutes)*60*1_000_000).toLong()
            return "$degrees/1,$minutes/1,$seconds/1000000"
        }
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,rational(fix.latitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,if(fix.latitude<0) "S" else "N")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,rational(fix.longitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,if(fix.longitude<0) "W" else "E")
        val utc=Instant.ofEpochMilli(fix.time).atOffset(ZoneOffset.UTC)
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP,utc.format(DateTimeFormatter.ofPattern("uuuu:MM:dd")))
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP,"${utc.hour}/1,${utc.minute}/1,${utc.second}/1")
        exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD,"GPS")
        exif.saveAttributes()
        val position=FloatArray(2)
        check(ExifInterface(file.path).getLatLong(position) && kotlin.math.abs(position[0]-fix.latitude)<0.00002 &&
            kotlin.math.abs(position[1]-fix.longitude)<0.00002) { "GPS metadata verification failed." }
    }
    fun cleanup(context:Context,db:QueueStore,row:PhotoRow) {
        if(row.state!="UPLOADED" || row.mediaId.isNullOrBlank()) return
        db.payload(row.id)?.file?.takeIf { it=="${row.id}.jpg" }?.let {
            val file=File(context.filesDir,"upload-copies/$it")
            check(!file.exists() || file.delete()) { "Could not remove confirmed upload copy." }
        }
    }
}
