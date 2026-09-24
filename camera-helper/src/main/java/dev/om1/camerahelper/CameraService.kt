package dev.om1.camerahelper

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
    private var selectedSlot=0
    private var slotNetwork:android.net.Network?=null
    private val downloads=mutableMapOf<Any,String>()
    private fun downloadStatus() {
        report(if(downloads.isEmpty()) "Waiting for Importer" else "Importing ${downloads.size} photo(s)",
            if(downloads.isEmpty()) "Camera Wi-Fi is ready for the next file or release request."
            else downloads.values.joinToString("\n"))
    }
    private var lastActivity=0L
    private var sessionId=""
    private var window:MonitorWindow?=null
    private var connectionSessionId=""
    private var generation=0L
    private var foreground=false
    private val cycle by lazy { dev.om1.importer.core.StandbyCycle(prefs.getInt("cycle",0).coerceIn(0,2)) }
    private fun report(title:String,detail:String,until:Long=0) {
        CameraSession.activity.value=dev.om1.importer.core.ActivityStatus(title,detail,until)
    }
    private fun notification():Notification {
        val status=CameraSession.activity.value
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CameraService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"camera").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camera Link · ${status.title}").setContentText(status.detail)
            .setStyle(Notification.BigTextStyle().bigText(status.detail))
            .setWhen(status.until).setShowWhen(status.until>0).setUsesChronometer(status.until>0).setChronometerCountDown(true)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"Stop watching",stop).build()).build()
    }
    private suspend fun waitBetween(seconds:Int,detail:String) {
        report("Between camera checks",detail,System.currentTimeMillis()+seconds*1000L)
        delay(seconds*1000L)
    }
    private val powerReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(PowerPolicy.saving(context)) {
                watching?.cancel();connecting?.cancel();wifi.disconnect()
                wifi.status.value="Battery saver is on. Camera imports are paused."
                report("Paused for battery saver","Bluetooth watching and camera imports resume when battery saver is off.")
            } else if(window!=null) startWatching()
        }
    }

    private val messenger=Messenger(object:Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg:Message) {
            val permitted=runCatching {
                msg.sendingUid==packageManager.getApplicationInfo(CameraBridge.MAIN,0).uid &&
                    packageManager.checkSignatures(msg.sendingUid,applicationInfo.uid)==PackageManager.SIGNATURE_MATCH
            }.getOrDefault(false)
            if(!permitted || msg.what !in setOf(CameraBridge.READ_CAPABILITIES,CameraBridge.LIST_DIRECTORY,CameraBridge.DOWNLOAD_JPEG,CameraBridge.RELEASE,CameraBridge.STATUS,CameraBridge.CARD_SLOT)) return
            val reply=msg.replyTo ?: return
            val operation=msg.what;val requestId=msg.arg1
            val path=msg.data.getString("path").orEmpty();val expected=msg.data.getLong("expected");val offset=msg.data.getInt("offset")
            val slot=msg.data.getInt("slot")
            fun respond(report:String?=null,error:String?=null,descriptor:ParcelFileDescriptor?=null) {
                runCatching { reply.send(Message.obtain(null,operation,requestId,0).apply {
                    data=Bundle().apply { putString("report",report);putString("error",error);putParcelable("file",descriptor) }
                }) }
            }
            if(operation==CameraBridge.STATUS) {
                val status=CameraSession.activity.value
                respond(report=org.json.JSONObject().put("title",status.title).put("detail",status.detail).put("until",status.until).toString())
                return
            }
            if(operation==CameraBridge.RELEASE) {
                val owner=msg.data.getString("sessionId")
                if(owner!=null && ((connectionSessionId.isNotEmpty() && owner!=connectionSessionId) ||
                    (connectionSessionId.isEmpty() && window!=null && owner!=sessionId))) {
                    respond(report="{\"released\":false}");return
                }
                if(owner==sessionId) {
                    window=window?.acknowledge(msg.data.getLong("scannedAt"),System.currentTimeMillis())
                    if(msg.data.getLong("scannedAt")>0) {
                        cycle.completed()
                        prefs.edit().putInt("cycle",cycle.phase).putInt("failures",0).putLong("retryAfter",0).commit()
                    }
                    prefs.edit().putLong("lastScan",window?.lastSuccessfulScan ?: 0).commit()
                    if(msg.data.getBoolean("paused")) clearMonitor()
                }
                connecting?.cancel();wifi.disconnect();connectionSessionId=""
                report(if(msg.data.getLong("scannedAt")>0) "Camera scan complete" else "Camera released",
                    if(msg.data.getLong("scannedAt")>0) "Eligible photos checked. Camera Wi-Fi disconnected."
                    else "Camera Wi-Fi disconnected. Any unfinished photos remain queued.")
                respond(report="{\"released\":true}")
                if(window?.isComplete()==true) clearMonitor()
                if(window==null) finishService() else if(watching?.isActive!=true) startWatching()
                return
            }
            val network=wifi.network
            if(PowerPolicy.saving(this@CameraService)) { respond(error="Battery saver is on. Camera imports are paused.");return }
            if(network==null) { respond(error="Camera is disconnected. Reconnect and retry.");return }
            if(slotNetwork!=network) { selectedSlot=0;slotNetwork=network }
            if(slot !in 0..2) { respond(error="Invalid camera card slot.");return }
            if(operation!=CameraBridge.CARD_SLOT && slot!=0 && slot!=selectedSlot) {
                respond(error="Camera card slot changed. Select the requested slot before reading.");return
            }
            val download=operation==CameraBridge.DOWNLOAD_JPEG
            val downloadKey=Any()
            if(reading || (if(download) downloads.size>=10 else downloads.isNotEmpty())) {
                respond(error="Camera transfer slots are busy. Retry shortly.");return
            }
            if(download) {
                downloads[downloadKey]="Slot ${slot.takeIf { it>0 } ?: "current"} · ${path.substringAfterLast('/')} · ${expected/1024} KiB"
                downloadStatus()
            } else {
                reading=true
                if(operation==CameraBridge.CARD_SLOT) report("Selecting camera card","Reading or selecting card slot ${slot.takeIf { it>0 } ?: "current"}.")
                else report("Reading camera files","Slot ${slot.takeIf { it>0 } ?: "current"} · Listing ${path.ifEmpty { "camera capabilities" }} · page offset $offset.")
            }
            lastActivity=SystemClock.elapsedRealtime()
            scope.launch {
                var failed=false
                try {
                    if(operation==CameraBridge.CARD_SLOT) {
                        // A failed switch leaves the selected card unknown, never the old cached slot.
                        selectedSlot=0
                        selectedSlot=withContext(Dispatchers.IO) {
                            if(slot==0) CameraHttp.currentSlot(this@CameraService,network)
                            else CameraHttp.selectSlot(this@CameraService,network,slot)
                        }
                        respond(report=org.json.JSONObject().put("slot",selectedSlot).toString())
                    } else if(operation==CameraBridge.DOWNLOAD_JPEG) {
                        val (file,report)=withContext(Dispatchers.IO) {
                            CameraHttp.download(this@CameraService,network,path,expected) { bytes ->
                                scope.launch {
                                    if(downloadKey in downloads && wifi.network!=null) {
                                        downloads[downloadKey]="Slot ${slot.takeIf { it>0 } ?: "current"} · ${path.substringAfterLast('/')} · ${bytes*100/expected}%"
                                        downloadStatus()
                                        runCatching { reply.send(Message.obtain(null,operation,requestId,0).apply {
                                            data=Bundle().apply { putLong("progressBytes",bytes) }
                                        }) }
                                    }
                                }
                            }
                        }
                        try { ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {
                            respond(report=org.json.JSONObject(report).put("slot",slot).toString(),descriptor=it)
                        } }
                        finally { file.delete() }
                    } else {
                        val report=withContext(Dispatchers.IO) {
                            if(operation==CameraBridge.LIST_DIRECTORY) {
                                if(slot>0) check(CameraHttp.currentSlot(this@CameraService,network)==slot) { "Camera card slot changed during listing." }
                                org.json.JSONObject(CameraHttp.list(this@CameraService,network,path,offset)).put("slot",slot).toString()
                            }
                            else CameraHttp.read(this@CameraService,network)
                        }
                        check(report.length<=CameraBridge.MAX_REPORT_CHARS);respond(report=report)
                    }
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    failed=true
                    val detail=if(e is IllegalStateException || e is IllegalArgumentException) e.message?.take(200) ?: "Camera request failed." else "Camera request failed. Check the connection."
                    report("Camera transfer interrupted",detail);respond(error=detail)
                }
                finally {
                    if(download) { downloads.remove(downloadKey);if(!failed && wifi.network!=null) downloadStatus() } else reading=false
                    lastActivity=SystemClock.elapsedRealtime()
                }
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        registerReceiver(powerReceiver,IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        cacheDir.listFiles()?.filter { it.name.startsWith("camera-") && it.name.endsWith(".part") }?.forEach { it.delete() }
        restoreMonitor()
        report("Monitoring not running","Save or restart a session in Importer to watch for camera standby.")
        scope.launch { CameraSession.activity.collect {
            if(foreground) getSystemService(NotificationManager::class.java).notify(1,notification())
        } }
    }
    override fun onBind(intent:Intent):IBinder=messenger.binder

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") { clearMonitor();connecting?.cancel();wifi.disconnect();report("Stopped","Camera watching was stopped. Start a session to resume.");finishService();return START_NOT_STICKY }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("camera","Camera connection",NotificationManager.IMPORTANCE_LOW))
        startForeground(1,notification());foreground=true
        when(intent?.action) {
            "monitor" -> {
                val id=intent.getStringExtra("sessionId").orEmpty()
                val candidate=runCatching { MonitorWindow(intent.getLongExtra("starts",0),intent.getLongExtra("ends",0)) }.getOrNull()
                if(id.isBlank() || candidate==null) { if(window==null) finishService();return START_NOT_STICKY }
                if(id!=sessionId) {
                    generation++
                    watching?.cancel();connecting?.cancel();wifi.disconnect();connectionSessionId=""
                    sessionId=id;window=candidate
                    cycle.phase=dev.om1.importer.core.StandbyCycle.ARMED
                    prefs.edit().putInt("cycle",cycle.phase).putInt("failures",0).putLong("retryAfter",0).commit()
                    prefs.edit().putString("sessionId",id).putLong("starts",candidate.starts).putLong("ends",candidate.ends).putLong("lastScan",0).commit()
                }
                startWatching()
            }
            "connect" -> {
                if(PowerPolicy.saving(this)) {
                    wifi.status.value="Battery saver is on. Camera imports are paused."
                    report("Paused for battery saver","Turn off battery saver before connecting to the camera.")
                    if(window==null) finishService()
                    return START_NOT_STICKY
                }
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
                        if(wake) CameraBluetooth(this@CameraService).wake(profile) { wifi.status.value=it;report("Bluetooth wake",it) }
                        connectWifi(profile)
                        wifi.status.value="Camera connected. Ready to import."
                        report("Camera connected","Ready for Importer to request photos over Wi-Fi.")
                        // The visible helper returns explicitly to the importer.
                    } catch(e:TimeoutCancellationException) {
                        if(ownerGeneration!=generation) return@launch
                        wifi.disconnect();wifi.status.value="Camera connection timed out. Retry when ready."
                        report("Connection timed out","Camera did not connect. Check standby settings and keep it nearby.")
                        if(window!=null) startWatching() else finishService()
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        if(ownerGeneration!=generation) return@launch
                        wifi.disconnect();wifi.status.value=e.message?.take(180) ?: "Camera connection failed. Retry when ready."
                        report("Connection failed",wifi.status.value)
                        if(window!=null) startWatching() else finishService()
                    }
                }
            }
            else -> if(window!=null) startWatching() else finishService()
        }
        return if(window!=null) START_STICKY else START_NOT_STICKY
    }

    private suspend fun connectWifi(profile:CameraWifiCredentials) {
        PowerPolicy.check(this)
        report("Connecting to camera Wi-Fi","Waiting for Android to join the camera. Approve its Wi-Fi prompt if shown.",System.currentTimeMillis()+65_000)
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
        CameraSession.monitoring.value=window!=null
        val ownerGeneration=generation
        watching=scope.launch {
            try {
                while(isActive && window!=null) {
                    if(PowerPolicy.saving(this@CameraService)) {
                        wifi.status.value="Battery saver is on. Camera imports are paused."
                        report("Paused for battery saver","Bluetooth watching and camera imports resume when battery saver is off.")
                        delay(1000);continue
                    }
                    val current=window ?: break
                    if(current.isComplete()) { report("Session collection complete","The final camera scan finished. No more camera checks are scheduled.");clearMonitor();finishService();break }
                    if(!current.mayCollect(System.currentTimeMillis())) {
                        report("Waiting for session start","Bluetooth watching begins when the saved session starts.",current.starts)
                        delay(1000);continue
                    }
                    if(reading || downloads.isNotEmpty()) { delay(1000);continue }
                    val retryAfter=prefs.getLong("retryAfter",0)
                    if(System.currentTimeMillis()<retryAfter) {
                        report("Waiting to retry camera","An incomplete check will retry automatically. Sync now bypasses this pause.",retryAfter)
                        delay(1000);continue
                    }
                    try {
                        val profile=CameraProfileStore.load(this@CameraService) ?: error("Scan the camera QR code to enable automatic sync.")
                        val name=profile.bluetoothName ?: error("Save Bluetooth details from the camera QR code.")
                        check(profile.bluetoothPassword!=null)
                        val scanEnd=System.currentTimeMillis()+25_000
                        report("Scanning for camera standby",
                            if(System.currentTimeMillis()>=current.ends) "Listening over Bluetooth for the final session collection."
                            else "Listening over Bluetooth. Waiting for the camera to enter power-off standby.",scanEnd)
                        val outcome=PowerOffWatcher(this@CameraService).waitForPowerOff(name,eligible={ powered ->
                            val before=cycle.phase
                            val eligible=cycle.observe(powered)
                            if(before!=cycle.phase) prefs.edit().putInt("cycle",cycle.phase).commit()
                            eligible || (!powered && System.currentTimeMillis()>=current.ends)
                        }) { report("Scanning for camera standby",it,scanEnd) }
                        if(!outcome.standby) { waitBetween(5,outcome.detail+" Next Bluetooth scan shortly.");continue }
                        PowerPolicy.check(this@CameraService)
                        val owner=sessionId
                        // Persist before attempting a wake: process death must not create a hot retry loop.
                        val failures=(prefs.getInt("failures",0)+1).coerceAtMost(6)
                        prefs.edit().putInt("failures",failures).putLong("retryAfter",
                            System.currentTimeMillis()+minOf(900_000L,30_000L*(1L shl (failures-1)))).commit()
                        connectionSessionId=owner
                        wifi.status.value="Standby signal detected; connecting for import…"
                        report("Standby detected","Starting the Bluetooth wake sequence.")
                        CameraBluetooth(this@CameraService).wake(profile) { wifi.status.value=it;report("Bluetooth wake",it) }
                        connectWifi(profile)
                        connectionSessionId=owner;lastActivity=SystemClock.elapsedRealtime()
                        wifi.status.value="Camera connected; waiting for import…"
                        report("Waiting for Importer","Camera Wi-Fi is ready. Android is scheduling the import.",System.currentTimeMillis()+180_000)
                        sendBroadcast(Intent("dev.om1.importer.ACTION_CAMERA_READY")
                            .setClassName(CameraBridge.MAIN,"dev.om1.importer.CameraReadyReceiver").putExtra("sessionId",owner))
                        // A lost callback, delayed worker, or dead importer cannot hold camera Wi-Fi forever.
                        while(isActive && wifi.network!=null && owner==sessionId && SystemClock.elapsedRealtime()-lastActivity<180_000) delay(1000)
                        if(wifi.network!=null) report("Importer wait timed out","Releasing camera Wi-Fi; the next camera check will retry.")
                    } catch(e:TimeoutCancellationException) {
                        wifi.status.value="Camera connection timed out; watching will retry…"
                        report("Connection timed out",wifi.status.value)
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { wifi.status.value=e.message?.take(180) ?: "Camera unavailable; retrying…";report("Camera unavailable",wifi.status.value) }
                    finally { if(ownerGeneration==generation) { wifi.disconnect();connectionSessionId="" } }
                    if(window!=null) waitBetween(30,CameraSession.activity.value.detail+" Next Bluetooth scan after this pause.")
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
        CameraSession.monitoring.value=false
        prefs.edit().clear().commit()
    }
    private fun finishService() { foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    override fun onDestroy() {
        unregisterReceiver(powerReceiver);scope.cancel();wifi.disconnect();CameraSession.monitoring.value=false
        if(foreground) report("Camera Link stopped","Monitoring is not running. Open Importer to restart the session watcher.")
        super.onDestroy()
    }
}
