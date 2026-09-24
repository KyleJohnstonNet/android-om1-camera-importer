package dev.om1.importer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import java.net.HttpURLConnection

object PowerPolicy {
    fun saving(context:Context)=context.getSystemService(PowerManager::class.java).isPowerSaveMode
    fun check(context:Context) { check(!saving(context)) { "Battery saver is on. Transfers will resume when it is off." } }

    /** Abort an in-flight socket as soon as battery saver is enabled. */
    fun guard(context:Context,connection:HttpURLConnection):AutoCloseable {
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) {
                if(saving(context)) connection.disconnect()
            }
        }
        context.registerReceiver(receiver,IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        try { check(context) } catch(e:Exception) {
            context.unregisterReceiver(receiver)
            connection.disconnect()
            throw e
        }
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }
}
