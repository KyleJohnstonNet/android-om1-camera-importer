package dev.om1.importer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import kotlinx.coroutines.*

class ImporterApplication:Application() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        scope.launch { runCatching { PhotoThumbnails.prune(this@ImporterApplication) } }
        scope.launch { runCatching { LocationHistory.get(this@ImporterApplication).prune() } }
        fun resume() { scope.launch {
            if(!PowerPolicy.saving(this@ImporterApplication)) {
                QueueStore.get(this@ImporterApplication).retryNetwork()
                UploadWorker.schedule(this@ImporterApplication)
            }
        } }
        registerReceiver(object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) {
                if(PowerPolicy.saving(context)) ImportBatch.cancel() else resume()
            }
        },IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object:ConnectivityManager.NetworkCallback() {
            private var usable:Network?=null
            override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities) {
                if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if(usable!=network) { usable=network;resume() }
                } else if(usable==network) usable=null
            }
            override fun onLost(network:Network) { if(usable==network) usable=null }
        })
    }
}
