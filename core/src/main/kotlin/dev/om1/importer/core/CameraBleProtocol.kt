package dev.om1.importer.core

/** Independently implemented wire facts observed in OI.Share 1.4.3. No credential logging. */
object CameraBleProtocol {
    const val SERVICE = "adc505f9-4e58-4b71-b8ca-983bb8c73e4f"
    const val COMMAND = "82f949b4-f5dc-4cf3-ab3c-fd9fd4017b68"
    const val RESPONSE = "b7a8015c-cb94-4efa-bda2-b7921fa9951f"
    const val EVENTS = "05a02050-0860-4919-8add-9801fba8b6ed"
    /** Android manufacturer data excludes the two-byte company ID. Bit 0 is power, not identity. */
    fun advertisementFlags(data: ByteArray): Int? =
        if(data.size == 6 && data[0] == 1.toByte() && data[1] == 0.toByte()) data[3].toInt() and 255 else null
    fun frame(sequence: Int, command: Int, subcommand: Int, payload: ByteArray): ByteArray {
        require(sequence in 0..255 && command in 0..255 && subcommand in 0..255 && payload.size <= 12)
        return byteArrayOf(1, sequence.toByte(), (payload.size + 3).toByte(), command.toByte(), 1, subcommand.toByte()) +
            payload + byteArrayOf((command + 1 + subcommand + payload.sumOf { it.toInt() and 255 }).toByte(), 0)
    }
    fun result(response: ByteArray, command: Int, subcommand: Int): Int {
        require(response.size in 7..512 && response[0] == 4.toByte() &&
            (response[3].toInt() and 255) == command && (response[5].toInt() and 255) == subcommand) {
            "Unexpected camera Bluetooth response."
        }
        return response[6].toInt() and 255
    }
    fun acknowledge(response: ByteArray): ByteArray {
        require(response.size >= 7 && response[0] == 4.toByte())
        return byteArrayOf(2, response[1], 0, 0, 0)
    }
}
