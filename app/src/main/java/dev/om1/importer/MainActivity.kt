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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.om1.importer.core.RecentPhotos
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.BitmapFactory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
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
        setContent { ImporterTheme { Screen() } }
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
        val uploadActivity by UploadWorker.activity.collectAsStateWithLifecycle()
        val uploadLimit by UploadWorker.parallelLimit.collectAsStateWithLifecycle()
        var cameraStatus by remember { mutableStateOf(dev.om1.importer.core.ActivityStatus("Checking Camera Link","Reading the current camera activity…")) }
        var cameraStats by remember { mutableStateOf<dev.om1.importer.core.CameraStats?>(null) }
        val cardStats by ImportBatch.cardStats.collectAsStateWithLifecycle()
        var uploadOverview by remember { mutableStateOf("Checking the upload queue…") }
        var message by rememberSaveable { mutableStateOf("") }
        var account by remember { mutableStateOf("") }
        var rows by remember { mutableStateOf(emptyList<PhotoRow>()) }
        var preview by remember { mutableStateOf<PhotoRow?>(null) }
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
        val gpsStatus by LocationRecordingService.status.collectAsStateWithLifecycle()
        var recordGps by remember { mutableStateOf(db.setting("recordGps")=="true") }
        var geotag by remember { mutableStateOf(db.setting("geotag")=="true") }
        var confirmClearGps by remember { mutableStateOf(false) }
        fun startGps() {
            runCatching { startForegroundService(Intent(this@MainActivity,LocationRecordingService::class.java)) }
                .onFailure { message="GPS could not start. Check precise location permission, then toggle recording on again." }
        }
        val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true) {
                db.set("recordGps","true");recordGps=true;startGps()
            } else { recordGps=false;db.set("recordGps","false");message="Precise location permission is required to record photo locations." }
        }
        LaunchedEffect(resumeCount,revision) {
            recordGps=db.setting("recordGps")=="true"
        }
        LaunchedEffect(resumeCount) {
            if(db.setting("recordGps")=="true" && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startGps()
        }
        if(confirmClearGps) AlertDialog(onDismissRequest={confirmClearGps=false},title={Text("Delete recorded GPS history?")},
            text={Text("This cannot be undone. Photos and already prepared uploads are unchanged. New fixes will continue if recording is enabled.")},
            confirmButton={TextButton(onClick={confirmClearGps=false;scope.launch { withContext(Dispatchers.IO) { LocationHistory.get(this@MainActivity).clear() };message="Recorded GPS history deleted." }}) { Text("Delete history") }},
            dismissButton={TextButton(onClick={confirmClearGps=false}) { Text("Cancel") }})
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(isActive) {
                    try { val snapshot=CameraHelperClient.status(this@MainActivity);cameraStatus=snapshot.activity;cameraStats=snapshot.stats }
                    catch(_:TimeoutCancellationException) {
                        cameraStats=null
                        cameraStatus=dev.om1.importer.core.ActivityStatus("Camera Link is not responding","Retrying its status automatically. Open Camera Link if this continues.")
                    }
                    catch(e:CancellationException) { throw e }
                    catch(_:Exception) {
                        cameraStats=null
                        cameraStatus=dev.om1.importer.core.ActivityStatus("Camera Link status unavailable","Open Camera Link to check its state and permissions. Make sure both apps are updated.")
                    }
                    delay(2000)
                }
            }
        }
        LaunchedEffect(now,rows,uploadActivity,uploadLimit,uploads,cellular) {
            uploadOverview=withContext(Dispatchers.IO) {
                val manager=getSystemService(android.net.ConnectivityManager::class.java)
                val caps=manager.getNetworkCapabilities(manager.activeNetwork)
                val accountId=db.setting("accountId")
                UploadOverview.describe(rows.filter { it.account==accountId && it.state in setOf("READY","UPLOADING","CREATE_PENDING","CREATING","UNCERTAIN") },
                    uploadActivity.size,uploadLimit,PowerPolicy.saving(this@MainActivity),uploads,accountId.isNotBlank(),
                    caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)==true,
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
                    cellular,now)
            }
        }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { DiagnosticLog.initialize(this@MainActivity) }
            UploadWorker.schedule(this@MainActivity)
            while(true) { delay(1000);now=System.currentTimeMillis() }
        }
        LaunchedEffect(revision,now/60000) {
            withContext(Dispatchers.IO) {
                val snapshot=db.rows();val email=db.setting("accountEmail")
                PhotoThumbnails.prune(this@MainActivity)
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
        preview?.let { photo -> PhotoPopup(photo) { preview=null } }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp, vertical=18.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    Column { Text("OM-1 Importer", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold); Text("Bring your camera roll home", style=MaterialTheme.typography.bodyLarge) }
                    StatusTag(if(running) "SYNCING" else if(db.session()!=null) "READY" else "SETUP", if(running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                }
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Current session", style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(sessionText, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.SemiBold, color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Camera · ${cameraStatus.title}",fontWeight=FontWeight.SemiBold)
                    Text(cameraStatus.detail)
                    cameraStatus.countdown(now)?.let { Text(it,style=MaterialTheme.typography.labelLarge) }
                    Text(if(running) "Import · $importStatus" else "Last import · $importStatus",style=MaterialTheme.typography.bodySmall)
                    Text("Google Photos · $uploadOverview",style=MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.18f))
                    Text("${rows.count { it.state=="UPLOADED" }} uploaded  ·  ${rows.count { it.state in setOf("READY","UPLOADING","CREATE_PENDING") }} queued  ·  ${rows.count { it.state=="DISCOVERED" }} on camera", style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onPrimaryContainer)
                    if(running) Button(onClick={startService(Intent(this@MainActivity,ImportService::class.java).setAction("stop"))}) { Text("Pause import") }
                    else Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick={begin("import")},enabled=db.session()!=null) { Text("Sync now") }
                        OutlinedButton(enabled=db.session()!=null,onClick={ db.endSession();ImportWorker.cancel(this@MainActivity);runCatching { startService(Intent().setClassName(CameraBridge.HELPER,CameraBridge.SERVICE).setAction("stop")) } }) { Text("End") }
                    }
                } }
                ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Camera stats",style=MaterialTheme.typography.titleMedium)
                    cameraStats?.lines(now)?.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                        ?: Text("Camera Link stats unavailable",style=MaterialTheme.typography.bodySmall)
                    Text("Card counts · most recent import",style=MaterialTheme.typography.labelMedium)
                    for(slot in 1..2) Text(cardStats.firstOrNull { it.slot==slot }?.text()
                        ?: "Slot $slot · no completed directory scan",style=MaterialTheme.typography.bodySmall)
                    Text("Observations only; no extra camera wake-ups. Counts exclude RAW, movies and unsupported JPEGs. Stats reset when their app restarts.",style=MaterialTheme.typography.bodySmall)
                } }
                SectionTitle("Plan a shooting session", "The camera helper watches for power-off, then collects eligible photos.")
                ElevatedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(sessionStart,{sessionStart=it},label={Text("Start · YYYY-MM-DD HH:mm")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(sessionEnd,{sessionEnd=it},label={Text("End · YYYY-MM-DD HH:mm")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("Camera time zone: ${sessionZone.id}\nDestination: $chosenAlbumTitle",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={startTimedSession()}, modifier=Modifier.fillMaxWidth()) { Text("Save session & watch for power-off") }
                } }
                SectionTitle("Google Photos", if(account.isBlank()) "Optional—local imports work without it." else "Connected as $account")
                ElevatedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(enabled=!cloudBusy && !running,onClick={signIn()},modifier=Modifier.fillMaxWidth()) { Text(if(account.isBlank()) "Connect Google Photos" else "Change Google account") }
                    Text("Saved session destination: $albumLabel",style=MaterialTheme.typography.bodyMedium)
                    if(account.isNotBlank()) {
                        OutlinedButton(enabled=!cloudBusy,onClick={albumAction(false)}) { Text("Load albums") }
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(chosenAlbum.isEmpty(),{chosenAlbum="";chosenAlbumTitle="General library"},label={Text("General library")})
                            albums.forEach { (id,title)->FilterChip(chosenAlbum==id,{chosenAlbum=id;chosenAlbumTitle=title},label={Text(title)}) }
                        }
                        OutlinedTextField(albumTitle,{albumTitle=it},label={Text("New album name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedButton(enabled=!cloudBusy && albumTitle.isNotBlank(),onClick={albumAction(true)}) { Text("Create album") }
                    }
                } }
                SectionTitle("Upload preferences", "Original-quality uploads count toward Google storage. Camera files are never deleted.")
                ElevatedCard { Column(Modifier.padding(horizontal=16.dp,vertical=6.dp)) {
                    PreferenceSwitch("Upload queued photos", uploads) { uploads=it;db.set("uploadsEnabled",it.toString());if(it) UploadWorker.schedule(this@MainActivity) }
                    PreferenceSwitch("Allow cellular uploads", cellular) { cellular=it;db.set("cellular",it.toString());UploadWorker.schedule(this@MainActivity) }
                    if(dev.om1.importer.core.CameraImportSafety.RETAIN_LOCAL_COPIES)
                        Text("Safety mode: camera downloads are serial. Phone originals and GPS upload copies are temporarily kept after upload while corruption is investigated. This uses additional phone storage.",Modifier.padding(vertical=9.dp),style=MaterialTheme.typography.bodyMedium)
                    else PreferenceSwitch("Remove phone original after upload", cleanup) { cleanup=it;db.set("cleanup",it.toString());UploadWorker.schedule(this@MainActivity) }
                    OutlinedButton(onClick={scope.launch { withContext(Dispatchers.IO) { db.rows("state != 'UPLOADED'").forEach { db.update(it.id,"retry_at" to 0L) } };UploadWorker.schedule(this@MainActivity) }},modifier=Modifier.padding(vertical=10.dp)) { Text("Retry pending uploads") }
                } }
                SectionTitle("Photo locations", "Record this phone’s GPS independently of camera imports. Both options are off by default.")
                ElevatedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    PreferenceSwitch("Record phone GPS",recordGps) { enabled ->
                        if(!enabled) { recordGps=false;db.set("recordGps","false");stopService(Intent(this@MainActivity,LocationRecordingService::class.java)) }
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                    Text(gpsStatus,style=MaterialTheme.typography.bodySmall)
                    PreferenceSwitch("Geotag new uploads from GPS history",geotag) {
                        geotag=it;db.set("geotag",it.toString())
                    }
                    Text("History stays on this phone for 30 days. A fix must be within 2 minutes of capture and accurate to 100 m. Existing photo GPS is preserved. GPS is added to a separate upload copy and shared with Google Photos; originals remain unchanged. Uploads already started keep their selected bytes.",style=MaterialTheme.typography.bodySmall)
                    Text("Camera clock/timezone verification is not yet supported by the verified camera protocol. Match the camera clock to the phone before shooting. Photos without an EXIF timezone use the saved session timezone; an incorrect camera clock can give incorrect GPS matches.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick={confirmClearGps=true}) { Text("Delete recorded GPS history") }
                } }
                if(message.isNotEmpty()) Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) { Text(message,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onErrorContainer) }
                if(rows.isNotEmpty()) {
                    SectionTitle("Recent photos", "Latest 20 · Hold a pending photo to upload now, including over cellular. Battery saver still pauses transfers.")
                    Surface(shape=MaterialTheme.shapes.medium,tonalElevation=1.dp) {
                        Column {
                            rows.takeLast(RecentPhotos.COUNT).reversed().forEach { row -> key(row.id) {
                                RecentPhoto(row, row.account==db.setting("accountId"),uploadActivity[row.id],now,onPreview={ preview=row }) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { db.prioritize(row.id) }
                                        UploadWorker.schedule(this@MainActivity,expedited=true)
                                        message=if(PowerPolicy.saving(this@MainActivity)) "Photo prioritized. Upload will start after battery saver is off."
                                            else if(!uploads) "Photo prioritized. Enable queued uploads to start."
                                            else "Prioritized for upload now; cellular is allowed for this photo."
                                    }
                                }
                            } }
                        }
                    }
                }
                HorizontalDivider()
                OutlinedButton(onClick={startActivity(Intent(this@MainActivity,DiagnosticsActivity::class.java))},modifier=Modifier.fillMaxWidth()) { Text("Camera diagnostics & individual imports") }
                TextButton(onClick={scope.launch { message=try { withContext(Dispatchers.IO) { val r=SystemHttp(this@MainActivity,cellular).request("GET","https://www.googleapis.com/oauth2/v3/userinfo",emptyMap(),byteArrayOf());"Google connection responded HTTP ${r.code}." } } catch(e:Exception) { e.message ?: "Connection check failed." } }},modifier=Modifier.fillMaxWidth()) { Text("Test Google connection") }
                Text(BuildConfig.VERSION_NAME,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RecentPhoto(row:PhotoRow,currentAccount:Boolean,activity:String?,now:Long,onPreview:()->Unit,onUploadNow:()->Unit) {
    val context=LocalContext.current
    val gpsNote by produceState<String?>(null,row.id,row.state) {
        value=withContext(Dispatchers.IO) { QueueStore.get(context).payload(row.id)?.note }
    }
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,row.sha,row.local) {
        value=withContext(Dispatchers.IO) {
            runCatching { PhotoThumbnails.load(context,row.sha,row.local)?.asImageBitmap() }.getOrNull()
        }
    }
    val pending=currentAccount && row.state in setOf("READY","UPLOADING","CREATE_PENDING","UNCERTAIN")
    Row(Modifier.fillMaxWidth().combinedClickable(onClick=onPreview,onLongClick=if(pending) onUploadNow else null,
        onLongClickLabel="Upload now, allowing cellular").padding(horizontal=10.dp,vertical=6.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.size(48.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceVariant) {
            val bitmap=thumbnail
            if(bitmap!=null) Image(bitmap,contentDescription=row.path.substringAfterLast('/'),contentScale=ContentScale.Crop)
            else Box(contentAlignment=Alignment.Center) { Text("JPEG",style=MaterialTheme.typography.labelSmall) }
        }
        Column(Modifier.weight(1f)) {
            Text(row.path.substringAfterLast('/'),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${if(row.slot>0) "Slot ${row.slot} · " else ""}${if(row.priority>0) "Priority · " else ""}${row.state.lowercase().replace('_',' ')} · ${row.size/1024} KiB",style=MaterialTheme.typography.bodySmall)
            activity?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
            if(activity==null && pending && row.retryAt>now) Text("Retry eligible in ${(row.retryAt-now+999)/1000} s",style=MaterialTheme.typography.labelSmall)
            row.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis) }
            gpsNote?.takeUnless { it=="Geotagging off at upload start" }?.let {
                Text(it,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun PhotoPopup(row:PhotoRow,onClose:()->Unit) {
    val context=LocalContext.current
    var loaded by remember(row.id) { mutableStateOf(false) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,row.id) {
        value=withContext(Dispatchers.IO) {
            runCatching { PhotoThumbnails.load(context,row.sha,row.local,full=true)?.asImageBitmap() }.getOrNull()
        }
        loaded=true
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.94f)).clickable(onClickLabel="Close image",onClick=onClose),
            contentAlignment=Alignment.Center) {
            val image=bitmap
            if(image!=null) Image(image,contentDescription=row.path.substringAfterLast('/'),
                modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else Text(if(loaded) "Preview unavailable. This photo may not have been imported yet." else "Loading image…",
                color=Color.White,modifier=Modifier.padding(24.dp))
            Text("Tap to close",color=Color.White,modifier=Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

@Composable private fun SectionTitle(title:String, subtitle:String) = Column(verticalArrangement=Arrangement.spacedBy(2.dp)) { Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun StatusTag(label:String, color:Color) = Surface(color=color.copy(alpha=.14f),shape=MaterialTheme.shapes.small) { Text(label,Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=color,fontWeight=FontWeight.Bold) }
@Composable private fun PreferenceSwitch(label:String, checked:Boolean, onCheckedChange:(Boolean)->Unit) = Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge);Switch(checked,onCheckedChange) }
