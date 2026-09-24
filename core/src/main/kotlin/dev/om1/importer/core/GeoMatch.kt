package dev.om1.importer.core

import kotlin.math.abs

data class GeoFix(val time:Long,val latitude:Double,val longitude:Double,val accuracy:Double) {
    fun usable()=time>0 && latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() &&
        longitude in -180.0..180.0 && accuracy.isFinite() && accuracy in 0.0..100.0
}

object GeoMatch {
    const val MAX_AGE=120_000L
    fun nearest(capturedAt:Long,fixes:List<GeoFix>):GeoFix? = fixes.filter {
        it.usable() && it.time in (capturedAt-MAX_AGE)..(capturedAt+MAX_AGE)
    }.minWithOrNull(compareBy<GeoFix> { abs(it.time-capturedAt) }.thenBy { it.accuracy }.thenBy { it.time })
}
