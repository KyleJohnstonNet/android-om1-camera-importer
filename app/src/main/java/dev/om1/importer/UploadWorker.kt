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
            WorkManager.getInstance(context).enqueueUniqueWork("google-photos-queue",ExistingWorkPolicy.KEEP,
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
            if(System.currentTimeMillis()-started>8*60*1000 || db.setting("uploadsEnabled","true")!="true") { retry=true;break }
            if(original.state=="UPLOADED") { cleanup(db,original);continue }
            if(original.retryAt>System.currentTimeMillis()) { retry=true;continue }
            var accessToken:String?=null
            try {
                val token=GoogleAuthorization.token(applicationContext,original.account);accessToken=token
                val api=PhotosApi(SystemHttp(applicationContext,db.setting("cellular","true")=="true"),token)
                if(original.state=="UNCERTAIN") {
                    val id=api.reconcile(original)
                    if(id!=null) { db.update(original.id,"state" to "UPLOADED","media_id" to id,"error" to null);cleanup(db,original.copy(state="UPLOADED",mediaId=id)) }
                    else db.update(original.id,"error" to "Google creation is unconfirmed. Original kept; use Recheck uploads later.")
                    continue
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
                try {
                    val media=api.create(row,SecretStore.open(checkNotNull(row.uploadToken)))
                    db.update(row.id,"state" to "UPLOADED","media_id" to media,"upload_url" to null,"upload_token" to null,"error" to null)
                    cleanup(db,row.copy(state="UPLOADED",mediaId=media))
                } catch(e:Exception) {
                    val definiteRejection=e is CloudFailure && e.status in setOf(400,401,403,404,429)
                    db.update(row.id,"state" to if(definiteRejection) "CREATE_PENDING" else "UNCERTAIN","error" to "Google creation was not confirmed. Original retained.")
                    throw e
                }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(e is CloudFailure && e.status==401 && accessToken!=null) runCatching { GoogleAuthorization.invalidate(applicationContext,accessToken) }
                val attempts=original.attempts+1;val delay=minOf(6*60*60*1000L,30000L*(1L shl minOf(attempts,9)))
                db.update(original.id,"error" to (if(e is IllegalStateException) e.message?.take(220) else "Network interrupted. Upload will resume when connected."),"attempts" to attempts,"retry_at" to System.currentTimeMillis()+delay)
                retry=true
                if(e is NetworkUnavailable) break
            }
        }
        if(retry) Result.retry() else Result.success()
    } }
    private fun cleanup(db:QueueStore,row:PhotoRow) {
        if(db.setting("cleanup","true")!="true" || row.state!="UPLOADED" || row.mediaId.isNullOrBlank() || row.account.isBlank() || row.local==null) return
        if(db.rows("local=? AND state NOT IN ('UPLOADED','BASELINE')",arrayOf(row.local)).isNotEmpty()) return
        if(!row.local.matches(Regex("[0-9a-f]{64}\\.jpg"))) return
        val file=File(applicationContext.filesDir,"originals/${row.local}")
        if(!file.exists() || file.delete()) db.update(row.id,"local" to null)
    }
}
