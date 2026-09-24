package dev.om1.importer.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Observations only: reading these values must never initiate camera traffic. */
data class CameraStats(
    val seenAt: Long = 0,
    val rssi: Int? = null,
    val powered: Boolean? = null,
    val bytesPerSecond: Long = 0,
) {
    fun lines(now: Long): List<String> = listOf(
        if(seenAt <= 0) "Bluetooth · no observation since Camera Link started"
        else "Bluetooth last seen · ${timestamp(seenAt)} · ${((now-seenAt).coerceAtLeast(0)/1000)} s ago",
        "Last signal · ${rssi?.let { "$it dBm" } ?: "unavailable"}",
        "Last controller state · ${powered?.let { if(it) "powered" else "standby" } ?: "unknown"} (not the physical switch)",
        "Camera download · ${String.format(Locale.ROOT,"%.2f",bytesPerSecond/1048576.0)} MiB/s · last 5 s",
        "Battery / free card space · unavailable in verified import protocol",
    )
    companion object {
        fun timestamp(time:Long):String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, HH:mm:ss"))
    }
}

/** Aggregate received bytes, not file sizes or per-file rates. Uses a monotonic clock. */
class CameraTransferRate {
    private val samples=ArrayDeque<Pair<Long,Long>>()
    fun received(bytes:Long,now:Long) {
        expire(now)
        if(bytes>0) samples.addLast(now to bytes)
    }
    fun bytesPerSecond(now:Long):Long {
        expire(now)
        return samples.sumOf { it.second }/5
    }
    private fun expire(now:Long) {
        while(samples.isNotEmpty() && samples.first().first<=now-5000) samples.removeFirst()
    }
}

data class CameraCardStats(val slot:Int,val count:Int,val scannedAt:Long) {
    fun text():String = "Slot $slot · $count importable JPEGs · scanned ${CameraStats.timestamp(scannedAt)}"
}
