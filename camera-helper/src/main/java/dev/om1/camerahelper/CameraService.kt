package dev.om1.camerahelper

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import dev.om1.importer.core.CameraBridge
import kotlinx.coroutines.*

class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reading = false
    private var connecting: Job? = null
    private val wifi by lazy { CameraSession.wifi(this) }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val uid = msg.sendingUid
            val permitted = runCatching {
                uid == packageManager.getApplicationInfo(CameraBridge.MAIN, 0).uid &&
                    packageManager.checkSignatures(uid, applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
            }.getOrDefault(false)
            if (!permitted || msg.what !in listOf(CameraBridge.READ_CAPABILITIES,CameraBridge.LIST_DIRECTORY,CameraBridge.DOWNLOAD_JPEG,CameraBridge.RELEASE)) return
            val operation=msg.what
            val path=msg.data.getString("path").orEmpty()
            val expected=msg.data.getLong("expected")
            val offset=msg.data.getInt("offset",0)
            val reply = msg.replyTo ?: return
            val requestId = msg.arg1
            fun respond(report: String? = null, error: String? = null, descriptor: ParcelFileDescriptor? = null) {
                val response = Message.obtain(null, operation, requestId, 0)
                response.data = Bundle().apply { putString("report",report); putString("error",error); putParcelable("file",descriptor) }
                runCatching { reply.send(response) }
            }
            if(operation==CameraBridge.RELEASE) {
                connecting?.cancel();wifi.disconnect();respond(report="{\"released\":true}")
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return
            }
            val network = wifi.network
            if (network == null) { respond(error="Open Camera Link and connect the camera first."); return }
            if (reading) { respond(error="A camera check is already running."); return }
            reading = true
            scope.launch {
                try {
                    if(operation==CameraBridge.DOWNLOAD_JPEG) {
                        val (file,report)=withContext(Dispatchers.IO) { CameraHttp.download(this@CameraService,network,path,expected) }
                        try {
                            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { respond(report=report,descriptor=it) }
                        } finally { file.delete() }
                    } else {
                        val report = withContext(Dispatchers.IO) {
                            if(operation==CameraBridge.LIST_DIRECTORY) CameraHttp.list(this@CameraService,network,path,offset)
                            else CameraHttp.read(this@CameraService,network)
                        }
                        check(report.length <= CameraBridge.MAX_REPORT_CHARS)
                        respond(report=report)
                    }
                } catch (e: Exception) { respond(error=if(e is IllegalStateException || e is IllegalArgumentException) e.message?.take(200) ?: "Camera operation rejected." else "Camera request failed (${e.javaClass.simpleName}). Check the connection.") }
                finally { reading = false }
            }
        }
    })
    override fun onCreate() {
        super.onCreate()
        // Reclaim only our unfinished diagnostic downloads after a previous crash.
        cacheDir.listFiles()?.filter { it.name.startsWith("camera-") && it.name.endsWith(".part") }?.forEach { it.delete() }
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { connecting?.cancel(); wifi.disconnect(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("camera", "Camera connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,1,Intent(this,CameraService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"camera").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OM-1 Camera Link").setContentText("Camera connection active or waiting. Tap to inspect.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Disconnect",stop).build()).build())
        if (intent?.action == "connect") {
            val ssid = intent.getStringExtra("ssid").orEmpty()
            val password = intent.getStringExtra("password").orEmpty()
            val wpa3 = intent.getBooleanExtra("wpa3",true)
            val profile = dev.om1.importer.core.CameraWifiCredentials(ssid,password,wpa3,
                intent.getStringExtra("bluetoothName"),intent.getStringExtra("bluetoothPassword"))
            val wake = intent.getBooleanExtra("wakeBluetooth",false)
            intent.removeExtra("password"); intent.removeExtra("bluetoothPassword")
            connecting?.cancel()
            connecting = scope.launch {
                wifi.disconnect()
                wifi.active.value=true
                try {
                    if(wake) {
                        CameraBluetooth(this@CameraService).wake(profile) { wifi.status.value=it }
                    }
                    wifi.connect(ssid,password,wpa3) {
                        try {
                            CameraProfileStore.save(this@CameraService,ssid,password,wpa3,profile.bluetoothName,profile.bluetoothPassword)
                            wifi.status.value="Camera connected. Profile saved for next time."
                        } catch(_:Exception) { wifi.status.value="Camera connected, but its details could not be saved." }
                    }
                } catch(e: TimeoutCancellationException) {
                    val stage=wifi.status.value
                    wifi.disconnect()
                    wifi.status.value="Bluetooth timed out: $stage Check camera Bluetooth is enabled, then retry."
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    wifi.disconnect()
                    wifi.status.value=if(e is IllegalStateException || e is IllegalArgumentException) e.message ?: "Camera connection failed." else "Camera connection failed. Check nearby-device permissions."
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); if(wifi.active.value) wifi.disconnect(); super.onDestroy() }
}
