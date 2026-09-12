package dev.om1.camerahelper

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import dev.om1.importer.core.CameraBleProtocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Passive standby observation. Controller power is not proof of a physical switch transition. */
@SuppressLint("MissingPermission")
class PowerOffWatcher(private val context: Context) {
    suspend fun waitForPowerOff(name: String): Boolean {
        val scanner=context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return false
        val results=Channel<Boolean>(Channel.CONFLATED)
        val callback=object:ScanCallback() {
            override fun onScanResult(type:Int,result:ScanResult) {
                val record=result.scanRecord ?: return
                if(record.deviceName!=name) return
                val bytes=record.getManufacturerSpecificData(1232) ?: record.getManufacturerSpecificData(2545) ?: return
                val flags=CameraBleProtocol.advertisementFlags(bytes) ?: return
                if(flags and 1==0) results.trySend(true)
            }
            override fun onScanFailed(errorCode:Int) { results.trySend(false) }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(CameraBleProtocol.SERVICE))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback)
            return withTimeoutOrNull(25_000) { results.receive() } ?: false
        } finally {
            runCatching { scanner.stopScan(callback) }
            results.close()
        }
    }
}
