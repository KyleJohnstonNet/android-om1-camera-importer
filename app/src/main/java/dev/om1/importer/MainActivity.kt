package dev.om1.importer

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.om1.importer.core.CameraBridge
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DiagnosticLog.initialize(this@MainActivity) }
            setContent { MaterialTheme { Screen() } }
        }
    }
    private fun vpnStatus(): String {
        val manager=getSystemService(ConnectivityManager::class.java)
        val caps=manager.getNetworkCapabilities(manager.activeNetwork)
        return if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true)
            "VPN is the main app’s active network."
        else "VPN is not the main app’s active network. Keep OM-1 Importer included in your VPN provider."
    }
    @Composable private fun Screen() {
        val scope=rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Open Camera Link to scan and connect the camera.") }
        var vpn by remember { mutableStateOf(vpnStatus()) }
        var currentDirectory by remember { mutableStateOf("/DCIM") }
        var files by remember { mutableStateOf(emptyList<dev.om1.importer.core.CameraFile>()) }
        var pageOffset by remember { mutableStateOf(0) }
        var totalEntries by remember { mutableStateOf(0) }
        var pageSize by remember { mutableStateOf(50) }
        fun browse(path: String, offset: Int = 0) {
            busy=true
            scope.launch {
                try {
                    val report=CameraHelperClient.list(this@MainActivity,path,offset)
                    val root=JSONObject(report)
                    val entries=root.getJSONArray("entries")
                    files=(0 until entries.length()).map {
                        val row=entries.getJSONObject(it)
                        dev.om1.importer.core.CameraFile(row.getString("path"),row.getLong("size"),row.getBoolean("directory"))
                    }
                    currentDirectory=path
                    pageOffset=root.optInt("offset",0)
                    totalEntries=root.getInt("total")
                    pageSize=root.optInt("pageSize",200)
                    withContext(Dispatchers.IO) { File(filesDir,"camera-listing.json").writeText(report) }
                    status=if(files.isEmpty()) "No JPEGs or directories in $path." else "Entries ${pageOffset+1}–${pageOffset+files.size} of $totalEntries in $path."
                } catch(e:Exception) { status=e.message ?: "Camera listing failed." }
                finally { busy=false; vpn=vpnStatus() }
            }
        }
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if(uri!=null) scope.launch {
                status=try { withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri,"wt")?.use { it.write(DiagnosticLog.report().toByteArray()) }
                        ?: error("Cannot open file.")
                }; "Report exported." } catch(_:Exception) { "Export failed." }
            }
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("OM-1 Importer",style=MaterialTheme.typography.headlineMedium)
                Text("Connection milestone · ${BuildConfig.VERSION_NAME}")
                Card { Text("This app follows system routing. If your VPN blocks camera access, exclude only the separate OM-1 Camera Link app.",Modifier.padding(16.dp)) }
                Text("Camera setup and requests run in Camera Link. Photo importing and Google Photos uploads are still under development.")
                Button(enabled=!busy,onClick={
                    try {
                        CameraHelperClient.verify(this@MainActivity)
                        startActivity(Intent().setClassName(CameraBridge.HELPER,CameraBridge.ACTIVITY))
                    } catch(_:Exception) { status="Install the matching OM-1 Camera Link build first." }
                }) { Text("Open Camera Link") }
                Button(enabled=!busy,onClick={
                    busy=true
                    scope.launch {
                        try {
                            val report=CameraHelperClient.read(this@MainActivity)
                            val responses=JSONObject(report).getJSONArray("responses")
                            val summary=(0 until responses.length()).joinToString("\n") {
                                val row=responses.getJSONObject(it)
                                "${row.getString("endpoint")}: " + if(row.has("error")) row.getString("error")
                                else "HTTP ${row.getInt("status")} · ${row.optString("body").length} characters"
                            }
                            withContext(Dispatchers.IO) {
                                val atomic=android.util.AtomicFile(File(filesDir,"camera-capabilities.json"))
                                val stream=atomic.startWrite()
                                try { stream.write(report.toByteArray()); atomic.finishWrite(stream) }
                                catch(e:Exception) { atomic.failWrite(stream); throw e }
                                DiagnosticLog.record("camera_helper",summary)
                            }
                            status="Camera Link replied:\n$summary\nNo camera mode was changed."
                        } catch(e:Exception) { status=e.message ?: "Camera Link request failed." }
                        finally { busy=false; vpn=vpnStatus() }
                    }
                }) { Text("Read camera capabilities") }
                Button(enabled=!busy,onClick={browse("/DCIM")}) { Text("Browse camera JPEGs") }
                Text(status)
                if(files.isNotEmpty()) {
                    Text(currentDirectory,style=MaterialTheme.typography.titleMedium)
                    if(totalEntries>pageSize) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=!busy && pageOffset>0,onClick={browse(currentDirectory,0)}) { Text("First") }
                            OutlinedButton(enabled=!busy && pageOffset>0,onClick={browse(currentDirectory,(pageOffset-pageSize).coerceAtLeast(0))}) { Text("Previous") }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled=!busy && pageOffset+files.size<totalEntries,onClick={browse(currentDirectory,pageOffset+pageSize)}) { Text("Next") }
                            OutlinedButton(enabled=!busy && pageOffset+files.size<totalEntries,onClick={browse(currentDirectory,((totalEntries-1)/pageSize)*pageSize)}) { Text("Last") }
                        }
                    }
                    files.forEach { file ->
                        OutlinedButton(enabled=!busy,onClick={
                            if(file.directory) browse(file.path)
                            else {
                                busy=true; status="Importing original JPEG…"
                                scope.launch {
                                    try { status=JpegImport.one(this@MainActivity,file.path,file.size) }
                                    catch(e:Exception) { status=e.message ?: "JPEG import failed." }
                                    finally { busy=false; vpn=vpnStatus() }
                                }
                            }
                        }) { Text(if(file.directory) "Open ${file.path.substringAfterLast('/')}" else "Import ${file.path.substringAfterLast('/')} · ${file.size / 1024} KiB") }
                    }
                }
                HorizontalDivider()
                Text(vpn)
                OutlinedButton(onClick={vpn=vpnStatus()}) { Text("Refresh VPN status") }
                Text("VPN status is a routing observation, not a verified upload. The future uploader must pause if its VPN transport disappears.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={export.launch("om1-connection-report.json")}) { Text("Export diagnostic report") }
            }
        }
    }
}
