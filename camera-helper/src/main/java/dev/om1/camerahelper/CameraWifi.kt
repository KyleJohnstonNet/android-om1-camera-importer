package dev.om1.camerahelper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/** Service-scoped camera connection. The owner decides when to save a confirmed profile. */
class CameraWifi(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    val status = MutableStateFlow("Enter the Wi-Fi name and password shown by your camera.")
    val active = MutableStateFlow(false)
    var network: Network? = null
        private set

    fun connect(ssid: String, password: String, wpa3: Boolean, onConnected: () -> Unit = {}) {
        require(ssid.isNotBlank()) { "Enter the camera Wi-Fi name." }
        require(password.isNotEmpty()) { "Enter the camera Wi-Fi password." }
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (wpa3) setWpa3Passphrase(password) else setWpa2Passphrase(password)
        }.build()
        disconnect()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier).build()
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (callback !== this) return
                network = available
                status.value = "Camera Wi-Fi connected. You can read camera capabilities now."
                onConnected()
            }
            override fun onLost(lost: Network) {
                if (callback !== this) return
                network = null
                status.value = "Camera connection lost. Disconnect and connect again to retry."
            }
            override fun onUnavailable() {
                if (callback !== this) return
                callback = null
                network = null
                active.value = false
                status.value = "Connection unavailable or declined. Check camera Wi-Fi, name, password and security type."
            }
        }
        callback = listener
        active.value = true
        status.value = "Approve the camera connection in Android’s dialog…"
        try { manager.requestNetwork(request, listener, handler, 60000) }
        catch (e: Exception) {
            disconnect()
            status.value = "Unable to request camera Wi-Fi. Check permissions and that Wi-Fi is enabled."
            throw e
        }
    }

    fun disconnect() {
        val previous = callback
        callback = null
        network = null
        active.value = false
        if (previous != null) runCatching { manager.unregisterNetworkCallback(previous) }
        status.value = "Camera connection released."
    }
}
