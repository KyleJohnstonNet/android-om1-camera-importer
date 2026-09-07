package dev.om1.importer.core

import java.time.Instant

/** Account and album are fixed at discovery, never recomputed by an upload retry. */
data class Destination(val accountId: String, val albumId: String? = null) {
    init {
        require(accountId.isNotBlank())
        require(albumId == null || albumId.isNotBlank())
    }
}

data class AlbumWindow(val albumId: String, val startsAt: Instant, val endsAt: Instant) {
    init { require(albumId.isNotBlank()); require(endsAt > startsAt) }
    fun contains(discoveredAt: Instant) = discoveredAt >= startsAt && discoveredAt < endsAt
}

class DestinationResolver {
    fun resolve(accountId: String, discoveredAt: Instant, window: AlbumWindow?): Destination =
        Destination(accountId, window?.takeIf { it.contains(discoveredAt) }?.albumId)
}

enum class UploadNetwork { WIFI, CELLULAR, OTHER, OFFLINE }

/** Unknown/VPN underlay must be resolved by the Android adapter before upload. */
fun mayUpload(network: UploadNetwork, cellularEnabled: Boolean): Boolean = when (network) {
    UploadNetwork.WIFI -> true
    UploadNetwork.CELLULAR -> cellularEnabled
    UploadNetwork.OTHER, UploadNetwork.OFFLINE -> false
}

/** A bytes-upload token alone is NOT evidence of a photo in Google Photos. */
data class UploadReceipt(val mediaItemId: String, val destination: Destination) {
    init { require(mediaItemId.isNotBlank()) }
}

fun mayDeleteLocalOriginal(expected: Destination, receipt: UploadReceipt?): Boolean =
    receipt != null && receipt.destination == expected
