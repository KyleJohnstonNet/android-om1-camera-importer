package dev.om1.importer

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    companion object {
        private val lock=Mutex()
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("google-photos-queue",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
    override suspend fun doWork():Result=lock.withLock { withContext(Dispatchers.IO) {
        val db=QueueStore.get(applicationContext)
        if(db.setting("uploadsEnabled","true")!="true" || db.setting("accountId").isBlank()) return@withContext Result.success()
        db.recover()
        val started=System.currentTimeMillis();var retry=false
        val rows=db.rows("state IN ('READY','UPLOADING','CREATE_PENDING','UNCERTAIN','UPLOADED') AND account=?",arrayOf(db.setting("accountId")))
        for(original in rows) {
            ensureActive()
            if(original.account!=db.setting("accountId")) break
            if(System.currentTimeMillis()-started>8*60*1000 || db.setting("uploadsEnabled","true")!="true") { retry=true;break }
            if(original.retryAt>System.currentTimeMillis()) { retry=true;continue }
            var accessToken:String?=null
            try {
                if(original.state=="UPLOADED") { cleanup(db,original);continue }
                val token=GoogleAuthorization.token(applicationContext,original.account);accessToken=token
                val api=PhotosApi(SystemHttp(applicationContext,db.setting("cellular","true")=="true"),token)
                if(original.state=="UNCERTAIN") {
                    val id=api.reconcile(original)
                    if(id!=null) {
                        db.update(original.id,"state" to "UPLOADED","media_id" to id,"error" to null)
                        cleanup(db,original.copy(state="UPLOADED",mediaId=id));continue
                    }
                    // Google documents same bytes => same mediaItem id, even with a new
                    // upload token. Verify the original below before retrying that same photo.
                    // https://developers.google.com/photos/library/guides/upload-media
                    db.update(original.id,"state" to "CREATE_PENDING")
                }
                val file=File(applicationContext.filesDir,"originals/${original.local}")
                check(original.local?.matches(Regex("[0-9a-f]{64}\\.jpg"))==true && file.length()==original.size) { "Local original missing or changed. Re-import this photo." }
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input->val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
                check(digest.digest().joinToString("") { "%02x".format(it) }==original.sha) { "Local original failed its integrity check." }
                var row=original
                if(row.uploadToken==null) {
                    var url=row.uploadUrl?.let(SecretStore::open)
                    var granularity=row.granularity
                    if(url==null) {
                        val response=api.call("POST","${PhotosApi.BASE}/uploads",mapOf("X-Goog-Upload-Command" to "start","X-Goog-Upload-Content-Type" to "image/jpeg",
                            "X-Goog-Upload-Protocol" to "resumable","X-Goog-Upload-Raw-Size" to row.size.toString()))
                        url=checkNotNull(response.header("x-goog-upload-url")) { "Google did not return a resumable upload URL." };SystemHttp.validateUrl(url)
                        granularity=response.header("x-goog-upload-chunk-granularity")?.toIntOrNull() ?: 262144
                        check(granularity in 1..8*1024*1024)
                        db.update(row.id,"upload_url" to SecretStore.seal(url),"granularity" to granularity,"state" to "UPLOADING")
                    }
                    val query=try { api.call("POST",checkNotNull(url),mapOf("X-Goog-Upload-Command" to "query")) }
                    catch(e:CloudFailure) {
                        if(e.status in setOf(404,410)) db.update(row.id,"upload_url" to null,"state" to "READY")
                        throw e
                    }
                    if(query.header("x-goog-upload-status")!="active") {
                        // No create has been attempted. Re-uploading bytes is safe if finalization's token was lost.
                        db.update(row.id,"upload_url" to null,"state" to "READY");retry=true;continue
                    }
                    var offset=query.header("x-goog-upload-size-received")?.toLongOrNull() ?: error("Google did not report an upload offset.")
                    check(offset in 0..row.size)
                    val chunkSize=((1024*1024+granularity-1)/granularity)*granularity
                    RandomAccessFile(file,"r").use { input ->
                        do {
                            ensureActive()
                            check(db.setting("uploadsEnabled","true")=="true") { "Uploads paused." }
                            if(System.currentTimeMillis()-started>8*60*1000) { retry=true;return@use }
                            val n=minOf(chunkSize.toLong(),row.size-offset).toInt();val buffer=ByteArray(n);input.seek(offset);input.readFully(buffer)
                            val final=offset+n==row.size
                            val response=api.call("POST",checkNotNull(url),mapOf("Content-Type" to "application/octet-stream","X-Goog-Upload-Command" to if(final) "upload, finalize" else "upload","X-Goog-Upload-Offset" to offset.toString()),buffer)
                            offset+=n
                            if(final) {
                                val uploadToken=response.text().trim();check(uploadToken.isNotEmpty() && uploadToken.length<65536)
                                db.update(row.id,"upload_token" to SecretStore.seal(uploadToken),"state" to "CREATE_PENDING")
                            }
                        } while(offset<row.size)
                    }
                    row=db.rows("id=?",arrayOf(row.id)).single()
                    if(row.uploadToken==null) { retry=true;continue }
                }
                ensureActive()
                db.update(row.id,"state" to "CREATING")
                val media=try {
                    api.create(row,SecretStore.open(checkNotNull(row.uploadToken)))
                } catch(e:Exception) {
                    when(creationRecovery(e)) {
                        CreationRecovery.UPLOAD_AGAIN -> db.update(row.id,"state" to "READY","upload_token" to null,"upload_url" to null)
                        CreationRecovery.RETRY_CREATE -> db.update(row.id,"state" to "CREATE_PENDING")
                        CreationRecovery.RECONCILE -> db.update(row.id,"state" to "UNCERTAIN")
                    }
                    throw e
                }
                db.update(row.id,"state" to "UPLOADED","media_id" to media,"upload_url" to null,"upload_token" to null,"error" to null)
                // Cleanup failure must not undo a positive Google receipt.
                cleanup(db,row.copy(state="UPLOADED",mediaId=media))
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(e is CloudFailure && e.status==401 && accessToken!=null) runCatching { GoogleAuthorization.invalidate(applicationContext,accessToken) }
                val attempts=original.attempts+1;val delay=minOf(6*60*60*1000L,30000L*(1L shl minOf(attempts,10)))
                db.update(original.id,"error" to (if(e is IllegalStateException) e.message?.take(220) else "Network interrupted. Upload will resume when connected."),"attempts" to attempts,"retry_at" to System.currentTimeMillis()+delay)
                retry=true
                if(e is NetworkUnavailable) break
            }
        }
        if(retry) {
            // A delayed retry must not block newly imported photos or the manual retry
            // button behind WorkManager's backoff on this entire unique-work chain.
            val next=db.rows("state IN ('READY','UPLOADING','CREATE_PENDING','UNCERTAIN') AND account=?",arrayOf(db.setting("accountId")))
                .minOfOrNull { it.retryAt } ?: System.currentTimeMillis()+30_000
            UploadRetryWorker.schedule(applicationContext,(next-System.currentTimeMillis()).coerceIn(30_000,6*60*60*1000))
        }
        Result.success()
    } }
    private fun cleanup(db:QueueStore,row:PhotoRow) {
        synchronized(OriginalFiles.lock) {
            if(db.setting("cleanup","true")!="true" || row.state!="UPLOADED" || row.mediaId.isNullOrBlank() || row.account.isBlank() || row.local==null) return
            if(db.rows("local=? AND state NOT IN ('UPLOADED','BASELINE')",arrayOf(row.local)).isNotEmpty()) return
            if(!row.local.matches(Regex("[0-9a-f]{64}\\.jpg"))) return
            val file=File(applicationContext.filesDir,"originals/${row.local}")
            if(!file.exists() || file.delete()) db.update(row.id,"local" to null)
        }
    }
}

/** Delayed wakeup, separate from the serialized upload chain so fresh work can run now. */
class UploadRetryWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    companion object {
        fun schedule(context:Context,delay:Long) {
            WorkManager.getInstance(context).enqueueUniqueWork("google-photos-retry",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UploadRetryWorker>().setInitialDelay(delay,TimeUnit.MILLISECONDS).build())
        }
    }
    override suspend fun doWork():Result { UploadWorker.schedule(applicationContext);return Result.success() }
}
