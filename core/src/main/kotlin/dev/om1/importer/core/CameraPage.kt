package dev.om1.importer.core

data class CameraPage(val entries: List<CameraFile>, val offset: Int) {
    companion object {
        const val SIZE = 50
        fun of(entries: List<CameraFile>, requested: Int): CameraPage {
            require(requested in 0..10000 && entries.size <= 10000) { "Invalid camera listing page." }
            val last = if(entries.isEmpty()) 0 else (entries.lastIndex / SIZE) * SIZE
            val start = minOf((requested / SIZE) * SIZE, last)
            return CameraPage(entries.drop(start).take(SIZE),start)
        }
    }
}
