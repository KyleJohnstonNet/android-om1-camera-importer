package dev.om1.camerahelper

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.*
import dev.om1.importer.core.CameraBridge
import dev.om1.importer.core.CameraQr
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Screen() } }
    }
    @Composable private fun Screen() {
        val wifi = remember { CameraSession.wifi(this) }
        val active by wifi.active.collectAsStateWithLifecycle()
        val status by wifi.status.collectAsStateWithLifecycle()
        var ssid by rememberSaveable { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var wpa3 by rememberSaveable { mutableStateOf(true) }
        var bluetoothName by remember { mutableStateOf<String?>(null) }
        var bluetoothPassword by remember { mutableStateOf<String?>(null) }
        var wakeBluetooth by rememberSaveable { mutableStateOf(true) }
        var profileLoaded by remember { mutableStateOf(false) }
        var profileMessage by remember { mutableStateOf("Loading saved camera…") }
        val scope=rememberCoroutineScope()
        LaunchedEffect(Unit) {
            try {
                val saved=withContext(Dispatchers.IO) { CameraProfileStore.load(this@MainActivity) }
                if(saved!=null) {
                    ssid=saved.ssid; password=saved.password; wpa3=saved.wpa3
                    bluetoothName=saved.bluetoothName; bluetoothPassword=saved.bluetoothPassword
                    profileMessage=if(saved.bluetoothName!=null) "Saved camera and Bluetooth details loaded." else "Saved Wi-Fi loaded. Scan the QR code once more to add Bluetooth wake."
                } else profileMessage="Camera details will be remembered after a successful connection."
            } catch(_:Exception) { profileMessage="Saved camera could not be unlocked. Forget it, then scan again." }
            finally { profileLoaded=true }
        }
        val useBluetooth = wakeBluetooth && bluetoothName!=null && bluetoothPassword!=null
        val required = (if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES,Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) +
            if(useBluetooth) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        fun connect() {
            try {
                startForegroundService(Intent(this,CameraService::class.java).setAction("connect").apply {
                    putExtra("ssid",ssid); putExtra("password",password); putExtra("wpa3",wpa3)
                    putExtra("bluetoothName",bluetoothName); putExtra("bluetoothPassword",bluetoothPassword)
                    putExtra("wakeBluetooth",useBluetooth)
                })
            } catch (_: Exception) { wifi.status.value="Unable to start Camera Link. Check app permissions." }
        }
        fun bluetoothEnabled(): Boolean {
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return false
            return getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        }
        val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if(result.resultCode == Activity.RESULT_OK && bluetoothEnabled()) {
                if(profileLoaded && password.isNotEmpty() && required.all { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }) connect()
                else wifi.status.value="Bluetooth is on. Tap Connect camera to continue."
            } else wifi.status.value="Bluetooth was not enabled. Tap Connect camera to try again."
        }
        fun prepareConnection() {
            if(!useBluetooth || bluetoothEnabled()) connect()
            else try {
                wifi.status.value="Allow Camera Link to turn on Bluetooth in Android’s dialog…"
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch(_:Exception) { wifi.status.value="Unable to open the Bluetooth prompt. Turn on Bluetooth in Quick Settings, then retry." }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (required.all { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }) prepareConnection()
            else wifi.status.value="Allow nearby-device access and notifications to keep a visible camera connection."
        }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            val text = result.contents
            if (text == null) wifi.status.value="Scan cancelled or camera unavailable."
            else try {
                val details = CameraQr.parse(text)
                ssid=details.ssid; password=details.password; wpa3=details.wpa3
                bluetoothName=details.bluetoothName; bluetoothPassword=details.bluetoothPassword
                profileLoaded=false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { CameraProfileStore.save(this@MainActivity,details.ssid,details.password,details.wpa3,details.bluetoothName,details.bluetoothPassword) }
                        profileMessage=if(details.bluetoothName!=null) "Camera Wi-Fi and Bluetooth details saved." else "Wi-Fi saved. This QR code contains no Bluetooth details."
                        wifi.status.value="Camera details scanned. Tap Connect camera."
                    } catch(_:Exception) { profileMessage="Could not save the scanned camera details." }
                    finally { profileLoaded=true }
                }
            } catch (_: Exception) { wifi.status.value="QR code not recognized. Scan the camera’s Wi-Fi setup code." }
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("OM-1 Camera Link",style=MaterialTheme.typography.headlineMedium)
                Text("Camera-only helper · ${BuildConfig.VERSION_NAME}")
                Card { Text("If your VPN blocks camera access, exclude only OM-1 Camera Link using its split-tunnel settings. Keep your regular internet connection available.",Modifier.padding(16.dp)) }
                Text("This helper handles the camera connection. Google Photos uploads belong to OM-1 Importer and are not implemented yet.")
                Button(enabled=!active && profileLoaded,onClick={ scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(CameraQrActivity::class.java).setPrompt("Scan the Wi-Fi QR code on your OM camera")
                    .setBeepEnabled(false).setBarcodeImageEnabled(false)) }) { Text("Scan camera Wi-Fi QR code") }
                OutlinedTextField(ssid,{ssid=it; bluetoothName=null; bluetoothPassword=null},enabled=!active && profileLoaded,label={Text("Camera Wi-Fi name (SSID)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password,{password=it; bluetoothName=null; bluetoothPassword=null},enabled=!active && profileLoaded,label={Text("Camera Wi-Fi password")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FilterChip(wpa3,{wpa3=true},enabled=!active && profileLoaded,label={Text("WPA3")})
                    FilterChip(!wpa3,{wpa3=false},enabled=!active && profileLoaded,label={Text("WPA2")})
                }
                if(bluetoothName!=null) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Checkbox(wakeBluetooth,{wakeBluetooth=it},enabled=!active)
                    Text("Start camera Wi-Fi using Bluetooth")
                }
                Button(enabled=profileLoaded && (active || (ssid.isNotBlank() && password.isNotEmpty())),onClick={
                    if (active) {
                        startService(Intent(this@MainActivity,CameraService::class.java).setAction("stop"))
                    } else if (required.all { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }) prepareConnection()
                    else permission.launch(required)
                }) { Text(if(active) "Disconnect camera Wi-Fi" else "Connect camera") }
                Text(status)
                Text(profileMessage,style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!active && profileLoaded,onClick={
                    profileLoaded=false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { CameraProfileStore.forget(this@MainActivity) }
                            ssid=""; password=""; wpa3=true; bluetoothName=null; bluetoothPassword=null
                            profileMessage="Saved camera forgotten."
                        } catch(_:Exception) { profileMessage="Unable to forget the saved camera." }
                        finally { profileLoaded=true }
                    }
                }) { Text("Forget camera") }
                Text("A notification keeps the connection visible while you use the importer. No automatic restart or reconnect after process death.")
                OutlinedButton(onClick={
                    runCatching { startActivity(Intent().setClassName(CameraBridge.MAIN,"dev.om1.importer.MainActivity")) }
                        .onFailure { wifi.status.value="Install OM-1 Importer to read camera capabilities." }
                }) { Text("Return to OM-1 Importer") }
            }
        }
    }
}
