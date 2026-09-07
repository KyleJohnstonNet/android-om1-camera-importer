package dev.om1.importer

import kotlin.test.*
import org.json.JSONObject

class PhotosApiTest {
    private fun row()=PhotoRow("photo-id","camera","/DCIM/100OMSYS/TEST0001.JPG",1024,"stamp","account","album","CREATE_PENDING","abc.jpg","sha",null,"sealed",262144,null,null,0,0)
    @Test fun `creation fixes album and filename and requires matching upload token`() {
        val transport=CloudTransport { method,url,headers,body ->
            assertEquals("POST",method);assertTrue(url.endsWith("/mediaItems:batchCreate"));assertEquals("Bearer access",headers["Authorization"])
            val request=JSONObject(body.toString(Charsets.UTF_8));assertEquals("album",request.getString("albumId"))
            val item=request.getJSONArray("newMediaItems").getJSONObject(0).getJSONObject("simpleMediaItem")
            assertEquals("TEST0001.JPG",item.getString("fileName"));assertEquals("upload",item.getString("uploadToken"))
            CloudResponse(200,emptyMap(),"""{"newMediaItemResults":[{"uploadToken":"upload","status":{"code":0},"mediaItem":{"id":"created"}}]}""".toByteArray())
        }
        assertEquals("created",PhotosApi(transport,"access").create(row(),"upload"))
        val wrong=PhotosApi(CloudTransport { _,_,_,_->CloudResponse(200,emptyMap(),"""{"newMediaItemResults":[{"uploadToken":"other","mediaItem":{"id":"created"}}]}""".toByteArray()) },"access")
        assertFails { wrong.create(row(),"upload") }
    }
    @Test fun `HTTP success with per-photo failure is not an upload receipt`() {
        val api=PhotosApi(CloudTransport { _,_,_,_->CloudResponse(200,emptyMap(),"""{"newMediaItemResults":[{"uploadToken":"upload","status":{"code":8}}]}""".toByteArray()) },"access")
        assertFailsWith<CloudFailure> { api.create(row(),"upload") }
    }
    @Test fun `uncertain album creation reconciles only positive item evidence`() {
        val api=PhotosApi(CloudTransport { _,url,_,body->
            assertTrue(url.endsWith("/mediaItems:search"));assertEquals("album",JSONObject(body.toString(Charsets.UTF_8)).getString("albumId"))
            CloudResponse(200,emptyMap(),"""{"mediaItems":[{"description":"OM-1 import photo-id","filename":"TEST0001.JPG","id":"recovered"}]}""".toByteArray())
        },"access")
        assertEquals("recovered",api.reconcile(row()))
    }
    @Test fun `cloud endpoints reject redirects to arbitrary hosts and cleartext`() {
        SystemHttp.validateUrl("https://photoslibrary.googleapis.com/v1/uploads?upload_id=test")
        for(url in listOf("http://photoslibrary.googleapis.com/v1/uploads","https://photoslibrary.googleapis.com.evil.test/v1/uploads","https://user@photoslibrary.googleapis.com/v1/uploads","https://192.168.0.10/","https://www.googleapis.com/other")) assertFails { SystemHttp.validateUrl(url) }
    }
}
