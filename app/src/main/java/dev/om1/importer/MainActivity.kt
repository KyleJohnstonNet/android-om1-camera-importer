package dev.om1.importer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.*
import dev.om1.importer.core.CameraBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.om1.importer.core.SessionTime

class MainActivity:ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        acceptCameraReturn(intent)
        lifecycle.addObserver(LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) resumeCount++ })
        setContent { MaterialTheme { Screen() } }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptCameraReturn(intent)
        resumeCount++
    }
    private fun acceptCameraReturn(intent:Intent?) {
        if(intent?.getBooleanExtra("cameraReady",false)==true) {
            val db=QueueStore.get(this)
            if(db.setting("pendingImport").isNotEmpty() && intent.getStringExtra("sessionId")==db.savedSession()?.id &&
                db.setting("cameraPaused")!="true") db.set("pendingImportReady","true")
            intent.removeExtra("cameraReady")
        }
    }
    @Composable private fun Screen() {
        val db=remember { QueueStore.get(this) };val scope=rememberCoroutineScope()
        val revision by db.changes.collectAsStateWithLifecycle()
        val running by ImportService.running.collectAsStateWithLifecycle()
        val importStatus by ImportService.status.collectAsStateWithLifecycle()
        var message by rememberSaveable { mutableStateOf("") }
        var account by remember { mutableStateOf("") }
        var rows by remember { mutableStateOf(emptyList<PhotoRow>()) }
        var sessionText by remember { mutableStateOf("No active session") }
        var cellular by remember { mutableStateOf(true) };var cleanup by remember { mutableStateOf(true) };var uploads by remember { mutableStateOf(true) }
        val timeFormat=remember { SessionTime.format }
        var albumTitle by rememberSaveable { mutableStateOf("") }
        val savedSession=remember { db.session() }
        val sessionZone=remember { ZoneId.of(savedSession?.optString("zone")?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id) }
        var sessionStart by rememberSaveable { mutableStateOf(savedSession?.let {
            java.time.Instant.ofEpochMilli(it.getLong("starts")).atZone(sessionZone).format(timeFormat)
        } ?: LocalDateTime.now().format(timeFormat)) }
        var sessionEnd by rememberSaveable { mutableStateOf(savedSession?.let {
            java.time.Instant.ofEpochMilli(it.getLong("ends")).atZone(sessionZone).format(timeFormat)
        } ?: LocalDateTime.now().plusHours(8).format(timeFormat)) }
        var albums by remember { mutableStateOf(emptyList<Pair<String,String>>()) }
        var chosenAlbum by rememberSaveable { mutableStateOf(savedSession?.optString("album").orEmpty()) }
        var chosenAlbumTitle by rememberSaveable { mutableStateOf(savedSession?.optString("albumTitle") ?: "General library") }
        var albumLabel by remember { mutableStateOf("General library") }
        var cloudBusy by remember { mutableStateOf(false) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { DiagnosticLog.initialize(this@MainActivity) }
            UploadWorker.schedule(this@MainActivity)
            while(true) { delay(1000);now=System.currentTimeMillis() }
        }
        LaunchedEffect(revision,now/60000) {
            withContext(Dispatchers.IO) {
                val snapshot=db.rows();val email=db.setting("accountEmail")
                val s=db.session();val remaining=(s?.getLong("ends") ?: 0)-now
                val session=when {
                    s==null -> "No session selected"
                    now < s.getLong("starts") -> "Session scheduled · starts ${java.time.Instant.ofEpochMilli(s.getLong("starts")).atZone(ZoneId.systemDefault()).format(timeFormat)}"
                    now < s.getLong("ends") -> "Session active · ${remaining/60000} min left"
                    else -> "Photo window ended · eligible photos can still be imported"
                }
                val album=db.savedSession()?.albumTitle ?: "No saved session"
                withContext(Dispatchers.Main) { rows=snapshot;account=email;sessionText=session;albumLabel=album
                    cellular=db.setting("cellular","true")=="true";cleanup=db.setting("cleanup","true")=="true";uploads=db.setting("uploadsEnabled","true")=="true" }
            }
        }
        LaunchedEffect(resumeCount) {
            val pending=withContext(Dispatchers.IO) { db.setting("pendingImport") }
            if(pending.isNotEmpty() && db.setting("pendingImportReady")=="true") {
                try {
                    startForegroundService(Intent(this@MainActivity,ImportService::class.java).setAction(pending))
                    db.set("pendingImport","");db.set("pendingImportReady","false")
                }
                catch(_:Exception) { message="Unable to start import. Allow notifications and retry." }
            }
        }
        fun launchCamera(action:String) {
            db.set("cameraPaused","false")
            db.set("pendingImportReady","false");db.set("pendingImport",action)
            try {
                CameraHelperClient.verify(this@MainActivity)
                startActivity(Intent().setClassName(CameraBridge.HELPER,CameraBridge.ACTIVITY)
                    .putExtra("connectForImport",true).putExtra("sessionId",db.savedSession()?.id))
            } catch(_:Exception) { db.set("pendingImport","");message="Install the matching Camera Link app first." }
        }
        var permissionAction by rememberSaveable { mutableStateOf("import") }
        val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(granted && permissionAction=="monitor") {
                db.savedSession()?.let { session ->
                    runCatching {
                        startActivity(Intent().setClassName(CameraBridge.HELPER,CameraBridge.ACTIVITY).putExtra("monitorSession",true)
                            .putExtra("starts",session.starts).putExtra("ends",session.ends).putExtra("sessionId",session.id))
                    }.onFailure { message="Session saved. Open Camera Link to check permissions and start watching." }
                }
            } else if(granted) launchCamera(permissionAction) else message="Session is saved. Allow notifications to enable camera sync."
        }
        fun begin(action:String) {
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                permissionAction=action;notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else launchCamera(action)
        }
        fun startTimedSession() {
            val starts=runCatching { SessionTime.parse(sessionStart,sessionZone) }.getOrNull()
            val ends=runCatching { SessionTime.parse(sessionEnd,sessionZone) }.getOrNull()
            if(starts==null || ends==null || ends<=starts) { message="Use local date/time as YYYY-MM-DD HH:mm, with an end after the start.";return }
            val session=db.startSession(starts,ends,chosenAlbum.takeIf { it.isNotBlank() },chosenAlbumTitle,sessionZone.id)
            ImportWorker.cancel(this@MainActivity)
            runCatching { startService(Intent().setClassName(CameraBridge.HELPER,CameraBridge.SERVICE).setAction("stop")) }
            try {
                CameraHelperClient.verify(this@MainActivity)
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                    permissionAction="monitor";notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else startActivity(Intent().setClassName(CameraBridge.HELPER,CameraBridge.ACTIVITY).putExtra("monitorSession",true)
                    .putExtra("starts",starts).putExtra("ends",ends).putExtra("sessionId",session.id))
                message="Session saved. Camera Link will check permissions and watch for standby until the final photos are collected."
            } catch(_:Exception) { message="Session saved. Install/open the matching Camera Link app once to enable automatic power-off sync." }
        }
        fun authorized(result:AuthorizationResult) {
            cloudBusy=true
            scope.launch {
                try {
                    val (id,email)=GoogleAuthorization.identity(this@MainActivity,result,cellular)
                    val changedAccount=withContext(Dispatchers.IO) {
                        val changed=db.setting("accountId").isNotBlank() && db.setting("accountId")!=id
                        if(changed) {
                            db.set("albumId","");db.set("albumUntil","0");db.endSession()
                        }
                        db.set("accountId",id);db.set("accountEmail",email);db.assignUnassigned(id)
                        changed
                    }
                    if(changedAccount) {
                        chosenAlbum="";chosenAlbumTitle="General library";albums=emptyList()
                        ImportWorker.cancel(this@MainActivity)
                        runCatching { startService(Intent().setClassName(CameraBridge.HELPER,CameraBridge.SERVICE).setAction("stop")) }
                    }
                    message="Google Photos connected. Unassigned local photos are now queued for this account.";UploadWorker.schedule(this@MainActivity)
                } catch(e:Exception) { message=e.message?.take(220) ?: "Google account setup failed." }
                finally { cloudBusy=false }
            }
        }
        val authorizeResult=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)) }
            catch(_:Exception) { cloudBusy=false;message="Google Photos permission was not granted." }
        }
        fun signIn() {
            cloudBusy=true
            scope.launch {
                try {
                    // Check routing before asking Play services for Photos authorization.
                    withContext(Dispatchers.IO) { SystemHttp(this@MainActivity,cellular).request("GET","https://www.googleapis.com/oauth2/v3/userinfo",emptyMap(),byteArrayOf()) }
                    val result=Identity.getAuthorizationClient(this@MainActivity).authorize(GoogleAuthorization.request()).await()
                    if(result.hasResolution()) { cloudBusy=false;authorizeResult.launch(IntentSenderRequest.Builder(checkNotNull(result.pendingIntent).intentSender).build()) }
                    else authorized(result)
                } catch(e:Exception) { cloudBusy=false;message="Check your internet connection and Google Photos setup, then retry sign-in." + ((e as? com.google.android.gms.common.api.ApiException)?.let { " (Google code ${it.statusCode})" } ?: "") }
            }
        }
        fun albumAction(create:Boolean) {
            cloudBusy=true
            scope.launch {
                try {
                    val token=GoogleAuthorization.token(this@MainActivity,db.setting("accountId"))
                    val api=PhotosApi(SystemHttp(this@MainActivity,cellular),token)
                    if(create) {
                        val album=withContext(Dispatchers.IO) { api.createAlbum(albumTitle.trim()) }
                        albums=albums+album;chosenAlbum=album.first;chosenAlbumTitle=album.second;message="Album created. Save the session to use it."
                    } else albums=withContext(Dispatchers.IO) { api.albums() }
                } catch(e:Exception) { message=e.message?.take(220) ?: "Album request failed." }
                finally { cloudBusy=false }
            }
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("OM-1 Importer",style=MaterialTheme.typography.headlineMedium)
                Text("Shoot. Switch off. Import during a break.",style=MaterialTheme.typography.titleMedium)
                Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(sessionText);Text(importStatus)
                    Text("${rows.count { it.state=="UPLOADED" }} uploaded · ${rows.count { it.state in setOf("READY","UPLOADING","CREATE_PENDING") }} queued · ${rows.count { it.state=="DISCOVERED" }} on camera")
                    if(running) Button(onClick={startService(Intent(this@MainActivity,ImportService::class.java).setAction("stop"))}) { Text("Pause import") }
                    else {
                        Button(onClick={begin("import")},enabled=db.session()!=null) { Text("Sync this session now") }
                        OutlinedButton(enabled=db.session()!=null,onClick={
                            db.endSession();ImportWorker.cancel(this@MainActivity)
                            runCatching { startService(Intent().setClassName(CameraBridge.HELPER,CameraBridge.SERVICE).setAction("stop")) }
                        }) { Text("End session") }
                        OutlinedTextField(sessionStart,{sessionStart=it},label={Text("Session start (YYYY-MM-DD HH:mm)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(sessionEnd,{sessionEnd=it},label={Text("Session end (YYYY-MM-DD HH:mm)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Text("Camera clock time zone: ${sessionZone.id}. New session destination: $chosenAlbumTitle",style=MaterialTheme.typography.bodySmall)
                        Button(onClick={startTimedSession()}) { Text("Save session and watch for power-off") }
                    }
                    Text("The interval is based on camera capture time, so it can be scheduled ahead or backfilled after the hike. Switch the camera OFF with Power-off Standby enabled; the helper keeps watching across app and phone restarts after permissions have been granted.",style=MaterialTheme.typography.bodySmall)
                } }
                Text("Google Photos",style=MaterialTheme.typography.titleLarge)
                Text(if(account.isBlank()) "Not connected. Local imports work without Google setup." else account)
                Button(enabled=!cloudBusy && !running,onClick={signIn()}) { Text(if(account.isBlank()) "Connect Google Photos" else "Reconnect / change Google account") }
                if(account.isBlank()) Text("Connecting assigns unassigned queued photos to the selected account. Google Photos authorization must be configured before sign-in will work.",style=MaterialTheme.typography.bodySmall)
                Text("Saved session destination: $albumLabel")
                if(account.isNotBlank()) {
                    OutlinedButton(enabled=!cloudBusy,onClick={albumAction(false)}) { Text("Load app-created albums") }
                    FilterChip(chosenAlbum.isEmpty(),{chosenAlbum="";chosenAlbumTitle="General library"},label={Text("General library")})
                    albums.forEach { (id,title)->FilterChip(chosenAlbum==id,{chosenAlbum=id;chosenAlbumTitle=title},label={Text(title)}) }
                    OutlinedTextField(albumTitle,{albumTitle=it},label={Text("New album name")},modifier=Modifier.fillMaxWidth())
                    OutlinedButton(enabled=!cloudBusy && albumTitle.isNotBlank(),onClick={albumAction(true)}) { Text("Create album") }
                    Text("Album selection applies when you save a session. Already queued photos keep their destination.",style=MaterialTheme.typography.bodySmall)
                }
                Row { Switch(uploads,{uploads=it;db.set("uploadsEnabled",it.toString());if(it) UploadWorker.schedule(this@MainActivity)});Text("Upload queued photos") }
                Row { Switch(cellular,{cellular=it;db.set("cellular",it.toString());UploadWorker.schedule(this@MainActivity)});Text("Allow cellular uploads") }
                Row { Switch(cleanup,{cleanup=it;db.set("cleanup",it.toString());UploadWorker.schedule(this@MainActivity)});Text("Remove phone copy after confirmed upload") }
                Text("Google Photos uploads use original quality and count toward your Google storage. Camera originals are never deleted.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={scope.launch { withContext(Dispatchers.IO) { db.rows("state != 'UPLOADED'").forEach { db.update(it.id,"retry_at" to 0L) } };UploadWorker.schedule(this@MainActivity) }}) { Text("Retry / recheck uploads") }
                if(message.isNotEmpty()) Text(message)
                if(rows.isNotEmpty()) {
                    Text("Recent photos",style=MaterialTheme.typography.titleLarge)
                    rows.takeLast(20).reversed().forEach { row -> Card { Column(Modifier.padding(12.dp)) {
                        Text(row.path.substringAfterLast('/'));Text("${row.state.lowercase().replace('_',' ')} · ${row.size/1024} KiB")
                        row.error?.let { Text(it) }
                    } } }
                }
                OutlinedButton(onClick={startActivity(Intent(this@MainActivity,DiagnosticsActivity::class.java))}) { Text("Camera diagnostics and individual imports") }
                OutlinedButton(onClick={scope.launch { message=try { withContext(Dispatchers.IO) { val r=SystemHttp(this@MainActivity,cellular).request("GET","https://www.googleapis.com/oauth2/v3/userinfo",emptyMap(),byteArrayOf());"Google connection responded HTTP ${r.code}." } } catch(e:Exception) { e.message ?: "Connection check failed." } }}) { Text("Test Google connection") }
                Text("${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
