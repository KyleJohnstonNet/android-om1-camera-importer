package dev.om1.importer.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class PolicyTest {
    private val start = Instant.parse("2026-09-06T10:00:00Z")
    private val end = start.plusSeconds(3600)
    private val window = AlbumWindow("album", start, end)
    private val resolver = DestinationResolver()

    @Test fun `album interval includes start and excludes end`() {
        assertNull(resolver.resolve("account", start.minusNanos(1), window).albumId)
        assertEquals("album", resolver.resolve("account", start, window).albumId)
        assertEquals("album", resolver.resolve("account", end.minusNanos(1), window).albumId)
        assertNull(resolver.resolve("account", end, window).albumId)
    }

    @Test fun `pending upload keeps discovery destination after timer change`() {
        val queuedDestination = resolver.resolve("account", start, window)
        val laterDestination = resolver.resolve("account", end, null)
        assertEquals("album", queuedDestination.albumId)
        assertNull(laterDestination.albumId)
        assertFalse(mayDeleteLocalOriginal(queuedDestination, UploadReceipt("id", laterDestination)))
    }

    @Test fun `cleanup requires matching confirmed media receipt`() {
        val destination = Destination("account", "album")
        assertFalse(mayDeleteLocalOriginal(destination, null))
        assertFalse(mayDeleteLocalOriginal(destination, UploadReceipt("id", Destination("other", "album"))))
        assertFalse(mayDeleteLocalOriginal(destination, UploadReceipt("id", Destination("account"))))
        assertTrue(mayDeleteLocalOriginal(destination, UploadReceipt("id", destination)))
        assertFailsWith<IllegalArgumentException> { UploadReceipt("", destination) }
    }

    @Test fun `cellular toggle never admits unresolved or offline networks`() {
        for (enabled in listOf(true, false)) {
            assertTrue(mayUpload(UploadNetwork.WIFI, enabled))
            assertFalse(mayUpload(UploadNetwork.OTHER, enabled))
            assertFalse(mayUpload(UploadNetwork.OFFLINE, enabled))
            assertEquals(enabled, mayUpload(UploadNetwork.CELLULAR, enabled))
        }
    }

    @Test fun `timer requires positive duration`() {
        assertFailsWith<IllegalArgumentException> { AlbumWindow("album", start, start) }
        assertFailsWith<IllegalArgumentException> { AlbumWindow("album", end, start) }
    }

    @Test fun `session window uses camera capture time and is end exclusive`() {
        val window = SessionWindow(100, 200)
        assertTrue(window.contains(100))
        assertTrue(window.contains(199))
        assertFalse(window.contains(200))
    }

    @Test fun `camera listing date and time parse without punctuation`() {
        assertEquals(1777897845000, CameraTimestamp.parse("2026/05/04:12:30:45", ZoneId.of("UTC")))
        assertNull(CameraTimestamp.parse("unknown", ZoneId.of("UTC")))
    }

    @Test fun `probe validates private numeric targets and port`() {
        assertEquals("192.168.0.10", ProbeTarget.parse("192.168.0.10", "15740").address)
        assertEquals(1, ProbeTarget.parse("10.0.0.2", "1").port)
        assertEquals(65535, ProbeTarget.parse("172.31.1.2", "65535").port)
        for (address in listOf("google.com", "127.0.0.1", "8.8.8.8", "224.0.0.1", "172.32.1.2",
            "192.168.0.256", "192.168.0.-1", "192.168.0", "192.168.0.1/24")) {
            assertFailsWith<IllegalArgumentException>(address) { ProbeTarget.parse(address, "80") }
        }
        for (port in listOf("0", "65536", "-1", "abc", "")) {
            assertFailsWith<IllegalArgumentException> { ProbeTarget.parse("10.0.0.2", port) }
        }
    }
}
