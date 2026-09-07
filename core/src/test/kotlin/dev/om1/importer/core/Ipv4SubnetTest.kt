package dev.om1.importer.core

import kotlin.test.*

class Ipv4SubnetTest {
    private fun ip(vararg parts: Int) = parts.map { it.toByte() }.toByteArray()

    @Test fun `camera subnet matches secondary WiFi and excludes regular WiFi`() {
        val camera = ip(192, 168, 0, 10)
        assertTrue(Ipv4Subnet.contains(ip(192, 168, 0, 7), camera, 24))
        assertFalse(Ipv4Subnet.contains(ip(192, 168, 10, 252), camera, 24))
        assertFalse(Ipv4Subnet.contains(ip(10, 1, 1, 1), camera, 0))
        assertFalse(Ipv4Subnet.contains(ByteArray(16), camera, 24))
        assertTrue(Ipv4Subnet.contains(camera, camera, 32))
        assertFalse(Ipv4Subnet.contains(ip(192, 168, 0, 11), camera, 32))
        assertTrue(Ipv4Subnet.contains(ip(192, 168, 0, 11), camera, 31))
        assertFalse(Ipv4Subnet.contains(ip(192, 168, 0, 12), camera, 31))
    }
}
