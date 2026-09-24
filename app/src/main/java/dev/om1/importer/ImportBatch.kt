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
    val cardStats=MutableStateFlow<List<dev.om1.importer.core.CameraCardStats>>(emptyList())
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
            PowerPolicy.check(context)
            DiagnosticLog.initialize(context)
            val selected=db.savedSession() ?: return true
            session=selected
            if(expectedSession!=null && selected.id!=expectedSession) return true
            if(db.setting("cameraPaused")=="true") return true
            ownsConnection=true
            check(System.currentTimeMillis()>=selected.starts) { "Session has not started yet." }
            status.value="Reading all camera directories…"
            cardStats.value=emptyList()
            scannedAt=System.currentTimeMillis()
            val originalSlot=CameraHelperClient.cardSlot(context)
            var imported=0;var failures=0;var matched=0;var total=0;var invalid=0
            val slotErrors=mutableListOf<String>()
            try {
                for(slot in 1..2) {
                    currentCoroutineContext().ensureActive()
                    PowerPolicy.check(context)
                    try {
                        status.value="Selecting camera card slot $slot…"
                        CameraHelperClient.cardSlot(context,slot)
                        val (camera,files)=enumerate(context,slot)
                        cardStats.value=cardStats.value+dev.om1.importer.core.CameraCardStats(slot,files.size,System.currentTimeMillis())
                        val discovery=withContext(Dispatchers.IO) {
                            db.discover(camera,files,selected.id).also { PhotoThumbnails.prune(context) }
                        }
                        matched+=discovery.matched;total+=discovery.total;invalid+=discovery.invalidTimestamps
                        val pending=withContext(Dispatchers.IO) {
                            db.rows("camera=? AND slot=? AND state='DISCOVERED'",arrayOf(camera,slot.toString()))
                                .filter { it.id in discovery.ids }.sortedBy { it.attempts }
                        }
                        val adaptive=AdaptiveUploads(maximum=1,initial=1) { android.os.SystemClock.elapsedRealtime() }
                        adaptive.networkChanged(slot.toLong())
                        val result=CameraImportPipeline.run(pending,progress={ done,errors,active,width ->
                            status.value="Slot $slot · imported $done/${pending.size} · $errors failed · ${active.size} active · safe serial limit $width/1." +
                                if(active.isEmpty()) "" else " " + active.joinToString { it.path.substringAfterLast('/') }
                        },fallback={ status.value="Slot $slot · transfer interrupted. Retrying failed photos one at a time…" },controller=adaptive) { p ->
                            PowerPolicy.check(context)
                            currentCoroutineContext().ensureActive()
                            check(db.savedSession()?.id==selected.id && db.setting("cameraPaused")!="true") { "Session changed or paused." }
                            val received=java.util.concurrent.atomic.AtomicLong()
                            fun progress(bytes:Long) {
                                val previous=received.getAndUpdate { maxOf(it,bytes) }
                                if(bytes>previous) adaptive.acknowledged((bytes-previous).toInt(),slot.toLong())
                            }
                            try {
                                JpegImport.one(context,p.path,p.size,p.id,p.slot,::progress)
                                progress(p.size)
                                true
                            } catch(e:CancellationException) { throw e }
                            catch(e:Exception) {
                                withContext(Dispatchers.IO) {
                                    val attempts=db.rows("id=?",arrayOf(p.id)).single().attempts
                                    db.update(p.id,"attempts" to (attempts+1),"error" to (e.message?.take(180) ?: "Import interrupted"))
                                }
                                false
                            }
                        }
                        imported+=result.imported;failures+=result.failed
                        check(CameraHelperClient.cardSlot(context)==slot) { "Camera card slot changed during import." }
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        PowerPolicy.check(context)
                        slotErrors+="Slot $slot: ${e.message?.take(140) ?: "could not be scanned"}"
                    }
                }
            } finally {
                // Restore the user's playback selection after every slot's requests have drained.
                withContext(NonCancellable) {
                    if(!PowerPolicy.saving(context)) runCatching {
                        withTimeout(12_000) { CameraHelperClient.cardSlot(context,originalSlot) }
                    }.onFailure { slotErrors+="Could not restore the previous card selection." }
                }
            }
            completed=failures==0 && invalid==0 && slotErrors.isEmpty()
            status.value="Imported $imported JPEGs. Checked both card slots; $matched of $total listed JPEGs match the saved window." +
                (if(invalid>0) " $invalid have unreadable timestamps." else "") +
                (if(failures>0) " Some photos remain queued for retry." else "") +
                (if(slotErrors.isNotEmpty()) " "+slotErrors.joinToString(" ")+" Unchecked slots will retry." else "")
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

    private suspend fun enumerate(context: Context,slot:Int):Pair<String,List<SourcePhoto>> {
        var identity:String?=null
        suspend fun directory(path:String):List<JSONObject> {
            val result=mutableListOf<JSONObject>();var offset=0;var expected=-1
            do {
                PowerPolicy.check(context)
                currentCoroutineContext().ensureActive()
                val page=JSONObject(CameraHelperClient.list(context,path,offset,slot))
                check(page.getInt("slot")==slot) { "Camera returned a listing from the wrong card slot." }
                status.value="Slot $slot · reading camera directory $path · ${offset+page.getJSONArray("entries").length()}/${page.getInt("total")} entries."
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
                files+=SourcePhoto(it.getString("path"),it.getLong("size"),it.optString("stamp"),slot)
            }
        }
        check(files.distinctBy { it.path }.size==files.size) { "Camera listing changed. Retry import." }
        return checkNotNull(identity) to files
    }
}
