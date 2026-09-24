package dev.om1.importer.core

object RecentPhotos {
    const val COUNT=20
    fun retainedHashes(hashesInDisplayOrder:List<String?>):Set<String> =
        hashesInDisplayOrder.takeLast(COUNT).filterNotNull().filter { it.matches(Regex("[0-9a-f]{64}")) }.toSet()
}
