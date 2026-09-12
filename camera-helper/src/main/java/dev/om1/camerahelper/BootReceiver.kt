package dev.om1.camerahelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore an unfinished watcher after reboot, including outstanding final collection. */
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val monitor=context.getSharedPreferences("power-off-monitor",Context.MODE_PRIVATE)
        if(!monitor.getString("sessionId","").isNullOrBlank()) runCatching {
            context.startForegroundService(Intent(context,CameraService::class.java).setAction("restore"))
        }
    }
}
