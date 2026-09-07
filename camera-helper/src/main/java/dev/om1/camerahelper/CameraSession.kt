package dev.om1.camerahelper

import android.content.Context

object CameraSession {
    private var instance: CameraWifi? = null
    @Synchronized fun wifi(context: Context): CameraWifi = instance ?: CameraWifi(context.applicationContext).also { instance = it }
}
