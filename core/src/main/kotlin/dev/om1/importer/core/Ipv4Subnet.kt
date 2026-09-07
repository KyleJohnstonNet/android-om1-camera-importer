package dev.om1.importer.core

object Ipv4Subnet {
    /** Reject a default route: the camera must be on a directly connected subnet. */
    fun contains(local: ByteArray, target: ByteArray, prefixLength: Int): Boolean {
        if (local.size != 4 || target.size != 4 || prefixLength !in 1..32) return false
        return (0 until prefixLength).all { bit ->
            val mask = 1 shl (7 - bit % 8)
            (local[bit / 8].toInt() and mask) == (target[bit / 8].toInt() and mask)
        }
    }
}
