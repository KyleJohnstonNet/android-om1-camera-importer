package dev.om1.importer

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import dev.om1.importer.core.CameraBridge
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** A signed, explicit Binder connection; the main process never opens a camera socket. */
object CameraHelperClient {
    private val ids = AtomicInteger()
    fun verify(context: Context) {
        check(context.packageManager.checkSignatures(context.packageName,CameraBridge.HELPER)==PackageManager.SIGNATURE_MATCH) {
            "Install the matching OM-1 Camera Link build first."
        }
    }
    suspend fun release(context: Context, sessionId: String? = null, scannedAt: Long = 0, paused: Boolean = false) {
        request(context,CameraBridge.RELEASE,extras=Bundle().apply {
            putString("sessionId",sessionId);putLong("scannedAt",scannedAt);putBoolean("paused",paused)
        }) { Unit }
    }
    suspend fun read(context: Context): String = request(context,CameraBridge.READ_CAPABILITIES) { it.getString("report")!! }
    suspend fun list(context: Context, path: String, offset: Int = 0): String = request(context,CameraBridge.LIST_DIRECTORY,path,offset=offset) { it.getString("report")!! }
    suspend fun <T> download(context: Context, path: String, size: Long, consume:suspend (Bundle)->T): T = request(context,CameraBridge.DOWNLOAD_JPEG,path,size,consume=consume)
    private suspend fun <T> request(context: Context, operation: Int, path: String="", expected: Long=0, offset: Int=0, extras: Bundle = Bundle(), consume:suspend (Bundle)->T): T = withContext(Dispatchers.Main.immediate) {
        verify(context)
        val result = CompletableDeferred<Bundle>()
        val requestId=ids.incrementAndGet()
        var receivedDescriptor: ParcelFileDescriptor? = null
        val receiver=Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != operation || msg.arg1 != requestId) return
                @Suppress("DEPRECATION")
                val descriptor=msg.data.getParcelable<ParcelFileDescriptor>("file")
                val error=msg.data.getString("error")
                val report=msg.data.getString("report")
                when {
                    error != null -> { descriptor?.close();result.completeExceptionally(IllegalStateException(error.take(300))) }
                    report == null || report.length > CameraBridge.MAX_REPORT_CHARS -> { descriptor?.close();result.completeExceptionally(IllegalStateException("Invalid helper response.")) }
                    else -> {
                        if(result.isCompleted) descriptor?.close() else {
                            // Take ownership before complete can resume the waiting coroutine.
                            receivedDescriptor=descriptor
                            if(!result.complete(msg.data)) { receivedDescriptor=null;descriptor?.close() }
                        }
                    }
                }
            }
        })
        val connection=object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    Messenger(binder).send(Message.obtain(null,operation,requestId,0).apply { replyTo=receiver; data=Bundle(extras).apply { putString("path",path); putLong("expected",expected); putInt("offset",offset) } })
                } catch (_: Exception) { result.completeExceptionally(IllegalStateException("Camera Link disconnected.")) }
            }
            override fun onServiceDisconnected(name: ComponentName) { result.completeExceptionally(IllegalStateException("Camera Link disconnected.")) }
            override fun onNullBinding(name: ComponentName) { result.completeExceptionally(IllegalStateException("Camera Link unavailable.")) }
            override fun onBindingDied(name: ComponentName) { result.completeExceptionally(IllegalStateException("Camera Link updated. Try again.")) }
        }
        var bound=false
        try {
            bound=context.bindService(Intent().setClassName(CameraBridge.HELPER,CameraBridge.SERVICE),connection,Context.BIND_AUTO_CREATE)
            check(bound) { "Unable to open Camera Link." }
            val response=withTimeout(if(operation==CameraBridge.DOWNLOAD_JPEG) 150000L else 30000L) { result.await() }
            // Consume resources inside their owning context; a cancelled dispatcher
            // handoff cannot discard a live descriptor outside this finally block.
            consume(response)
        } finally { receivedDescriptor?.close();result.cancel();if(bound) context.unbindService(connection) }
    }
}
