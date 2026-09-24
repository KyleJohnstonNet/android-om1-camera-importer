package dev.om1.importer

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import dev.om1.importer.core.GeoFix
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Started only from the visible activity after an explicit opt-in. No background permission. */
class LocationRecordingService:Service(),LocationListener {
    companion object { val status=MutableStateFlow("GPS recording stopped. Open the app to resume an enabled recorder.") }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private var listening=false
    private val power=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) { listen() }
    }
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate()
        registerReceiver(power,IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val db=QueueStore.get(this)
        if(intent?.action=="stop") db.set("recordGps","false")
        if(db.setting("recordGps")!="true" || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            stopSelf();return START_NOT_STICKY
        }
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("gps","GPS recording",NotificationManager.IMPORTANCE_LOW))
        startForeground(3,notification("Starting phone GPS recorder"))
        listen()
        return START_NOT_STICKY
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,30,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,31,Intent(this,LocationRecordingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"gps").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("OM-1 · GPS recording").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"Stop recording",stop).build()).build()
    }
    private fun report(text:String) { status.value=text;getSystemService(NotificationManager::class.java).notify(3,notification(text)) }
    @SuppressLint("MissingPermission")
    private fun listen() {
        if(listening) { manager.removeUpdates(this);listening=false }
        if(QueueStore.get(this).setting("recordGps")!="true") { stopSelf();return }
        if(PowerPolicy.saving(this)) { report("GPS paused for battery saver");return }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            report("Precise location permission is required");stopSelf();return
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,30_000L,0f,this,Looper.getMainLooper())
            listening=true
            report(if(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for a GPS fix; history stays on this phone" else "Enable phone Location to record GPS")
        } catch(_:Exception) { report("GPS unavailable; reopen the app to retry");stopSelf() }
    }
    override fun onLocationChanged(location:Location) {
        if(!listening || PowerPolicy.saving(this) || QueueStore.get(this).setting("recordGps")!="true") return
        val age=SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos
        if(location.isMock || !location.hasAccuracy() || age !in 0..60_000_000_000L) return
        val fix=GeoFix(location.time,location.latitude,location.longitude,location.accuracy.toDouble())
        if(!fix.usable()) { report("Waiting for GPS accuracy within 100 m");return }
        scope.launch {
            try {
                val saved=LocationHistory.get(this@LocationRecordingService).add(fix)
                withContext(Dispatchers.Main) {
                    if(listening && !PowerPolicy.saving(this@LocationRecordingService)) report(if(saved)
                        "GPS fix recorded · ±${location.accuracy.toInt()} m · ${java.time.Instant.ofEpochMilli(location.time).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}"
                        else "Waiting for a fresh GPS timestamp")
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { withContext(Dispatchers.Main) { report("Could not save GPS history. Check available phone storage.") } }
        }
    }
    override fun onProviderDisabled(provider:String) { report("Enable phone Location to record GPS") }
    override fun onProviderEnabled(provider:String) { report("Waiting for a fresh GPS fix") }
    override fun onDestroy() {
        manager.removeUpdates(this);unregisterReceiver(power);scope.cancel();listening=false
        status.value="GPS recording stopped. Open the app to resume an enabled recorder."
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
