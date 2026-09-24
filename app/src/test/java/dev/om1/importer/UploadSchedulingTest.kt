package dev.om1.importer

import kotlin.test.*

class UploadSchedulingTest {
    private fun row(id:String,priority:Long=0,retryAt:Long=0)=PhotoRow(
        id,"camera","/DCIM/$id.JPG",100,"stamp","account",null,"READY",
        null,null,null,null,262144,null,null,0,retryAt,priority)

    @Test fun `three ordinary photos upload concurrently`() {
        assertEquals(listOf("1","2","3"),UploadScheduling.select(
            (1..6).map { row("$it") },emptySet(),emptyMap(),100).map { it.id })
    }
    @Test fun `adaptive limit supports eight plus a priority slot and drains on reduction`() {
        val rows=(1..12).map { row("$it") }
        assertEquals(8,UploadScheduling.select(rows,emptySet(),emptyMap(),100,limit=8).size)
        val active=(1..8).map { "$it" }.toSet()
        assertEquals(listOf("urgent"),UploadScheduling.select(rows+row("urgent",10),active,emptyMap(),100,limit=8).map { it.id })
        assertTrue(UploadScheduling.select(rows,active,emptyMap(),100,limit=2).isEmpty())
    }
    @Test fun `old receipts never delay pending uploads`() {
        val rows=(1..10).map { row("old$it").copy(state="UPLOADED") }+row("pending")
        assertEquals("pending",UploadScheduling.select(rows,emptySet(),emptyMap(),100).first().id)
    }
    @Test fun `priority photo gets an immediate reserved slot ahead of normal work`() {
        assertEquals(listOf("urgent"),UploadScheduling.select(
            listOf(row("normal"),row("urgent",10)),setOf("1","2","3"),emptyMap(),100).map { it.id })
        assertTrue(UploadScheduling.select(listOf(row("urgent",10)),setOf("1","2","3","4"),emptyMap(),100).isEmpty())
    }
    @Test fun `latest explicit request wins and active rows are never duplicated`() {
        assertEquals(listOf("new","old"),UploadScheduling.select(
            listOf(row("normal"),row("old",10),row("new",20),row("active",30)),
            setOf("active"),emptyMap(),100).map { it.id })
    }
    @Test fun `retry deadline and network reset allow work without repeating completed attempts`() {
        val attempts=mapOf("1" to (0L to 0L))
        assertTrue(UploadScheduling.select(listOf(row("1")),emptySet(),attempts,100).isEmpty())
        assertTrue(UploadScheduling.select(listOf(row("1",retryAt=200)),emptySet(),attempts,100).isEmpty())
        assertEquals(1,UploadScheduling.select(listOf(row("1",retryAt=200)),emptySet(),attempts,200).size)
        assertEquals(1,UploadScheduling.select(listOf(row("1")),emptySet(),mapOf("1" to (0L to 200L)),100).size)
        assertEquals(1,UploadScheduling.select(listOf(row("1",priority=50)),emptySet(),attempts,100).size)
    }
}
