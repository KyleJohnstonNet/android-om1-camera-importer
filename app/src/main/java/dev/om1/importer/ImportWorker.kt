package dev.om1.importer

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*

/** Android 12+ permits scheduling this from a broadcast without a background FGS start. */
class ImportWorker(context: Context, params: WorkerParameters):CoroutineWorker(context,params) {
    companion object {
        fun schedule(context:Context,sessionId:String) {
            WorkManager.getInstance(context).enqueueUniqueWork("camera-import",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ImportWorker>().setInputData(workDataOf("sessionId" to sessionId))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
        fun cancel(context:Context) { WorkManager.getInstance(context).cancelUniqueWork("camera-import") }
    }
    override suspend fun doWork():Result {
        val sessionId=inputData.getString("sessionId") ?: return Result.success()
        // The helper retries when it receives no successful completion acknowledgement.
        try { withTimeout(8*60*1000L) { ImportBatch.run(applicationContext,sessionId) } }
        catch(_:TimeoutCancellationException) { return Result.success() }
        return Result.success()
    }
}
