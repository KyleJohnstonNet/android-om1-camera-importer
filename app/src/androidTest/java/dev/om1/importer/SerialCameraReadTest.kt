package dev.om1.importer

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.om1.importer.core.CameraFiles
import dev.om1.importer.core.JpegStructure
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicitly opt-in hardware diagnostic. Never writes the queue or uploads files. */
class SerialCameraReadTest {
    @Test fun repeatSerialReads() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires an explicitly connected camera and cameraPaths argument",args.containsKey("cameraPaths"))
        val paths=args.getString("cameraPaths")!!.split(',')
        require(paths.size in 1..2 && paths.all(CameraFiles::jpeg))
        val slot=args.getString("cameraSlot")?.toInt() ?: 1
        require(slot in 1..2)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(!ImportBatch.running.value) { "Wait for the normal import to finish first." }
        PowerPolicy.check(context)
        val output=File(context.filesDir,"camera-diagnostics/${UUID.randomUUID()}").apply { check(mkdirs()) }
        fun report(message:String) { instrumentation.sendStatus(0,Bundle().apply { putString("stream","\n$message\n") }) }
        val originalSlot=CameraHelperClient.cardSlot(context)
        try {
            CameraHelperClient.cardSlot(context,slot)
            for(path in paths) {
                var offset=0
                var size:Long?=null
                do {
                    val page=JSONObject(CameraHelperClient.list(context,path.substringBeforeLast('/'),offset,slot))
                    val entries=page.getJSONArray("entries")
                    for(i in 0 until entries.length()) {
                        val entry=entries.getJSONObject(i)
                        if(entry.getString("path")==path) size=entry.getLong("size")
                    }
                    val total=page.getInt("total")
                    check(total in 0..10000 && (entries.length()>0 || total==0))
                    offset+=entries.length()
                } while(size==null && offset<total)
                val expected=checkNotNull(size) { "Requested photo was not listed." }
                require(expected in 1..CameraFiles.MAX_JPEG_BYTES)
                check(output.usableSpace>expected*2+32*1024*1024)
                val hashes=mutableListOf<String>()
                for(pass in 1..2) {
                    val file=File(output,"$pass-${path.substringAfterLast('/')}")
                    CameraHelperClient.download(context,path,expected,slot) { response ->
                        @Suppress("DEPRECATION")
                        val descriptor=checkNotNull(response.getParcelable<ParcelFileDescriptor>("file"))
                        descriptor.use {
                            withContext(Dispatchers.IO) {
                                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                    file.outputStream().use { out ->
                                        val buffer=ByteArray(65536);var count=0L
                                        while(true) {
                                            ensureActive();PowerPolicy.check(context)
                                            val n=input.read(buffer);if(n<0) break
                                            count+=n;check(count<=expected);out.write(buffer,0,n)
                                        }
                                        out.fd.sync();assertEquals(expected,count)
                                    }
                                }
                                file.inputStream().buffered().use(JpegStructure::verify)
                                val receipt=JSONObject(response.getString("report")!!)
                                assertEquals(path,receipt.getString("path"));assertEquals(slot,receipt.getInt("slot"))
                                val hash=UploadPayload.sha(file)
                                assertEquals(receipt.getString("sha256"),hash)
                                hashes+=hash
                                report("Serial read $pass: $path bytes=${file.length()} sha256=$hash saved=${file.relativeTo(context.filesDir)}")
                            }
                        }
                    }
                }
                assertEquals("Repeated serial reads differ for $path; retained copies need inspection",hashes[0],hashes[1])
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(12000) { CameraHelperClient.cardSlot(context,originalSlot) } }
                runCatching { withTimeout(5000) { CameraHelperClient.release(context) } }
            }
        }
    }
}
