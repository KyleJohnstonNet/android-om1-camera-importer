package dev.om1.importer.core

/** The photo cutoff is independent from the last collection attempt. */
data class MonitorWindow(val starts: Long, val ends: Long, val lastSuccessfulScan: Long = 0) {
    init { require(ends > starts) }
    fun isComplete() = lastSuccessfulScan >= ends
    fun mayCollect(now: Long) = now >= starts && !isComplete()
    fun acknowledge(scanStartedAt: Long, receivedAt: Long): MonitorWindow {
        // A scan begun before the cutoff cannot prove that all last-minute photos were listed.
        if(scanStartedAt < starts || scanStartedAt > receivedAt) return this
        return copy(lastSuccessfulScan=maxOf(lastSuccessfulScan,scanStartedAt))
    }
}
