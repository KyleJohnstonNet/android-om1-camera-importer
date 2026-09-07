package dev.om1.importer

import org.json.JSONArray
import org.json.JSONObject

class CloudFailure(val status:Int,message:String):IllegalStateException(message)
class PhotosApi(private val transport:CloudTransport,private val token:String) {
    companion object { const val BASE="https://photoslibrary.googleapis.com/v1" }
    fun call(method:String,url:String,headers:Map<String,String> = emptyMap(),body:ByteArray=byteArrayOf()):CloudResponse {
        val r=transport.request(method,url,mapOf("Authorization" to "Bearer $token")+headers,body)
        if(r.code !in 200..299) throw CloudFailure(r.code,when(r.code) {
            401->"Google permission expired. Reconnect your account."
            403->"Google denied access. Check Photos API setup, permissions, account storage and quota."
            429->"Google rate limit reached. Upload will retry later."
            else->"Google Photos request failed (HTTP ${r.code}). Local original retained."
        })
        return r
    }
    fun json(path:String,body:JSONObject):JSONObject=JSONObject(call("POST",BASE+path,mapOf("Content-Type" to "application/json"),body.toString().toByteArray()).text())
    fun albums():List<Pair<String,String>> {
        val albums=mutableListOf<Pair<String,String>>();var next=""
        do {
            val suffix=if(next.isEmpty()) "" else "&pageToken="+java.net.URLEncoder.encode(next,"UTF-8")
            val response=JSONObject(call("GET","$BASE/albums?pageSize=50$suffix").text())
            val entries=response.optJSONArray("albums") ?: JSONArray()
            for(i in 0 until entries.length()) { val a=entries.getJSONObject(i);if(a.optBoolean("isWriteable")) albums+=a.getString("id") to a.getString("title") }
            next=response.optString("nextPageToken");check(albums.size<=10000)
        } while(next.isNotEmpty())
        return albums
    }
    fun createAlbum(title:String):Pair<String,String> {
        require(title.isNotBlank() && title.length<=500)
        val a=json("/albums",JSONObject().put("album",JSONObject().put("title",title)))
        return a.getString("id") to a.getString("title")
    }
    fun create(row:PhotoRow,uploadToken:String):String {
        val body=JSONObject().put("newMediaItems",JSONArray().put(JSONObject().put("description","OM-1 import ${row.id}")
            .put("simpleMediaItem",JSONObject().put("fileName",row.path.substringAfterLast('/')).put("uploadToken",uploadToken))))
        row.album?.let { body.put("albumId",it) }
        val response=json("/mediaItems:batchCreate",body).getJSONArray("newMediaItemResults")
        check(response.length()==1) { "Unexpected Google Photos create result." }
        val item=response.getJSONObject(0)
        check(item.optString("uploadToken")==uploadToken) { "Google upload-token mismatch." }
        val code=item.optJSONObject("status")?.optInt("code",0) ?: 0
        if(code!=0) throw CloudFailure(code,"Google could not create this photo (code $code). Original retained.")
        return item.getJSONObject("mediaItem").getString("id").also { check(it.isNotBlank()) }
    }
    /** Only positive, app-created evidence resolves an interrupted create. Absence is not proof of failure. */
    fun reconcile(row:PhotoRow):String? {
        var next="";var pages=0
        do {
            val suffix=if(next.isEmpty()) "" else "&pageToken="+java.net.URLEncoder.encode(next,"UTF-8")
            val response=if(row.album==null) JSONObject(call("GET","$BASE/mediaItems?pageSize=100$suffix").text())
                else json("/mediaItems:search",JSONObject().put("albumId",row.album).put("pageSize",100).apply { if(next.isNotEmpty()) put("pageToken",next) })
            val entries=response.optJSONArray("mediaItems") ?: JSONArray()
            for(i in 0 until entries.length()) { val item=entries.getJSONObject(i)
                if(item.optString("description")=="OM-1 import ${row.id}" && item.optString("filename")==row.path.substringAfterLast('/')) return item.getString("id")
            }
            next=response.optString("nextPageToken");pages++
        } while(next.isNotEmpty() && pages<100)
        return null
    }
}
