package dev.om1.importer

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.TimeZone
import java.util.UUID
import dev.om1.importer.core.SessionTime
import java.time.ZoneId
import org.json.JSONObject

/** Every test uses its own database. Never touches the user's queue or settings. */
class QueueStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="queue-test-${UUID.randomUUID()}.db"
    private lateinit var db:QueueStore
    private val zone=ZoneId.of("America/Los_Angeles")
    private val start=SessionTime.parse("2026-09-12 08:00",zone)
    private val end=SessionTime.parse("2026-09-12 12:00",zone)
    private val photo=SourcePhoto("/DCIM/100OMSYS/TEST.JPG",1024,"23852:21447")
    @Before fun setup() { db=QueueStore(context,name);db.set("accountId","owner") }
    @After fun teardown() { db.close();context.deleteDatabase(name) }
    private fun session(album:String?=null)=db.startSession(start,end,album,zone=zone.id)

    @Test fun libraryDoesNotFallBackToAnOldAlbum() {
        db.set("albumId","old");db.set("albumUntil",Long.MAX_VALUE.toString())
        session();db.discover("camera",listOf(photo))
        assertNull(db.savedSession()!!.album);assertNull(db.rows().single().album)
    }

    @Test fun queuedDestinationsRemainImmutableAcrossSessionsAndDatabaseReopen() {
        session("album-one");db.discover("camera",listOf(photo))
        session("album-two");db.discover("camera",listOf(photo))
        db.close();db=QueueStore(context,name)
        assertEquals("album-one",db.rows().single().album)
        assertEquals("album-two",db.savedSession()!!.album)
    }

    @Test fun staleSessionAndDifferentCameraCannotQueuePhotos() {
        val stale=session();val current=session()
        assertThrows(IllegalStateException::class.java) { db.discover("camera",listOf(photo),stale.id) }
        assertEquals(0,db.rows().size)
        db.discover("camera",listOf(photo),current.id)
        assertThrows(IllegalStateException::class.java) { db.discover("different",listOf(photo),current.id) }
        assertEquals(1,db.rows().size)
    }

    @Test fun pastWindowFiltersExactBoundariesAndReportsInvalidTimestamps() {
        session()
        val result=db.discover("camera",listOf(photo,
            photo.copy(path="/DCIM/100OMSYS/START.JPG",stamp="23852:16384"),
            photo.copy(path="/DCIM/100OMSYS/END.JPG",stamp="23852:24576"),
            photo.copy(path="/DCIM/100OMSYS/BAD.JPG",stamp="invalid")))
        assertEquals(4,result.total);assertEquals(2,result.matched);assertEquals(1,result.invalidTimestamps)
        assertEquals(db.rows().map { it.id }.toSet(),result.ids)
    }

    @Test fun legacySessionTimezoneIsPinnedOnceAcrossPhoneTimezoneChanges() {
        val original=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            db.set("session",JSONObject().put("starts",start).put("ends",end).put("account","owner").toString())
            val migrated=db.savedSession()!!
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            db.close();db=QueueStore(context,name)
            assertEquals(migrated,db.savedSession())
            assertEquals(1,db.discover("camera",listOf(photo)).matched)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun legacyBaselineCanBeBackfilledWithoutReroutingRealQueuedPhotos() {
        session("old");db.discover("camera",listOf(photo))
        val row=db.rows().single();db.update(row.id,"state" to "BASELINE")
        session("hike");db.discover("camera",listOf(photo))
        assertEquals("hike",db.rows().single().album)
        db.update(row.id,"state" to "UPLOADED","media_id" to "confirmed")
        session("other");db.discover("camera",listOf(photo))
        assertEquals("hike",db.rows().single().album)
        assertEquals("UPLOADED",db.rows().single().state)
    }

    @Test fun endClearsPendingConnectionAndRecoveryPreservesCreationUncertainty() {
        session();db.discover("camera",listOf(photo))
        val row=db.rows().single();db.update(row.id,"state" to "CREATING")
        db.set("pendingImport","import");db.set("pendingImportReady","true")
        db.endSession();db.recover()
        assertNull(db.savedSession());assertEquals("",db.setting("pendingImport"))
        assertEquals("false",db.setting("pendingImportReady"));assertEquals("true",db.setting("cameraPaused"))
        assertEquals("UNCERTAIN",db.rows().single().state)
    }
}
