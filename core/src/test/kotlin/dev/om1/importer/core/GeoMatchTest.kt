package dev.om1.importer.core

import kotlin.test.*

class GeoMatchTest {
    private val time=1_000_000L
    @Test fun matchesOnlyAccurateNearbyHistory() {
        val point=GeoFix(time+30_000,40.0,-100.0,10.0)
        assertEquals(point,GeoMatch.nearest(time,listOf(point,point.copy(time=time,accuracy=101.0))))
        assertNull(GeoMatch.nearest(time,listOf(point.copy(time=time+120_001))))
        assertNull(GeoMatch.nearest(time,listOf(point.copy(latitude=Double.NaN))))
    }
    @Test fun nearestAndTieAreDeterministic() {
        val good=GeoFix(time-1_000,40.0,-100.0,5.0)
        assertEquals(good,GeoMatch.nearest(time,listOf(good.copy(time=time+1_000,accuracy=10.0),good)))
        assertEquals(good,GeoMatch.nearest(time,listOf(good.copy(time=time+120_000),good)))
    }
}
