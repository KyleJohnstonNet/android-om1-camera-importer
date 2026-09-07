package dev.om1.importer.core

import kotlin.test.*

class CameraBleProtocolTest {
    @Test fun `wifi wake has observed command and checksum`() {
        assertContentEquals(byteArrayOf(1,1,4,0x1d,1,1,2,0x21,0), CameraBleProtocol.frame(1,29,1,byteArrayOf(2)))
        assertContentEquals(byteArrayOf(1,2,4,0x0f,1,1,2,0x13,0), CameraBleProtocol.frame(2,15,1,byteArrayOf(2)))
    }
    @Test fun `passcode is UTF8 payload with wrapping checksum`() {
        val result = CameraBleProtocol.frame(255,12,2,"123456".toByteArray())
        assertEquals(9,result[2].toInt())
        assertContentEquals("123456".toByteArray(),result.copyOfRange(6,12))
        assertEquals(68,result[12].toInt())
        assertFails { CameraBleProtocol.frame(1,12,2,ByteArray(13)) }
    }
    @Test fun `response command and subcommand must match before acknowledgement`() {
        val response = byteArrayOf(4,42,4,29,1,1,0,31,0)
        assertEquals(0,CameraBleProtocol.result(response,29,1))
        assertContentEquals(byteArrayOf(2,42,0,0,0),CameraBleProtocol.acknowledge(response))
        assertFails { CameraBleProtocol.result(response,12,2) }
        assertFails { CameraBleProtocol.result(response,29,2) }
        for(size in 0..6) assertFails { CameraBleProtocol.result(response.copyOf(size),29,1) }
        assertFails { CameraBleProtocol.result(response.copyOf().apply { this[0]=5 },29,1) }
        assertEquals(2,CameraBleProtocol.result(response.copyOf().apply { this[6]=2 },29,1))
    }
}
