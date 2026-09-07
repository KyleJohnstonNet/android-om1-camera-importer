package dev.om1.camerahelper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.om1.importer.core.CameraBridge
import dev.om1.importer.core.CameraEndpoints
import dev.om1.importer.core.Ipv4Subnet
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL
import java.time.Instant
import dev.om1.importer.core.CameraFiles
import java.io.File
import java.security.MessageDigest

object CameraHttp {
    private fun validate(context: Context, network: Network) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(network)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "Camera Wi-Fi is unavailable." }
        check(manager.getLinkProperties(network)?.linkAddresses?.any {
            it.address is Inet4Address && Ipv4Subnet.contains(it.address.address, byteArrayOf(192.toByte(),168.toByte(),0,10), it.prefixLength)
        } == true) { "Camera is not on the expected local subnet." }
    }

    fun read(context: Context, network: Network): String {
        validate(context, network)
        val responses = JSONArray()
        for (path in CameraEndpoints.paths) {
            val result = JSONObject().put("endpoint", path)
            var connection: HttpURLConnection? = null
            try {
                connection = network.openConnection(URL(CameraEndpoints.url(path)), Proxy.NO_PROXY) as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "OI.Share v2")
                connection.setRequestProperty("Connection", "close")
                val status = connection.responseCode
                result.put("status", status)
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                if (input != null) input.use {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= CameraBridge.MAX_RESPONSE_BYTES) { "Camera response too large." }
                        output.write(buffer, 0, count)
                    }
                    result.put("body", output.toString("UTF-8"))
                }
            } catch (e: Exception) {
                // Never echo URLs or server-provided exception messages over IPC.
                result.put("error", e.javaClass.simpleName)
            } finally { connection?.disconnect() }
            responses.put(result)
        }
        return JSONObject().put("schemaVersion",1).put("transport","camera_helper")
            .put("time",Instant.now().toString()).put("networkHandle",network.networkHandle)
            .put("responses",responses).toString(2)
    }

    private fun connection(network: Network, url: String): HttpURLConnection =
        (network.openConnection(URL(url), Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod="GET"; instanceFollowRedirects=false
            connectTimeout=5000; readTimeout=5000
            setRequestProperty("User-Agent","OI.Share v2")
            setRequestProperty("Connection","close")
            setRequestProperty("Accept-Encoding","identity")
        }

    fun list(context: Context, network: Network, directory: String, offset: Int = 0): String {
        require(offset in 0..10000) { "Invalid camera listing page." }
        validate(context,network)
        val conn=connection(network,CameraFiles.listingUrl(directory))
        try {
            val status=conn.responseCode
            check(status==200) { "Camera listing returned HTTP $status. Camera mode may not permit browsing." }
            val deadline=android.os.SystemClock.elapsedRealtime()+15000
            val bytes=ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer=ByteArray(8192)
                while(true) {
                    check(android.os.SystemClock.elapsedRealtime()<deadline) { "Camera listing timed out." }
                    val count=input.read(buffer)
                    if(count<0) break
                    check(bytes.size()+count<=512*1024) { "Camera directory is too large for this diagnostic." }
                    bytes.write(buffer,0,count)
                }
            }
            val entries=CameraFiles.parse(bytes.toString("UTF-8"),directory)
            // Bound Binder replies while allowing every entry to be browsed.
            val listing=dev.om1.importer.core.CameraPage.of(entries,offset)
            val page=listing.entries
            val profile=checkNotNull(CameraProfileStore.load(context)) { "Save a camera profile first." }
            val cameraId=MessageDigest.getInstance("SHA-256").digest(profile.ssid.toByteArray()).joinToString("") { "%02x".format(it) }
            return JSONObject().put("cameraId",cameraId).put("directory",directory).put("total",entries.size)
                .put("offset",listing.offset).put("pageSize",dev.om1.importer.core.CameraPage.SIZE)
                .put("truncated",entries.size>page.size).put("entries",JSONArray().apply {
                    page.forEach { put(JSONObject().put("path",it.path).put("size",it.size).put("directory",it.directory).put("stamp",it.stamp)) }
                }).toString()
        } finally { conn.disconnect() }
    }

    fun download(context: Context, network: Network, path: String, expected: Long): Pair<File,String> {
        validate(context,network)
        require(expected in 1..CameraFiles.MAX_JPEG_BYTES)
        val conn=connection(network,CameraFiles.originalUrl(path))
        val file=File.createTempFile("camera-", ".part", context.cacheDir)
        try {
            check(conn.responseCode==200) { "Camera JPEG request failed." }
            val declared=conn.contentLengthLong
            check(declared<0 || declared==expected) { "Camera file size changed. Refresh the listing." }
            val deadline=android.os.SystemClock.elapsedRealtime()+120000
            val digest=MessageDigest.getInstance("SHA-256")
            var count=0L
            conn.inputStream.use { input -> file.outputStream().use { output ->
                val buffer=ByteArray(64*1024)
                while(true) {
                    check(android.os.SystemClock.elapsedRealtime()<deadline) { "Camera transfer timed out." }
                    val n=input.read(buffer)
                    if(n<0) break
                    count+=n
                    check(count<=expected && count<=CameraFiles.MAX_JPEG_BYTES) { "Camera file exceeds expected size." }
                    digest.update(buffer,0,n); output.write(buffer,0,n)
                }
                output.fd.sync()
            } }
            check(count==expected) { "Incomplete camera JPEG." }
            file.inputStream().buffered(65536).use { dev.om1.importer.core.JpegStructure.verify(it) }
            val sha=digest.digest().joinToString("") { "%02x".format(it) }
            return file to JSONObject().put("path",path).put("bytes",count).put("sha256",sha).toString()
        } catch(e:Exception) { file.delete(); throw e }
        finally { conn.disconnect() }
    }
}
