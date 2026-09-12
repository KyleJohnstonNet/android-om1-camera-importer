package dev.om1.importer.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A camera-file timestamp belongs to a session at its start, but not at its end. */
data class SessionWindow(val startsAt: Long, val endsAt: Long) {
    init { require(endsAt > startsAt) }
    fun contains(capturedAt: Long) = capturedAt >= startsAt && capturedAt < endsAt
}

/** OM listings use decimal FAT date/time words, in the camera's local clock. */
object CameraTimestamp {
    private val fatStamp = Regex("([0-9]{1,5}):([0-9]{1,5})")
    private val textStamp = Regex("(?:[0-9]{8}:[0-9]{6}|[0-9]{4}/[0-9]{2}/[0-9]{2}:[0-9]{2}:[0-9]{2}:[0-9]{2})")
    private val textFormat = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    fun parse(stamp: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        return runCatching {
            val packed = fatStamp.matchEntire(stamp)
            val local = if (packed != null) {
                val date = packed.groupValues[1].toInt()
                val time = packed.groupValues[2].toInt()
                require(date in 1..65535 && time in 0..65535)
                LocalDateTime.of(1980 + (date ushr 9), (date ushr 5) and 15, date and 31,
                    time ushr 11, (time ushr 5) and 63, (time and 31) * 2)
            } else {
                require(textStamp.matches(stamp))
                LocalDateTime.parse(stamp.filter(Char::isDigit), textFormat)
            }
            val offsets=zone.rules.getValidOffsets(local)
            require(offsets.size==1) { "Camera time is skipped or ambiguous in this zone." }
            local.toInstant(offsets.single()).toEpochMilli()
        }.getOrNull()
    }
}
