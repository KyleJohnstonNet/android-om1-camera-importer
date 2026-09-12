package dev.om1.importer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** One owner of camera imports, shared by the visible service and background jobs. */
object ImportBatch {
    val status=MutableStateFlow("Ready for a shooting break.")
    val running=MutableStateFlow(false)
    private val lock=Mutex()
    private var active: Job?=null
    fun cancel() { active?.cancel() }

    suspend fun run(context: Context, expectedSession: String? = null): Boolean {
        if(!lock.tryLock()) return true
        val db=QueueStore.get(context)
        var session:SavedSession?=null
        var scannedAt=0L
        var completed=false
        var ownsConnection=false
        running.value=true;active=currentCoroutineContext()[Job]
        try {
            DiagnosticLog.initialize(context)
            val selected=db.savedSession() ?: return true
            session=selected
            if(expectedSession!=null && selected.id!=expectedSession) return true
            if(db.setting("cameraPaused")=="true") return true
            ownsConnection=true
            check(System.currentTimeMillis()>=selected.starts) { "Session has not started yet." }
            status.value="Reading all camera directories…"
            scannedAt=System.currentTimeMillis()
            val (camera,files)=enumerate(context)
            val discovery=withContext(Dispatchers.IO) { db.discover(camera,files,selected.id) }
            val pending=withContext(Dispatchers.IO) {
                db.rows("camera=? AND state='DISCOVERED'",arrayOf(camera)).filter { it.id in discovery.ids }.sortedBy { it.attempts }
            }
            var imported=0
            var failures=0
            for(p in pending) {
                currentCoroutineContext().ensureActive()
                check(db.savedSession()?.id==selected.id && db.setting("cameraPaused")!="true") { "Session changed or paused." }
                status.value="Importing ${imported+failures+1}/${pending.size}: ${p.path.substringAfterLast('/')}"
                try { JpegImport.one(context,p.path,p.size,p.id);imported++ }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    failures++
                    withContext(Dispatchers.IO) { db.update(p.id,"attempts" to (p.attempts+1),"error" to (e.message?.take(180) ?: "Import interrupted")) }
                    if(failures>=3) break
                }
            }
            completed=failures==0 && imported==pending.size && discovery.invalidTimestamps==0
            status.value="Imported $imported JPEGs. ${discovery.matched} of ${discovery.total} camera JPEGs match the saved window." +
                (if(discovery.invalidTimestamps>0) " ${discovery.invalidTimestamps} have unreadable timestamps." else "") +
                (if(failures>0) " Some photos could not be imported; they remain queued for retry." else "")
            return completed
        } catch(e:CancellationException) {
            status.value="Import paused or interrupted. Completed photos are safe; remaining photos will retry."
            throw e
        } catch(e:Exception) {
            status.value=e.message?.take(220) ?: "Import interrupted. Reconnect the camera and retry."
            return false
        } finally {
            withContext(NonCancellable) {
                runCatching { UploadWorker.schedule(context) }
                if(ownsConnection) runCatching { withTimeout(5000) {
                    CameraHelperClient.release(context,session?.id,if(completed) scannedAt else 0,
                        db.setting("cameraPaused")=="true")
                } }
            }
            active=null;running.value=false;lock.unlock()
        }
    }

    private suspend fun enumerate(context: Context):Pair<String,List<SourcePhoto>> {
        var identity:String?=null
        suspend fun directory(path:String):List<JSONObject> {
            val result=mutableListOf<JSONObject>();var offset=0;var expected=-1
            do {
                currentCoroutineContext().ensureActive()
                val page=JSONObject(CameraHelperClient.list(context,path,offset))
                val camera=page.getString("cameraId")
                check(identity==null || identity==camera) { "Camera changed during import." };identity=camera
                val total=page.getInt("total")
                check(total in 0..10000 && (expected<0 || expected==total)) { "Camera directory changed. Retry after shooting stops." };expected=total
                check(page.getInt("offset")==offset) { "Camera directory changed. Retry import." }
                val entries=page.getJSONArray("entries");check(entries.length()>0 || total==0)
                for(i in 0 until entries.length()) result+=entries.getJSONObject(i)
                offset+=entries.length();check(offset<=expected)
            } while(offset<expected)
            return result
        }
        val files=mutableListOf<SourcePhoto>()
        directory("/DCIM").filter { it.getBoolean("directory") }.forEach { d ->
            directory(d.getString("path")).filterNot { it.getBoolean("directory") }.forEach {
                files+=SourcePhoto(it.getString("path"),it.getLong("size"),it.optString("stamp"))
            }
        }
        check(files.distinctBy { it.path }.size==files.size) { "Camera listing changed. Retry import." }
        return checkNotNull(identity) to files
    }
}
