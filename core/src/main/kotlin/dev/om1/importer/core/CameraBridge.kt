package dev.om1.importer.core

object CameraBridge {
    const val HELPER = "dev.om1.camerahelper"
    const val MAIN = "dev.om1.importer.diagnostic"
    const val PERMISSION = "dev.om1.camerahelper.permission.ACCESS_CAMERA"
    const val SERVICE = "dev.om1.camerahelper.CameraService"
    const val ACTIVITY = "dev.om1.camerahelper.MainActivity"
    const val READ_CAPABILITIES = 1
    const val LIST_DIRECTORY = 2
    const val DOWNLOAD_JPEG = 3
    const val MAX_RESPONSE_BYTES = 32 * 1024
    const val MAX_REPORT_CHARS = 160 * 1024
}

/** Closed camera endpoint list: neither IPC nor a camera response supplies a URL. */
object CameraEndpoints {
    const val HOST = "192.168.0.10"
    val paths = listOf("get_commandlist.cgi", "get_connectmode.cgi")
    fun url(path: String): String {
        require(path in paths) { "Unsupported camera operation." }
        return "http://$HOST/$path"
    }
}
