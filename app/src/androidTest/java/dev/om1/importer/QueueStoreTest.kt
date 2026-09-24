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
    @Test fun identicalMetadataOnTwoCardsCreatesDistinctQueueEntries() {
        session()
        val first=db.discover("camera",listOf(photo.copy(slot=1)))
        val second=db.discover("camera",listOf(photo.copy(slot=2)))
        assertEquals(2,db.rows().size)
        assertTrue(first.ids.intersect(second.ids).isEmpty())
        assertEquals(setOf(1,2),db.rows().map { it.slot }.toSet())
        db.close();db=QueueStore(context,name)
        assertEquals(setOf(1,2),db.rows().map { it.slot }.toSet())
    }
    @Test fun legacyReceiptIsReusedOnlyForMatchingBytesAndDestination() {
        session("album");db.discover("camera",listOf(photo))
        val legacy=db.rows().single()
        db.update(legacy.id,"state" to "UPLOADED","sha" to "a".repeat(64),"media_id" to "confirmed")
        val id=db.discover("camera",listOf(photo.copy(slot=2))).ids.single()
        db.update(id,"state" to "READY","sha" to "b".repeat(64));db.reuseConfirmedReceipt(id)
        assertEquals("READY",db.rows("id=?",arrayOf(id)).single().state)
        db.update(id,"sha" to "a".repeat(64));db.reuseConfirmedReceipt(id)
        assertEquals("confirmed",db.rows("id=?",arrayOf(id)).single().mediaId)
        session("different-album")
        val other=db.discover("camera",listOf(photo.copy(slot=1))).ids.single()
        db.update(other,"state" to "READY","sha" to "a".repeat(64));db.reuseConfirmedReceipt(other)
        assertEquals("READY",db.rows("id=?",arrayOf(other)).single().state)
    }
    @Test fun unpinnedLegacyDiscoveryIsReplacedByExplicitSlotDiscovery() {
        session();db.discover("camera",listOf(photo))
        db.discover("camera",listOf(photo.copy(slot=1)))
        assertEquals(1,db.rows().single().slot)
        assertEquals("BASELINE",db.rows("slot=0").single().state)
    }
    @Test fun slotUpgradeNeverDiscardsADifferentPendingDestination() {
        session("first");db.discover("camera",listOf(photo))
        session("second");db.discover("camera",listOf(photo.copy(slot=1)))
        val legacy=db.rows("slot=0").single()
        assertEquals("DISCOVERED",legacy.state);assertEquals("first",legacy.album)
    }
    @Test fun versionThreeMigrationKeepsPendingUploadsAndMarksSlotUnknown() {
        session();db.discover("camera",listOf(photo))
        val row=db.rows().single()
        db.update(row.id,"state" to "READY","local" to "retained.jpg","priority" to 42L)
        val sql=db.writableDatabase
        val columns="id,camera,path,size,stamp,discovered,account,album,state,local,sha,upload_url,upload_token,granularity,media_id,error,attempts,retry_at,priority"
        sql.execSQL("CREATE TABLE old_photos AS SELECT $columns FROM photos")
        sql.execSQL("DROP TABLE photos");sql.execSQL("ALTER TABLE old_photos RENAME TO photos")
        sql.execSQL("DROP TABLE capture_times");sql.execSQL("DROP TABLE upload_payloads")
        sql.version=3
        db.close();db=QueueStore(context,name)
        val upgraded=db.rows().single()
        assertEquals("READY",upgraded.state);assertEquals("retained.jpg",upgraded.local)
        assertEquals(42L,upgraded.priority);assertEquals(0,upgraded.slot);assertEquals(row.id,upgraded.id)
    }
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

    @Test fun prioritySurvivesReopenWithoutChangingDestinationOrGlobalCellularPolicy() {
        session("album");db.discover("camera",listOf(photo))
        val id=db.rows().single().id
        db.set("cellular","false")
        db.update(id,"state" to "READY","retry_at" to Long.MAX_VALUE)
        db.prioritize(id)
        db.close();db=QueueStore(context,name)
        val row=db.rows().single()
        assertTrue(row.priority>0);assertEquals(0L,row.retryAt)
        assertEquals("album",row.album);assertEquals("owner",row.account)
        assertEquals("false",db.setting("cellular"))
    }

    @Test fun priorityCannotOverrideAccountOrCompletedState() {
        session();db.discover("camera",listOf(photo))
        val id=db.rows().single().id
        db.update(id,"state" to "READY");db.set("accountId","other");db.prioritize(id)
        assertEquals(0L,db.rows().single().priority)
        db.set("accountId","owner");db.update(id,"state" to "UPLOADED");db.prioritize(id)
        assertEquals(0L,db.rows().single().priority)
    }

    @Test fun networkRecoveryResetsOnlyConnectivityFailures() {
        session();db.discover("camera",listOf(photo))
        val id=db.rows().single().id
        db.update(id,"state" to "READY","retry_at" to 200L,"error" to "Waiting for Wi-Fi. Cellular uploads are disabled.")
        db.retryNetwork();assertEquals(0L,db.rows().single().retryAt)
        db.update(id,"retry_at" to 200L,"error" to "Google rate limit reached. Upload will retry later.")
        db.retryNetwork();assertEquals(200L,db.rows().single().retryAt)
    }

    @Test fun versionTwoMigrationPreservesQueueAndAddsDefaultPriority() {
        session("saved-album");db.discover("camera",listOf(photo))
        val original=db.rows().single()
        val sql=db.writableDatabase
        // Recreate the old schema only inside this test's isolated database.
        val columns="id,camera,path,size,stamp,discovered,account,album,state,local,sha,upload_url,upload_token,granularity,media_id,error,attempts,retry_at"
        sql.execSQL("CREATE TABLE old_photos AS SELECT $columns FROM photos")
        sql.execSQL("DROP TABLE photos")
        sql.execSQL("ALTER TABLE old_photos RENAME TO photos")
        sql.execSQL("DROP TABLE capture_times");sql.execSQL("DROP TABLE upload_payloads")
        sql.version=2
        db.close();db=QueueStore(context,name)
        assertEquals(original,db.rows().single())
        assertEquals(0L,db.rows().single().priority)
        db.update(original.id,"state" to "READY");db.prioritize(original.id)
        assertTrue(db.rows().single().priority>0)
    }
    @Test fun versionFourMigrationAndFrozenCaptureTimesSurviveReopen() {
        session();db.discover("camera",listOf(photo))
        val row=db.rows().single()
        val sql=db.writableDatabase
        sql.execSQL("DROP TABLE capture_times");sql.execSQL("DROP TABLE upload_payloads");sql.version=4
        db.close();db=QueueStore(context,name)
        assertEquals(row,db.rows().single());assertNull(db.captureTime(row.id));assertNull(db.payload(row.id))
        db.discover("camera",listOf(photo));val timing=db.captureTime(row.id)
        assertNotNull(timing)
        db.startSession(start,end,zone="UTC");db.discover("camera",listOf(photo))
        assertEquals(timing,db.captureTime(row.id))
    }
}
