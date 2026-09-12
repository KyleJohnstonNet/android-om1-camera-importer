package dev.om1.camerahelper

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import dev.om1.importer.core.CameraBridge
import dev.om1.importer.core.CameraWifiCredentials
import dev.om1.importer.core.MonitorWindow
import kotlinx.coroutines.*

class CameraService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val wifi by lazy { CameraSession.wifi(this) }
    private val prefs by lazy { getSharedPreferences("power-off-monitor",Context.MODE_PRIVATE) }
    private var watching:Job?=null
    private var connecting:Job?=null
    private var reading=false
    private var lastActivity=0L
    private var sessionId=""
    private var window:MonitorWindow?=null
    private var connectionSessionId=""
    private var generation=0L

    private val messenger=Messenger(object:Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg:Message) {
            val permitted=runCatching {
                msg.sendingUid==packageManager.getApplicationInfo(CameraBridge.MAIN,0).uid &&
                    packageManager.checkSignatures(msg.sendingUid,applicationInfo.uid)==PackageManager.SIGNATURE_MATCH
            }.getOrDefault(false)
            if(!permitted || msg.what !in setOf(CameraBridge.READ_CAPABILITIES,CameraBridge.LIST_DIRECTORY,CameraBridge.DOWNLOAD_JPEG,CameraBridge.RELEASE)) return
            val reply=msg.replyTo ?: return
            val operation=msg.what;val requestId=msg.arg1
            val path=msg.data.getString("path").orEmpty();val expected=msg.data.getLong("expected");val offset=msg.data.getInt("offset")
            fun respond(report:String?=null,error:String?=null,descriptor:ParcelFileDescriptor?=null) {
                runCatching { reply.send(Message.obtain(null,operation,requestId,0).apply {
                    data=Bundle().apply { putString("report",report);putString("error",error);putParcelable("file",descriptor) }
                }) }
            }
            if(operation==CameraBridge.RELEASE) {
                val owner=msg.data.getString("sessionId")
                if(owner!=null && ((connectionSessionId.isNotEmpty() && owner!=connectionSessionId) ||
                    (connectionSessionId.isEmpty() && window!=null && owner!=sessionId))) {
                    respond(report="{\"released\":false}");return
                }
                if(owner==sessionId) {
                    window=window?.acknowledge(msg.data.getLong("scannedAt"),System.currentTimeMillis())
                    prefs.edit().putLong("lastScan",window?.lastSuccessfulScan ?: 0).commit()
                    if(msg.data.getBoolean("paused")) clearMonitor()
                }
                connecting?.cancel();wifi.disconnect();connectionSessionId=""
                respond(report="{\"released\":true}")
                if(window?.isComplete()==true) clearMonitor()
                if(window==null) finishService() else if(watching?.isActive!=true) startWatching()
                return
            }
            val network=wifi.network
            if(network==null) { respond(error="Camera is disconnected. Reconnect and retry.");return }
            if(reading) { respond(error="A camera operation is still finishing. Retry shortly.");return }
            reading=true;lastActivity=SystemClock.elapsedRealtime()
            scope.launch {
                try {
                    if(operation==CameraBridge.DOWNLOAD_JPEG) {
                        val (file,report)=withContext(Dispatchers.IO) { CameraHttp.download(this@CameraService,network,path,expected) }
                        try { ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { respond(report=report,descriptor=it) } }
                        finally { file.delete() }
                    } else {
                        val report=withContext(Dispatchers.IO) {
                            if(operation==CameraBridge.LIST_DIRECTORY) CameraHttp.list(this@CameraService,network,path,offset)
                            else CameraHttp.read(this@CameraService,network)
                        }
                        check(report.length<=CameraBridge.MAX_REPORT_CHARS);respond(report=report)
                    }
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { respond(error=if(e is IllegalStateException || e is IllegalArgumentException) e.message?.take(200) else "Camera request failed. Check the connection.") }
                finally { reading=false;lastActivity=SystemClock.elapsedRealtime() }
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        cacheDir.listFiles()?.filter { it.name.startsWith("camera-") && it.name.endsWith(".part") }?.forEach { it.delete() }
        restoreMonitor()
    }
    override fun onBind(intent:Intent):IBinder=messenger.binder

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") { clearMonitor();connecting?.cancel();wifi.disconnect();finishService();return START_NOT_STICKY }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("camera","Camera connection",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CameraService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"camera").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OM-1 Camera Link").setContentText("Watching for standby or importing photos. Tap to inspect.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop watching",stop).build()).build())
        when(intent?.action) {
            "monitor" -> {
                val id=intent.getStringExtra("sessionId").orEmpty()
                val candidate=runCatching { MonitorWindow(intent.getLongExtra("starts",0),intent.getLongExtra("ends",0)) }.getOrNull()
                if(id.isBlank() || candidate==null) { if(window==null) finishService();return START_NOT_STICKY }
                if(id!=sessionId) {
                    generation++
                    watching?.cancel();connecting?.cancel();wifi.disconnect();connectionSessionId=""
                    sessionId=id;window=candidate
                    prefs.edit().putString("sessionId",id).putLong("starts",candidate.starts).putLong("ends",candidate.ends).putLong("lastScan",0).commit()
                }
                startWatching()
            }
            "connect" -> {
                generation++
                watching?.cancel();connecting?.cancel();wifi.disconnect()
                val ownerGeneration=generation
                connectionSessionId=intent.getStringExtra("sessionId").orEmpty()
                val ssid=intent.getStringExtra("ssid").orEmpty();val password=intent.getStringExtra("password").orEmpty()
                val profile=CameraWifiCredentials(ssid,password,intent.getBooleanExtra("wpa3",true),
                    intent.getStringExtra("bluetoothName"),intent.getStringExtra("bluetoothPassword"))
                val wake=intent.getBooleanExtra("wakeBluetooth",false)
                intent.removeExtra("password");intent.removeExtra("bluetoothPassword")
                connecting=scope.launch {
                    wifi.active.value=true
                    try {
                        if(wake) CameraBluetooth(this@CameraService).wake(profile) { wifi.status.value=it }
                        connectWifi(profile)
                        wifi.status.value="Camera connected. Ready to import."
                        // The visible helper returns explicitly to the importer.
                    } catch(e:TimeoutCancellationException) {
                        if(ownerGeneration!=generation) return@launch
                        wifi.disconnect();wifi.status.value="Camera connection timed out. Retry when ready."
                        if(window!=null) startWatching() else finishService()
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        if(ownerGeneration!=generation) return@launch
                        wifi.disconnect();wifi.status.value=e.message?.take(180) ?: "Camera connection failed. Retry when ready."
                        if(window!=null) startWatching() else finishService()
                    }
                }
            }
            else -> if(window!=null) startWatching() else finishService()
        }
        return if(window!=null) START_STICKY else START_NOT_STICKY
    }

    private suspend fun connectWifi(profile:CameraWifiCredentials) {
        val ready=CompletableDeferred<Unit>()
        wifi.connect(profile.ssid,profile.password,profile.wpa3,onDisconnected={
            ready.completeExceptionally(IllegalStateException("Camera Wi-Fi was lost or declined."))
        }) {
            ready.complete(Unit)
        }
        withTimeout(65_000) { ready.await() }
        CameraProfileStore.save(this,profile.ssid,profile.password,profile.wpa3,profile.bluetoothName,profile.bluetoothPassword)
    }

    private fun startWatching() {
        if(watching?.isActive==true) return
        val ownerGeneration=generation
        watching=scope.launch {
            try {
                while(isActive && window!=null) {
                    val current=window ?: break
                    if(current.isComplete()) { clearMonitor();finishService();break }
                    if(!current.mayCollect(System.currentTimeMillis())) { delay(1000);continue }
                    if(reading) { delay(1000);continue }
                    try {
                        val profile=CameraProfileStore.load(this@CameraService) ?: error("Scan the camera QR code to enable automatic sync.")
                        val name=profile.bluetoothName ?: error("Save Bluetooth details from the camera QR code.")
                        check(profile.bluetoothPassword!=null)
                        wifi.status.value=if(System.currentTimeMillis()>=current.ends) "Waiting for standby to collect the final session photos…" else "Watching for camera standby…"
                        if(!PowerOffWatcher(this@CameraService).waitForPowerOff(name)) { delay(5000);continue }
                        val owner=sessionId
                        connectionSessionId=owner
                        wifi.status.value="Standby signal detected; connecting for import…"
                        CameraBluetooth(this@CameraService).wake(profile) { wifi.status.value=it }
                        connectWifi(profile)
                        connectionSessionId=owner;lastActivity=SystemClock.elapsedRealtime()
                        wifi.status.value="Camera connected; waiting for import…"
                        sendBroadcast(Intent("dev.om1.importer.ACTION_CAMERA_READY")
                            .setClassName(CameraBridge.MAIN,"dev.om1.importer.CameraReadyReceiver").putExtra("sessionId",owner))
                        // A lost callback, delayed worker, or dead importer cannot hold camera Wi-Fi forever.
                        while(isActive && wifi.network!=null && owner==sessionId && SystemClock.elapsedRealtime()-lastActivity<180_000) delay(1000)
                    } catch(e:TimeoutCancellationException) {
                        wifi.status.value="Camera connection timed out; watching will retry…"
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { wifi.status.value=e.message?.take(180) ?: "Camera unavailable; retrying…" }
                    finally { if(ownerGeneration==generation) { wifi.disconnect();connectionSessionId="" } }
                    if(window!=null) delay(30_000)
                }
            } finally { if(window==null && ownerGeneration==generation) finishService() }
        }
    }

    private fun restoreMonitor() {
        sessionId=prefs.getString("sessionId","").orEmpty()
        window=if(sessionId.isBlank()) null else runCatching {
            MonitorWindow(prefs.getLong("starts",0),prefs.getLong("ends",0),prefs.getLong("lastScan",0))
        }.getOrNull()?.takeUnless { it.isComplete() }
    }
    private fun clearMonitor() {
        generation++
        window=null;sessionId="";watching?.cancel();watching=null
        prefs.edit().clear().commit()
    }
    private fun finishService() { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    override fun onDestroy() { scope.cancel();wifi.disconnect();super.onDestroy() }
}
