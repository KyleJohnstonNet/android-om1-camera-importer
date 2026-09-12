package dev.om1.importer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Called only by the signature-matched Camera Link after it has joined camera Wi-Fi. */
class CameraReadyReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val db=QueueStore.get(context)
        val session=db.savedSession() ?: return
        if(db.setting("cameraPaused")=="true" || intent.getStringExtra("sessionId")!=session.id) return
        runCatching { ImportWorker.schedule(context,session.id) }
            .onFailure {
                ImportService.status.value="Automatic import could not start. Open the importer and tap Sync this session now."
                android.util.Log.w("Om1AutoSync","Import job scheduling failed",it)
            }
    }
}
