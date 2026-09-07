package dev.om1.importer.core

/** Strict DCF paths: camera replies cannot turn the helper into a URL/file proxy. */
object CameraFiles {
    const val MAX_JPEG_BYTES = 64L * 1024 * 1024
    fun directory(path: String): Boolean = path == "/DCIM" || Regex("/DCIM/[0-9]{3}[A-Za-z0-9_]{5}").matches(path)
    fun jpeg(path: String): Boolean = Regex("/DCIM/[0-9]{3}[A-Za-z0-9_]{5}/[A-Za-z0-9_]{1,32}\\.[Jj][Pp][Gg]").matches(path)
    fun listingUrl(path: String): String {
        require(directory(path)) { "Unsupported camera directory." }
        return "http://${CameraEndpoints.HOST}/get_imglist.cgi?DIR=$path"
    }
    fun originalUrl(path: String): String {
        require(jpeg(path)) { "Unsupported camera JPEG path." }
        return "http://${CameraEndpoints.HOST}$path"
    }
    fun parse(body: String, requested: String): List<CameraFile> {
        require(directory(requested))
        require(body.length <= 512 * 1024) { "Camera listing too large." }
        return body.lineSequence().filter { it.isNotBlank() && it.trim() !in setOf("WLANSD_FILELIST", "VER_100") }.map { line ->
            val fields = line.trimEnd('\r').split(',')
            require(fields.size >= 6) { "Unrecognized camera listing row." }
            require(fields[0] == requested) { "Camera listing directory mismatch." }
            val path = fields[0] + "/" + fields[1]
            val size = fields[2].toLongOrNull() ?: error("Invalid camera file size.")
            require(size >= 0)
            when {
                directory(path) && size == 0L -> CameraFile(path,0,true)
                jpeg(path) && size in 1..MAX_JPEG_BYTES -> CameraFile(path,size,false,fields[4]+":"+fields[5])
                else -> null // RAW, movies and unsupported files are not import candidates.
            }
        }.filterNotNull().toList().also { require(it.size <= 10000) { "Too many camera entries." } }
    }
}
data class CameraFile(val path: String, val size: Long, val directory: Boolean, val stamp: String = "")
