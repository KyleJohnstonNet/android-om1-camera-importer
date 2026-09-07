package dev.om1.camerahelper

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.om1.importer.core.CameraBleProtocol as Protocol
import dev.om1.importer.core.CameraWifiCredentials
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.util.UUID

/** A single bounded wake attempt. Its caller owns cancellation and runtime permissions. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CameraBluetooth(private val context: Context) {
    private val connected = Channel<Unit>(1)
    private val discovered = Channel<Unit>(1)
    private val descriptors = Channel<UUID>(8)
    private val writes = Channel<UUID>(8)
    private val responses = Channel<ByteArray>(16)
    private var gatt: BluetoothGatt? = null
    @Volatile private var finished = false
    private fun fail(message: String) {
        val error = IllegalStateException(message)
        connected.close(error); discovered.close(error); descriptors.close(error)
        writes.close(error); responses.close(error)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (finished) return
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED)
                fail("Camera Bluetooth disconnected (status $status).")
            else if (state == BluetoothProfile.STATE_CONNECTED) connected.trySend(Unit)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.trySend(Unit)
            else fail("Camera Bluetooth service discovery failed (status $status).")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) descriptors.trySend(d.characteristic.uuid)
            else fail("Camera Bluetooth subscription failed (status $status).")
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) writes.trySend(c.uuid)
            else fail("Camera Bluetooth write failed (status $status).")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notification(c.uuid, value)
        }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notification(c.uuid, c.value ?: byteArrayOf())
        }
    }
    private fun notification(uuid: UUID, bytes: ByteArray) {
        if (finished) return
        when (uuid.toString()) {
            Protocol.COMMAND -> if (bytes.firstOrNull() != 5.toByte()) fail("Camera rejected the Bluetooth command.")
            Protocol.RESPONSE -> if (bytes.size <= 512) {
                if (!responses.trySend(bytes.copyOf()).isSuccess) fail("Too many camera Bluetooth responses.")
            } else fail("Invalid camera Bluetooth response.")
        }
    }
    suspend fun wake(profile: CameraWifiCredentials, report: (String) -> Unit) {
        val name = requireNotNull(profile.bluetoothName) { "Scan the camera QR code to save Bluetooth details." }
        val secret = requireNotNull(profile.bluetoothPassword) { "Scan the camera QR code to save Bluetooth details." }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null && adapter.isEnabled) { "Turn on Bluetooth on the phone first." }
        try {
            withTimeout(110000) {
                report("Looking for the saved camera over Bluetooth…")
                var found = withTimeout(20000) { scan(adapter, name, false, report) }
                if (advertisementFlags(found) and 1 == 0) {
                    report("Saved camera found in Bluetooth standby. Waking its connection controller…")
                    delay(3000)
                    wakeStandby(found.device)
                    report("Waiting for the camera to advertise after standby wake…")
                    found = withTimeout(30000) { scan(adapter, name, true, report) }
                }
                report("Connecting to camera Bluetooth…")
                gatt = found.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                val link = checkNotNull(gatt) { "Unable to open camera Bluetooth." }
                withTimeout(10000) { connected.receive() }
                check(link.discoverServices()) { "Unable to discover camera Bluetooth services." }
                withTimeout(10000) { discovered.receive() }
                val service = checkNotNull(link.getService(UUID.fromString(Protocol.SERVICE))) { "Camera Bluetooth service missing." }
                for (id in listOf(Protocol.COMMAND, Protocol.RESPONSE, Protocol.EVENTS)) {
                    val c = checkNotNull(service.getCharacteristic(UUID.fromString(id))) { "Camera Bluetooth characteristic missing." }
                    check(link.setCharacteristicNotification(c, true)) { "Unable to enable camera Bluetooth notifications." }
                    val d = checkNotNull(c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
                    val started = if (Build.VERSION.SDK_INT >= 33)
                        link.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; link.writeDescriptor(d) }
                    check(started) { "Unable to subscribe to camera Bluetooth." }
                    check(withTimeout(5000) { descriptors.receive() } == c.uuid)
                }
                var sequence = 1
                suspend fun command(code: Int, sub: Int, payload: ByteArray, timeout: Long = 10000): Int = withTimeout(timeout) {
                    val request = Protocol.frame(sequence++, code, sub, payload)
                    try { write(service, Protocol.COMMAND, request) } finally { request.fill(0) }
                    val response = responses.receive()
                    val result = Protocol.result(response, code, sub)
                    write(service, Protocol.RESPONSE, Protocol.acknowledge(response))
                    result
                }
                report("Authenticating with the saved camera…")
                val passcode = secret.toByteArray(Charsets.UTF_8)
                val authenticated = try { command(12, 2, passcode) } finally { passcode.fill(0) }
                check(authenticated == 0) { "Camera Bluetooth authentication failed (code $authenticated). Scan its current QR code again." }
                val flags = advertisementFlags(found)
                if (flags and (8 or 32) == 0) {
                    report("Waking the camera over Bluetooth…")
                    val power = command(15, 1, byteArrayOf(2), 20000)
                    check(power in 0..1) { "Camera wake failed (code $power)." }
                }
                if (flags and 8 != 0) {
                    report("Asking the camera to start Wi-Fi…")
                    val wifi = command(29, 1, byteArrayOf(2))
                    check(wifi == 0) { "Camera Wi-Fi wake failed (code $wifi)." }
                    report("Camera accepted the Wi-Fi wake command. Waiting for Wi-Fi…")
                    delay(5500)
                } else {
                    // In power-off standby, power-on starts Wi-Fi itself (OI.Share e.O / BlePowOnActivity.b).
                    report("Camera standby wake accepted. Waiting for Wi-Fi…")
                    delay(3000)
                }
            }
        } finally {
            finished = true
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
            gatt = null
        }
    }
    private fun advertisementFlags(result: ScanResult): Int {
        val record = result.scanRecord ?: error("Missing camera Bluetooth advertisement.")
        return Protocol.advertisementFlags(record.getManufacturerSpecificData(1232)
            ?: record.getManufacturerSpecificData(2545) ?: byteArrayOf())
            ?: error("Camera Bluetooth advertisement format not recognized.")
    }
    // OI.Share connects to an ASIC-off advertisement without discovering services.
    // The camera then disconnects and advertises its powered connection controller.
    private suspend fun wakeStandby(device: BluetoothDevice) {
        val ended = Channel<Unit>(1)
        val wakeCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                    ended.trySend(Unit)
            }
        }
        val wakeLink = checkNotNull(device.connectGatt(context, false, wakeCallback, BluetoothDevice.TRANSPORT_LE))
        try { kotlinx.coroutines.withTimeoutOrNull(20000) { ended.receive() } }
        finally { runCatching { wakeLink.disconnect() }; runCatching { wakeLink.close() }; ended.close() }
        delay(1000)
    }
    private suspend fun scan(adapter: BluetoothAdapter, name: String, requirePowered: Boolean, report: (String) -> Unit): ScanResult {
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "Bluetooth scanning unavailable." }
        val results = Channel<ScanResult>(1)
        var previousFlags: Int? = null
        val listener = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val manufacturer = record.getManufacturerSpecificData(1232) ?: record.getManufacturerSpecificData(2545) ?: return
                if (record.deviceName != name) return
                val flags = Protocol.advertisementFlags(manufacturer) ?: return
                // Persist only non-identifying state bits for hardware diagnosis.
                if(previousFlags != flags) {
                    previousFlags=flags
                    runCatching { java.io.File(context.filesDir,"last-bluetooth-state.json").writeText(
                        org.json.JSONObject().put("time",java.time.Instant.now().toString()).put("flags",flags)
                            .put("controllerPowered",flags and 1 != 0).toString()) }
                }
                if (!requirePowered || flags and 1 != 0) results.trySend(result)
                else report("Camera found; waiting for its Bluetooth controller to wake…")
            }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) { batch.forEach { onScanResult(0, it) } }
            override fun onScanFailed(code: Int) { results.close(IllegalStateException("Camera Bluetooth scan failed (code $code).")) }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(Protocol.SERVICE))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), listener)
            return results.receive()
        } finally { runCatching { scanner.stopScan(listener) }; results.close() }
    }
    private suspend fun write(service: BluetoothGattService, id: String, value: ByteArray) {
        val link = checkNotNull(gatt)
        val c = checkNotNull(service.getCharacteristic(UUID.fromString(id)))
        val started = if (Build.VERSION.SDK_INT >= 33)
            link.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else {
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            link.writeCharacteristic(c)
        }
        check(started) { "Unable to send camera Bluetooth command." }
        check(withTimeout(5000) { writes.receive() } == c.uuid) { "Unexpected Bluetooth write completion." }
    }
}
