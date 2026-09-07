package dev.om1.importer

import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import dev.om1.importer.core.CameraFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object JpegImport {
    suspend fun one(context: Context, path: String, expected: Long, queueId: String? = null): String {
        require(CameraFiles.jpeg(path) && expected in 1..CameraFiles.MAX_JPEG_BYTES)
        val response=CameraHelperClient.download(context,path,expected)
        @Suppress("DEPRECATION")
        val descriptor=response.getParcelable<ParcelFileDescriptor>("file") ?: error("Helper did not return a JPEG.")
        // Own the descriptor before dispatching so cancellation cannot leak it.
        return descriptor.use {
            withContext(Dispatchers.IO) {
                val receipt=JSONObject(response.getString("report")!!)
                check(receipt.getString("path")==path && receipt.getLong("bytes")==expected)
                val directory=File(context.filesDir,"originals").apply { mkdirs() }
                check(directory.usableSpace > expected+16*1024*1024) { "Not enough space for the original JPEG." }
                val staging=File.createTempFile("import-", ".part",directory)
                try {
                    val digest=MessageDigest.getInstance("SHA-256")
                    var count=0L
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> staging.outputStream().use { output ->
                        val buffer=ByteArray(64*1024)
                        while(true) {
                            val n=input.read(buffer)
                            if(n<0) break
                            count+=n; check(count<=expected)
                            digest.update(buffer,0,n); output.write(buffer,0,n)
                        }
                        output.fd.sync()
                    } }
                    val sha=digest.digest().joinToString("") { "%02x".format(it) }
                    check(count==expected && sha==receipt.getString("sha256")) { "JPEG transfer integrity check failed." }
                    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(staging.path,options)
                    check(options.outWidth>0 && options.outHeight>0 && options.outMimeType=="image/jpeg") { "Downloaded file is not a readable JPEG." }
                    val dest=File(directory,"$sha.jpg")
                    if(dest.exists()) {
                        val existing=MessageDigest.getInstance("SHA-256")
                        dest.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { val n=input.read(buffer); if(n<0) break; existing.update(buffer,0,n) } }
                        check(existing.digest().joinToString("") { "%02x".format(it) }==sha) { "Existing original failed integrity check." }
                        staging.delete()
                    } else check(staging.renameTo(dest)) { "Unable to finalize JPEG." }
                    receipt.put("width",options.outWidth).put("height",options.outHeight)
                        .put("localFile",dest.name).put("uploaded",false)
                    File(context.filesDir,"last-import.json").writeText(receipt.toString(2))
                    if(queueId!=null) QueueStore.get(context).update(queueId,"local" to dest.name,"sha" to sha,"state" to "READY","error" to null)
                    val summary="Saved original JPEG: ${path.substringAfterLast('/')}\n$count bytes · ${options.outWidth} × ${options.outHeight}\nSHA-256 verified across helper IPC. No camera deletion or cloud upload."
                    DiagnosticLog.record("jpeg_import",summary)
                    summary
                } finally { staging.delete() }
            }
        }
    }
}
