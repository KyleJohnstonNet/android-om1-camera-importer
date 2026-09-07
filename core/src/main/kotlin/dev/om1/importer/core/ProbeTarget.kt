package dev.om1.importer.core

/** Numeric RFC1918 addresses only: no DNS, loopback, multicast or public probes. */
class ProbeTarget private constructor(val address: String, val port: Int) {
    companion object {
        fun parse(address: String, port: String): ProbeTarget {
            val parts = address.trim().split('.')
            require(parts.size == 4) { "Enter the camera's private IPv4 address." }
            val octets = parts.map {
                require(it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' }) {
                    "Use a numeric IPv4 address, not a hostname."
                }
                it.toInt().also { n -> require(n in 0..255) { "Invalid IPv4 address." } }
            }
            require(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)) { "Use a private camera address." }
            val parsedPort = port.toIntOrNull()
            require(parsedPort != null && parsedPort in 1..65535) { "Port must be 1–65535." }
            return ProbeTarget(octets.joinToString("."), parsedPort)
        }
    }
}
