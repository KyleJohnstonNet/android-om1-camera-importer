package dev.om1.importer

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    companion object {
        val activity=MutableStateFlow<Map<String,String>>(emptyMap())
        val parallelLimit=MutableStateFlow(3)
        private fun report(id:String,detail:String) { activity.update { it+(id to detail) } }
        private val lock=Mutex()
        private val createLock=Mutex()
        fun schedule(context:Context,expedited:Boolean=false) {
            WorkManager.getInstance(context).enqueueUniqueWork("google-photos-queue",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .apply { if(expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
    override suspend fun doWork():Result=lock.withLock { withContext(Dispatchers.IO) {
        val db=QueueStore.get(applicationContext)
        if(db.setting("uploadsEnabled","true")!="true" || db.setting("accountId").isBlank()) return@withContext Result.success()
        db.recover()
        UploadRetryWorker.schedule(applicationContext,30_000)
        if(PowerPolicy.saving(applicationContext)) {
            UploadRetryWorker.schedule(applicationContext,30_000)
            return@withContext Result.success()
        }
        val started=System.currentTimeMillis()
        val adaptive=AdaptiveUploads { android.os.SystemClock.elapsedRealtime() }
        val connectivity=applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        coroutineScope {
            val attempted=mutableMapOf<String,Pair<Long,Long>>()
            val active=mutableMapOf<String,Job>()
            while(isActive && !PowerPolicy.saving(applicationContext) &&
                db.setting("uploadsEnabled","true")=="true" && System.currentTimeMillis()-started<8*60*1000) {
                active.entries.removeAll { it.value.isCompleted }
                adaptive.networkChanged(connectivity.activeNetwork?.networkHandle)
                val limit=adaptive.limit
                val available=UploadScheduling.select(db.rows(
                    "(state IN ('READY','UPLOADING','CREATE_PENDING','UNCERTAIN') OR (state='UPLOADED' AND local IS NOT NULL AND ?='true')) AND account=?",
                    arrayOf(db.setting("cleanup","true"),db.setting("accountId"))),active.keys,attempted,System.currentTimeMillis(),limit)
                for(row in available) {
                    attempted[row.id]=row.priority to row.retryAt
                    active[row.id]=launch { upload(db,row,started,adaptive) }
                }
                adaptive.tick(active.size)
                parallelLimit.value=adaptive.limit
                if(active.isEmpty()) break
                delay(500)
            }
        }
        val next=db.rows("state IN ('READY','UPLOADING','CREATE_PENDING','UNCERTAIN') AND account=?",
            arrayOf(db.setting("accountId"))).minOfOrNull { it.retryAt }
        if(next!=null) UploadRetryWorker.schedule(applicationContext,
            (next-System.currentTimeMillis()).coerceIn(10_000,60_000))
        else WorkManager.getInstance(applicationContext).cancelUniqueWork("google-photos-retry")
        Result.success()
    } }
    private suspend fun upload(db:QueueStore,original:PhotoRow,started:Long,adaptive:AdaptiveUploads) {
        var accessToken:String?=null
        try {
            currentCoroutineContext().ensureActive()
            PowerPolicy.check(applicationContext)
            if(original.account!=db.setting("accountId")) return
            if(original.state=="UPLOADED") { cleanup(db,original);return }
            report(original.id,"Authorizing Google Photos")
            val token=GoogleAuthorization.token(applicationContext,original.account);accessToken=token
            val api=PhotosApi(SystemHttp(applicationContext,db.setting("cellular","true")=="true",original.id),token)
            if(original.state=="UNCERTAIN") {
                report(original.id,"Checking an interrupted Google Photos creation")
                val id=api.reconcile(original)
                if(id!=null) {
                    db.update(original.id,"state" to "UPLOADED","media_id" to id,"error" to null,"priority" to 0L,"retry_at" to 0L)
                    cleanup(db,original.copy(state="UPLOADED",mediaId=id));return
                }
                // Google documents same bytes => same mediaItem id, even with a new
                // upload token. Verify the original below before retrying that same photo.
                // https://developers.google.com/photos/library/guides/upload-media
                db.update(original.id,"state" to "CREATE_PENDING")
            }
            var file=File(applicationContext.filesDir,"originals/${original.local}")
            check(original.local?.matches(Regex("[0-9a-f]{64}\\.jpg"))==true && file.length()==original.size) { "Local original missing or changed. Re-import this photo." }
            val digest=MessageDigest.getInstance("SHA-256")
            report(original.id,"Verifying the local original")
            file.inputStream().use { input->val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
            check(digest.digest().joinToString("") { "%02x".format(it) }==original.sha) { "Local original failed its integrity check." }
            report(original.id,"Preparing GPS metadata and freezing upload bytes")
            val (payloadFile,payload)=UploadPayload.prepare(applicationContext,db,original,file)
            file=payloadFile
            var row=original.copy(size=payload.size)
            if(row.uploadToken==null) {
                var url=row.uploadUrl?.let(SecretStore::open)
                report(original.id,"Opening or resuming the upload")
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
                    db.update(row.id,"upload_url" to null,"state" to "READY");return
                }
                var offset=query.header("x-goog-upload-size-received")?.toLongOrNull() ?: error("Google did not report an upload offset.")
                check(offset in 0..row.size)
                val chunkSize=((1024*1024+granularity-1)/granularity)*granularity
                RandomAccessFile(file,"r").use { input ->
                    do {
                        currentCoroutineContext().ensureActive()
                        PowerPolicy.check(applicationContext)
                        check(db.setting("uploadsEnabled","true")=="true") { "Uploads paused." }
                        if(System.currentTimeMillis()-started>8*60*1000) { return@use }
                        val n=minOf(chunkSize.toLong(),row.size-offset).toInt();val buffer=ByteArray(n);input.seek(offset);input.readFully(buffer)
                        val final=offset+n==row.size
                        report(original.id,"Uploading · ${offset*100/row.size}% · ${offset/1024}/${row.size/1024} KiB")
                        val networkId=applicationContext.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
                        val response=api.call("POST",checkNotNull(url),mapOf("Content-Type" to "application/octet-stream","X-Goog-Upload-Command" to if(final) "upload, finalize" else "upload","X-Goog-Upload-Offset" to offset.toString()),buffer)
                        adaptive.acknowledged(n,networkId)
                        offset+=n
                        report(original.id,"Uploading · ${offset*100/row.size}% · ${offset/1024}/${row.size/1024} KiB")
                        if(final) {
                            val uploadToken=response.text().trim();check(uploadToken.isNotEmpty() && uploadToken.length<65536)
                            db.update(row.id,"upload_token" to SecretStore.seal(uploadToken),"state" to "CREATE_PENDING")
                        }
                    } while(offset<row.size)
                }
                row=db.rows("id=?",arrayOf(row.id)).single()
                if(row.uploadToken==null) { return }
            }
            currentCoroutineContext().ensureActive()
            PowerPolicy.check(applicationContext)
            db.update(row.id,"state" to "CREATING")
            report(original.id,"Waiting for Google Photos creation")
            val media=try {
                createLock.withLock {
                    PowerPolicy.check(applicationContext)
                    report(original.id,"Creating photo in Google Photos")
                    api.create(row,SecretStore.open(checkNotNull(row.uploadToken)))
                }
            } catch(e:Exception) {
                when(creationRecovery(e)) {
                    CreationRecovery.UPLOAD_AGAIN -> db.update(row.id,"state" to "READY","upload_token" to null,"upload_url" to null)
                    CreationRecovery.RETRY_CREATE -> db.update(row.id,"state" to "CREATE_PENDING")
                    CreationRecovery.RECONCILE -> db.update(row.id,"state" to "UNCERTAIN")
                }
                throw e
            }
            db.update(row.id,"state" to "UPLOADED","media_id" to media,"upload_url" to null,"upload_token" to null,"error" to null,"priority" to 0L,"attempts" to 0,"retry_at" to 0L)
            // Cleanup failure must not undo a positive Google receipt.
            cleanup(db,row.copy(state="UPLOADED",mediaId=media))
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(e is java.io.IOException || e is CloudFailure && (e.status==429 || e.status in 500..599)) adaptive.congested()
            if(e is CloudFailure && e.status==401 && accessToken!=null) runCatching { GoogleAuthorization.invalidate(applicationContext,accessToken) }
            val attempts=original.attempts+1;val delay=if(e is java.io.IOException || e is NetworkUnavailable) 10_000L else minOf(60_000L,10_000L*(1L shl minOf(attempts-1,3)))
            db.update(original.id,"error" to (if(e is IllegalStateException) e.message?.take(220) else "Network interrupted. Upload will resume when connected."),"attempts" to attempts,"retry_at" to System.currentTimeMillis()+delay)
        } finally { activity.update { it-original.id } }
    }
    private fun cleanup(db:QueueStore,row:PhotoRow) {
        synchronized(OriginalFiles.lock) {
            UploadPayload.cleanup(applicationContext,db,row)
            if(db.setting("cleanup","true")!="true" || row.state!="UPLOADED" || row.mediaId.isNullOrBlank() || row.account.isBlank() || row.local==null) return
            if(db.rows("local=? AND state NOT IN ('UPLOADED','BASELINE')",arrayOf(row.local)).isNotEmpty()) return
            if(!row.local.matches(Regex("[0-9a-f]{64}\\.jpg"))) return
            val file=File(applicationContext.filesDir,"originals/${row.local}")
            runCatching { PhotoThumbnails.file(applicationContext,row.sha,row.local) }
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
