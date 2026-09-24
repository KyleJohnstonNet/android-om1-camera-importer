package dev.om1.importer

import dev.om1.importer.core.CameraStats
import kotlin.test.*

class CameraStatsIpcTest {
    @Test fun oldHelperStillProvidesStatusWithoutFabricatedObservations() {
        val result=CameraHelperClient.parseSnapshot("""{"title":"Waiting","detail":"Between scans","until":1234}""")
        assertEquals("Waiting",result.activity.title)
        assertEquals(1234L,result.activity.until)
        assertEquals(CameraStats(),result.stats)
    }
    @Test fun nullableUnknownsAreNotMistakenForStandbyOrZeroRssi() {
        val result=CameraHelperClient.parseSnapshot("""{"title":"Waiting","detail":"","stats":{"rssi":null,"powered":null}}""")
        assertNull(result.stats.rssi);assertNull(result.stats.powered)
    }
    @Test fun observationsSurviveIpc() {
        val result=CameraHelperClient.parseSnapshot("""{"title":"Waiting","detail":"","stats":{"seenAt":12000,"rssi":-65,"powered":false,"bytesPerSecond":1048576}}""")
        assertEquals(CameraStats(12000,-65,false,1048576),result.stats)
    }
}
