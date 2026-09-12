package dev.om1.importer.core

import java.time.ZoneId
import kotlin.test.*

class SessionRecoveryTest {
    private val zone=ZoneId.of("America/Los_Angeles")

    @Test fun `session input is strict and rejects impossible calendar dates`() {
        for(text in listOf("2026-02-30 08:00","2026-09-12 24:00","2026-13-12 08:00","2026-09-12 08:00 junk")) {
            assertFails { SessionTime.parse(text,zone) }
        }
        assertEquals(SessionTime.parse("2026-09-12 08:00",zone),SessionTime.parse(" 2026-09-12 08:00 ",zone))
    }

    @Test fun `session input rejects skipped and repeated daylight saving times`() {
        assertFails { SessionTime.parse("2026-03-08 02:30",zone) }
        assertFails { SessionTime.parse("2026-11-01 01:30",zone) }
        assertEquals(7*3600_000L,SessionTime.parse("2026-09-12 08:00",zone)-SessionTime.parse("2026-09-12 08:00",ZoneId.of("UTC")))
    }

    @Test fun `monitor waits before start but stays eligible after end until final collection`() {
        val window=MonitorWindow(100,200)
        assertFalse(window.mayCollect(99))
        assertTrue(window.mayCollect(100))
        assertTrue(window.mayCollect(500))
        assertFalse(window.acknowledge(199,250).isComplete())
        assertTrue(window.acknowledge(200,250).isComplete())
        assertFalse(window.acknowledge(200,250).mayCollect(500))
    }

    @Test fun `invalid acknowledgements cannot finish a monitor and restored progress never regresses`() {
        val window=MonitorWindow(100,200,150)
        assertEquals(window,window.acknowledge(0,300))
        assertEquals(window,window.acknowledge(301,300))
        assertEquals(window,window.acknowledge(140,300))
        assertTrue(MonitorWindow(100,200,200).isComplete())
        assertFails { MonitorWindow(200,100) }
    }
}
