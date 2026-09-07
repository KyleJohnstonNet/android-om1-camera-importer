package dev.om1.importer.core
import kotlin.test.*
class JpegStructureTest {
    private fun data(vararg values: Int)=values.map { it.toByte() }.toByteArray()
    // Marker-structure fixtures; Android separately validates actual image dimensions.
    private val prefix=data(255,216,255,192,0,2,255,218,0,2)
    @Test fun `accepts EOI before trailing camera data without trimming it`() {
        val bytes=prefix+data(4,255,0,217,255,208,7,255,217,0,0,1,2)
        JpegStructure.verify(bytes.inputStream())
        assertEquals(23,bytes.size)
    }
    @Test fun `rejects truncation and stuffed pseudo EOI`() {
        assertFails { JpegStructure.verify((prefix+data(4,255,0,217)).inputStream()) }
        assertFails { JpegStructure.verify(data(255,216,255,225,0,10,1,255,217).inputStream()) }
        assertFails { JpegStructure.verify(data(255,216,255,217).inputStream()) }
    }
}
