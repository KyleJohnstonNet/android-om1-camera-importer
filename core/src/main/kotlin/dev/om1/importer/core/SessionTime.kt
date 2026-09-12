package dev.om1.importer.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Persist this zone with the session: later phone-zone changes must not move its photos. */
object SessionTime {
    val format: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String, zone: ZoneId): Long {
        val local = LocalDateTime.parse(text.trim(), format)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "This local time is skipped or repeated by daylight saving. Choose an unambiguous time." }
        return local.toInstant(offsets.single()).toEpochMilli()
    }
}
