package dev.om1.importer

import org.json.JSONObject
import java.io.IOException
import kotlin.test.*

class SessionAndCreationTest {
    @Test fun `explicit library and immutable session fields survive persistence`() {
        val session=SavedSession("one",100,200,"America/Los_Angeles","account",null,"General library","camera")
        assertEquals(session,SavedSession.read(JSONObject(session.json().toString()),"UTC"))
        assertNull(SavedSession.read(JSONObject(session.json().toString()).put("album",JSONObject.NULL)).album)
    }

    @Test fun `legacy session gets identity and timezone without fabricating an album`() {
        val legacy=JSONObject().put("starts",100).put("ends",200).put("account","")
        val session=SavedSession.read(legacy,"UTC")
        assertTrue(session.id.isNotBlank());assertEquals("UTC",session.zone)
        assertNull(session.album);assertEquals("General library",session.albumTitle)
        assertEquals(session,SavedSession.read(session.json(),"Asia/Tokyo"))
    }

    @Test fun `per item rpc statuses are not treated as http statuses`() {
        assertEquals(CreationRecovery.RETRY_CREATE,creationRecovery(MediaCreationRejected(8)))
        assertEquals(CreationRecovery.RETRY_CREATE,creationRecovery(MediaCreationRejected(13)))
        assertEquals(CreationRecovery.UPLOAD_AGAIN,creationRecovery(MediaCreationRejected(3)))
        assertEquals(CreationRecovery.RECONCILE,creationRecovery(IOException("timeout")))
        assertEquals(CreationRecovery.RETRY_CREATE,creationRecovery(CloudFailure(429,"rate limited")))
        assertEquals(CreationRecovery.UPLOAD_AGAIN,creationRecovery(CloudFailure(400,"invalid token")))
        assertEquals(CreationRecovery.RECONCILE,creationRecovery(CloudFailure(503,"unavailable")))
    }
}
