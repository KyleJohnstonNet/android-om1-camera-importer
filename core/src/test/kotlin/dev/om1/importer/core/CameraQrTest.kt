package dev.om1.importer.core

import kotlin.test.*

class CameraQrTest {
    @Test fun `legacy code decodes SSID and password`() {
        val result = CameraQr.parse("OIS1,HJ1-1G1CRDC,-+*%\$ZYX")
        assertEquals("OM-1-P-TEST", result.ssid)
        assertEquals("12345678", result.password)
        assertFalse(result.wpa3)
    }
    @Test fun `combined Bluetooth code retains separate credentials without exposing them`() {
        val result = CameraQr.parse("OIS3,3,2,HJ1-1G1CRDC,-+*%\$ZYX,HJ1-1G1CRDC,-+*%\$ZYX")
        assertTrue(result.wpa3)
        assertEquals("OM-1-P-TEST",result.bluetoothName)
        assertEquals("12345678",result.bluetoothPassword)
        assertEquals("12345678", result.password)
        assertFalse(result.toString().contains(result.password))
    }
    @Test fun `version two and WiFi only version three work`() {
        assertFalse(CameraQr.parse("OIS2,1,HJ1-1G1CRDC,-+*%\$ZYX").wpa3)
        assertTrue(CameraQr.parse("OIS3,1,2,HJ1-1G1CRDC,-+*%\$ZYX").wpa3)
    }
    @Test fun `malformed unsupported and unrelated codes are rejected without echo`() {
        for (text in listOf("https://example.com", "OIS3", "OIS3,3,2,a,b", "OIS3,1,9,HJ1-1G1CRDC,-+*%\$ZYX", "OIS1,?,secret-password", "OIS1,HJ,short")) {
            val error = assertFails { CameraQr.parse(text) }
            assertFalse(error.message.orEmpty().contains("secret-password"))
        }
    }
}
