package dev.om1.importer.core

import kotlin.test.*

class RecentPhotosTest {
    @Test fun retainsOnlyDisplayedRowsIncludingTheirSharedThumbnails() {
        val hashes=(1..25).map { it.toString(16).padStart(64,'0') }
        assertEquals(hashes.takeLast(20).toSet(),RecentPhotos.retainedHashes(hashes))
        assertEquals(setOf(hashes.last()),RecentPhotos.retainedHashes(List(25) { hashes.last() }))
    }
    @Test fun unimportedRowsStillConsumeRecentListPositions() {
        val old="a".repeat(64)
        assertTrue(RecentPhotos.retainedHashes(listOf(old)+List(20) { null }).isEmpty())
        assertTrue(RecentPhotos.retainedHashes(listOf("../originals/photo")).isEmpty())
    }
}
