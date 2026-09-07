package dev.om1.importer.core
import kotlin.test.*

class CameraPageTest {
    private val files=(1..334).map { CameraFile("/DCIM/100OMSYS/P$it.JPG",100,false) }
    @Test fun `all pages include every entry once including newly appended files`() {
        assertEquals(files,(0 until files.size step CameraPage.SIZE).flatMap { CameraPage.of(files,it).entries })
        assertEquals(files.last(),CameraPage.of(files,300).entries.last())
    }
    @Test fun `page request recovers when camera directory shrinks`() {
        assertEquals(50,CameraPage.of(files.take(70),300).offset)
        assertTrue(CameraPage.of(emptyList(),300).entries.isEmpty())
        assertFails { CameraPage.of(files,-1) }
        assertFails { CameraPage.of(files,10001) }
    }
    @Test fun `standby advertisements retain zero power flag`() {
        assertEquals(0,CameraBleProtocol.advertisementFlags(byteArrayOf(1,0,0,0,0,0)))
        assertEquals(2,CameraBleProtocol.advertisementFlags(byteArrayOf(1,0,0,2,0,0)))
        assertEquals(9,CameraBleProtocol.advertisementFlags(byteArrayOf(1,0,0,9,0,0)))
        assertNull(CameraBleProtocol.advertisementFlags(byteArrayOf(1,0)))
        assertNull(CameraBleProtocol.advertisementFlags(byteArrayOf(2,0,0,0,0,0)))
    }
}
