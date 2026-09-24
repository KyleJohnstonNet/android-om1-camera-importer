package dev.om1.camerahelper

import android.content.Context

object CameraSession {
    val stats=kotlinx.coroutines.flow.MutableStateFlow(dev.om1.importer.core.CameraStats())
    val transferRate=dev.om1.importer.core.CameraTransferRate()
    fun snapshot()=stats.value.copy(bytesPerSecond=transferRate.bytesPerSecond(android.os.SystemClock.elapsedRealtime()))
    val activity=kotlinx.coroutines.flow.MutableStateFlow(dev.om1.importer.core.ActivityStatus(
        "Monitoring not running","Save a session in Importer to watch for camera standby."))
    val monitoring=kotlinx.coroutines.flow.MutableStateFlow(false)
    private var instance: CameraWifi? = null
    @Synchronized fun wifi(context: Context): CameraWifi = instance ?: CameraWifi(context.applicationContext).also { instance = it }
}
