package dev.om1.importer.core
import kotlin.test.*
class CameraEndpointsTest {
    @Test fun `only fixed camera commands can become URLs`() {
        assertEquals("http://192.168.0.10/get_commandlist.cgi",CameraEndpoints.url("get_commandlist.cgi"))
        for(path in listOf("https://photos.googleapis.com", "//example.com", "../get_caminfo.cgi", "get_commandlist.cgi?redirect=x", "switch_cammode.cgi?mode=play")) {
            assertFailsWith<IllegalArgumentException> { CameraEndpoints.url(path) }
        }
    }
}
