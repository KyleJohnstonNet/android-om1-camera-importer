package dev.om1.importer.core

import kotlin.test.*

class CameraStatsTest {
    @Test fun unknownIsNotZeroBatteryOrPoweredOff() {
        val text=CameraStats().lines(10000).joinToString("\n")
        assertTrue(text.contains("no observation"))
        assertTrue(text.contains("state · unknown"))
        assertTrue(text.contains("Battery / free card space · unavailable"))
    }
    @Test fun oldObservationsStayExplicitlyHistorical() {
        val text=CameraStats(1000,-65,false).lines(121000).joinToString("\n")
        assertTrue(text.contains("120 s ago"))
        assertTrue(text.contains("-65 dBm"))
        assertTrue(text.contains("standby"))
        assertTrue(text.contains("not the physical switch"))
    }
    @Test fun clockCorrectionDoesNotProduceNegativeAge() {
        assertTrue(CameraStats(10000).lines(1000).first().endsWith("0 s ago"))
    }
    @Test fun parallelBytesAreSummedAndIdleRateExpires() {
        val rate=CameraTransferRate()
        rate.received(5000,1000)
        rate.received(10000,1000)
        assertEquals(3000L,rate.bytesPerSecond(1000))
        rate.received(5000,4000)
        assertEquals(4000L,rate.bytesPerSecond(5999))
        assertEquals(1000L,rate.bytesPerSecond(6000))
        assertEquals(0L,rate.bytesPerSecond(9000))
    }
    @Test fun invalidDeltasCannotReduceRate() {
        val rate=CameraTransferRate()
        rate.received(5000,1000);rate.received(-2000,1001)
        assertEquals(1000L,rate.bytesPerSecond(1001))
    }
    @Test fun emptyCardIsDifferentFromUnknown() {
        assertTrue(CameraCardStats(2,0,1000).text().contains("Slot 2 · 0 importable JPEGs"))
    }
}
