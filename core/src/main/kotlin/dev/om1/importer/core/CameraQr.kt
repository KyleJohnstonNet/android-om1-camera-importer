package dev.om1.importer.core

/** Credentials deliberately have no generated toString or persistence behavior. */
class CameraWifiCredentials(val ssid: String, val password: String, val wpa3: Boolean,
    val bluetoothName: String? = null, val bluetoothPassword: String? = null)

/** OIS field layout and substitution alphabet observed in OI.Share 1.4.3. */
object CameraQr {
    fun parse(text: String): CameraWifiCredentials {
        require(text.length in 1..4096) { "Invalid camera QR code." }
        val fields = text.split(',')
        val version = fields.first()
        val offset: Int
        val security: Int
        when (version) {
            "OIS1" -> {
                require(fields.size == 3) { "Invalid camera QR code." }
                offset = 1; security = 1
            }
            "OIS2", "OIS3" -> {
                require(fields.size >= 4) { "Invalid camera QR code." }
                val mode = fields[1].toIntOrNull()
                require(mode == 1 || mode == 3) { "Unsupported camera QR version." }
                offset = if (version == "OIS3") 3 else 2
                require(fields.size == offset + 2 + if (mode == 3) 2 else 0) { "Invalid camera QR code." }
                security = if (version == "OIS3") fields[2].toIntOrNull() ?: 0 else 1
            }
            else -> error("This is not a supported OM camera Wi-Fi QR code.")
        }
        require(security == 1 || security == 2) { "Unsupported camera Wi-Fi security." }
        val ssid = decode(fields[offset])
        val password = decode(fields[offset + 1])
        require(ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32) { "Invalid camera Wi-Fi name." }
        require(password.length in 8..63) { "Invalid camera Wi-Fi password." }
        val bluetoothName = fields.getOrNull(offset + 2)?.let(::decode)
        val bluetoothPassword = fields.getOrNull(offset + 3)?.let(::decode)
        if (bluetoothName != null) {
            require(bluetoothName.isNotBlank() && bluetoothName.toByteArray().size <= 248) { "Invalid camera Bluetooth name." }
            require(bluetoothPassword != null && bluetoothPassword.toByteArray().size in 1..12) { "Invalid camera Bluetooth passcode." }
        }
        return CameraWifiCredentials(ssid, password, security == 2, bluetoothName, bluetoothPassword)
    }

    private fun decode(value: String): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\$%*+-/"
        return value.map { c ->
            when {
                c in alphabet -> alphabet[alphabet.lastIndex - alphabet.indexOf(c)]
                c in 'a'..'z' -> ('z'.code - (c.code - 'a'.code)).toChar()
                c == ' ' -> c
                else -> error("Unsupported character in camera QR code.")
            }
        }.joinToString("")
    }
}
