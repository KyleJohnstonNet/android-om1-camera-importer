package dev.om1.importer

import android.content.Context
import android.net.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class NetworkUnavailable(message:String): IllegalStateException(message)
data class CloudResponse(val code:Int,val headers:Map<String,String>,val body:ByteArray) {
    fun text()=body.toString(Charsets.UTF_8)
    fun header(name:String)=headers[name.lowercase()]
}
fun interface CloudTransport { fun request(method:String,url:String,headers:Map<String,String>,body:ByteArray):CloudResponse }

/** Uses Android’s default routing, including the system VPN and lockdown policy. */
class SystemHttp(private val context:Context,private val cellular:Boolean):CloudTransport {
    companion object {
        fun validateUrl(url:String) {
            val uri=URI(url)
            require(uri.scheme=="https" && uri.userInfo==null && uri.fragment==null && uri.port in setOf(-1,443) &&
                (uri.host=="photoslibrary.googleapis.com" || uri.host=="www.googleapis.com" && uri.path=="/oauth2/v3/userinfo")) { "Unexpected Google endpoint." }
        }
    }
    override fun request(method:String,url:String,headers:Map<String,String>,body:ByteArray):CloudResponse {
        validateUrl(url)
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.activeNetwork ?: throw NetworkUnavailable("Waiting for an internet connection.")
        val caps=manager.getNetworkCapabilities(network)
        if(QueueStore.get(context).setting("cellular",cellular.toString())!="true" && (caps==null || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)))
            throw NetworkUnavailable("Waiting for Wi-Fi. Cellular uploads are disabled.")
        // Do not bind the process, DNS, or sockets to a physical network or a VPN.
        val connection=URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
            connection.useCaches=false
            headers.forEach { (k,v)->connection.setRequestProperty(k,v) }
            if(method=="POST") { connection.doOutput=true;connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use { it.write(body) } }
            val code=connection.responseCode
            val input=if(code in 200..299) connection.inputStream else connection.errorStream
            val bytes=input?.use { stream ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=stream.read(buffer);if(n<0) break;check(output.size()+n<=2*1024*1024) { "Google response too large." };output.write(buffer,0,n) }
                output.toByteArray()
            } ?: byteArrayOf()
            return CloudResponse(code,connection.headerFields.filterKeys { it!=null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") },bytes)
        } finally { connection.disconnect() }
    }
}
