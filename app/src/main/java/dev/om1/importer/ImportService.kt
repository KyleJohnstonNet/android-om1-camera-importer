package dev.om1.importer

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Explicit break-time import; never wakes a camera merely because a timer fired. */
class ImportService: Service() {
    companion object { val status=MutableStateFlow("Ready for a shooting break.");val running=MutableStateFlow(false) }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var task:Job?=null
    override fun onBind(intent:Intent):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") { task?.cancel();return START_NOT_STICKY }
        if(running.value) return START_NOT_STICKY
        val action=intent?.action ?: return START_NOT_STICKY
        if(action !in setOf("baseline","include","import")) return START_NOT_STICKY
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("import","Photo imports",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,ImportService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(20,Notification.Builder(this,"import").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Importing camera photos")
            .setContentText("Originals are kept until Google Photos confirms upload.").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Pause",stop).build()).build())
        running.value=true
        task=scope.launch {
            val db=QueueStore.get(this@ImportService)
            try {
                status.value="Reading all camera directories…"
                val (camera,files)=enumerate()
                withContext(Dispatchers.IO) {
                    if(action=="baseline" || action=="include") db.begin(camera,files,action=="include") else db.discover(camera,files)
                }
                if(action=="baseline") status.value="Session started for 8 hours. ${files.size} existing JPEGs skipped. New photos will be imported during your breaks."
                else {
                    val pending=withContext(Dispatchers.IO) { db.rows("camera=? AND state='DISCOVERED'",arrayOf(camera)) }
                    pending.forEachIndexed { i,p ->
                        ensureActive();status.value="Importing ${i+1}/${pending.size}: ${p.path.substringAfterLast('/')}"
                        try { JpegImport.one(this@ImportService,p.path,p.size,p.id) }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) { withContext(Dispatchers.IO) { db.update(p.id,"error" to (e.message?.take(180) ?: "Import interrupted")) };throw e }
                    }
                    status.value="Imported ${pending.size} new JPEGs. Safe to resume shooting after disconnecting Camera Link."
                    UploadWorker.schedule(this@ImportService)
                }
            } catch(e:CancellationException) { status.value="Import paused. Completed photos are safe; remaining photos will retry." }
            catch(e:Exception) { status.value=e.message?.take(220) ?: "Import interrupted. Reconnect the camera and retry." }
            finally {
                // Release only the phone-side camera connection; the hardware return-to-shooting cycle was verified.
                withContext(NonCancellable) { runCatching { CameraHelperClient.release(this@ImportService) } }
                running.value=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun enumerate():Pair<String,List<SourcePhoto>> {
        var identity:String?=null
        suspend fun directory(path:String):List<JSONObject> {
            val result=mutableListOf<JSONObject>();var offset=0;var expected=-1
            do {
                currentCoroutineContext().ensureActive()
                val page=JSONObject(CameraHelperClient.list(this@ImportService,path,offset))
                val camera=page.getString("cameraId");check(identity==null || identity==camera) { "Camera changed during import." };identity=camera
                val total=page.getInt("total");check(expected<0 || expected==total) { "Camera directory changed. Retry after shooting stops." };expected=total
                check(page.getInt("offset")==offset) { "Camera directory changed. Retry import." }
                val entries=page.getJSONArray("entries");check(entries.length()>0 || total==0)
                for(i in 0 until entries.length()) result+=entries.getJSONObject(i)
                offset+=entries.length();check(result.size<=10000)
            } while(offset<expected)
            return result
        }
        val root=directory("/DCIM");val files=mutableListOf<SourcePhoto>()
        root.filter { it.getBoolean("directory") }.forEach { d ->
            directory(d.getString("path")).filterNot { it.getBoolean("directory") }.forEach {
                files+=SourcePhoto(it.getString("path"),it.getLong("size"),it.optString("stamp"))
            }
        }
        check(files.distinctBy { it.path }.size==files.size) { "Camera listing changed. Retry import." }
        return checkNotNull(identity) to files
    }
    override fun onTimeout(startId:Int,fgsType:Int) { task?.cancel();stopSelf() }
    override fun onDestroy() { scope.cancel();running.value=false;super.onDestroy() }
}
