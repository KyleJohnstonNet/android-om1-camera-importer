package dev.om1.importer

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

/** Visible, user-initiated imports. Automatic imports use ImportWorker. */
class ImportService: Service() {
    companion object { val status=ImportBatch.status;val running=ImportBatch.running }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var task:Job?=null
    override fun onBind(intent:Intent):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {
            QueueStore.get(this).set("cameraPaused","true")
            ImportBatch.cancel();ImportWorker.cancel(this);task?.cancel()
            runCatching { startService(Intent().setClassName(dev.om1.importer.core.CameraBridge.HELPER,
                dev.om1.importer.core.CameraBridge.SERVICE).setAction("stop")) }
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY
        }
        if(intent?.action!="import") { stopSelf(startId);return START_NOT_STICKY }
        if(task?.isActive==true) return START_NOT_STICKY
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("import","Photo imports",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,ImportService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(20,Notification.Builder(this,"import").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Importing camera photos").setContentText("Originals are kept until Google Photos confirms upload.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Pause",stop).build()).build())
        task=scope.launch {
            try { ImportBatch.run(this@ImportService) }
            finally { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) { task?.cancel();stopSelf() }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}
