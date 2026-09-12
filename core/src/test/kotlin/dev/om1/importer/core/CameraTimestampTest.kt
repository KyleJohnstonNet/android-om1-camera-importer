package dev.om1.importer.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class CameraTimestampTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun instant(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()

    @Test fun `decodes packed FAT date time including two second resolution`() {
        assertEquals(instant("2026-09-12T10:30:14"), CameraTimestamp.parse("23852:21447", zone))
        assertEquals(instant("1980-01-01T00:00:00"), CameraTimestamp.parse("33:0", zone))
        assertEquals(instant("2107-12-31T23:59:58"), CameraTimestamp.parse("65439:49021", zone))
    }

    @Test fun `real listing format selects only photos in a past hike window`() {
        val files = CameraFiles.parse("""
            WLANSD_FILELIST
            /DCIM/100OMSYS,BEFORE.JPG,1024,32,23852,16253
            /DCIM/100OMSYS,START.JPG,1024,32,23852,16384
            /DCIM/100OMSYS,INSIDE.JPG,1024,32,23852,21447
            /DCIM/100OMSYS,END.JPG,1024,32,23852,24576
        """.trimIndent(), "/DCIM/100OMSYS")
        val window = SessionWindow(instant("2026-09-12T08:00:00"), instant("2026-09-12T12:00:00"))
        assertEquals(listOf("START.JPG", "INSIDE.JPG"), files.filter {
            CameraTimestamp.parse(it.stamp, zone)?.let(window::contains) == true
        }.map { it.path.substringAfterLast('/') })
    }

    @Test fun `invalid timestamp never silently becomes a valid date`() {
        for (stamp in listOf("0:0", "65536:0", "23852:65535", "23852:31", "23852:1920",
            "23872:0", "23645:0", "2026/02/30:10:00:00", "junk20260912103014", "")) {
            assertNull(CameraTimestamp.parse(stamp, zone), stamp)
        }
    }

    @Test fun `camera local time uses the supplied zone`() {
        assertEquals(7 * 60 * 60 * 1000L,
            CameraTimestamp.parse("23852:21447", zone)!! - CameraTimestamp.parse("23852:21447", ZoneId.of("UTC"))!!)
    }

    @Test fun `camera clock daylight saving ambiguity is reported instead of silently shifted`() {
        assertNull(CameraTimestamp.parse("2026/03/08:02:30:00",zone))
        assertNull(CameraTimestamp.parse("2026/11/01:01:30:00",zone))
    }
}
