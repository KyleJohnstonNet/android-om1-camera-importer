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
    companion object {
        const val SCAN_WINDOW_MS=15_000L
        const val REST_SECONDS=45
    }
    data class Outcome(val standby:Boolean,val detail:String)
    suspend fun waitForPowerOff(name: String,eligible:(Boolean)->Boolean = { !it },observed:(String)->Unit = {}): Outcome {
        val scanner=context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
            ?: return Outcome(false,"Bluetooth is off or unavailable. Enable Bluetooth to watch for the camera.")
        val results=Channel<Outcome>(Channel.CONFLATED)
        // Android may already have queued callbacks when stopScan is called.
        val accepting=java.util.concurrent.atomic.AtomicBoolean(true)
        var lastSeen="No standby signal detected. The camera may be on, out of range, or not advertising."
        val callback=object:ScanCallback() {
            override fun onScanResult(type:Int,result:ScanResult) {
                if(!accepting.get()) return
                val record=result.scanRecord ?: return
                if(record.deviceName!=name) return
                val bytes=record.getManufacturerSpecificData(1232) ?: record.getManufacturerSpecificData(2545) ?: return
                val flags=CameraBleProtocol.advertisementFlags(bytes) ?: return
                val powered=flags and 1!=0
                if(eligible(powered)) results.trySend(Outcome(true,"New camera standby cycle detected."))
                else {
                    val detail=if(powered) "Camera Bluetooth seen; waiting for standby."
                        else "Camera remains in standby; already collected. Waiting for a new powered/standby cycle. Sync now can check manually."
                    if(lastSeen!=detail) { lastSeen=detail;observed(detail) }
                }
            }
            override fun onScanFailed(errorCode:Int) {
                if(accepting.get()) results.trySend(Outcome(false,"Bluetooth scan failed (code $errorCode). Will retry automatically."))
            }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(CameraBleProtocol.SERVICE))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback)
            return withTimeoutOrNull(SCAN_WINDOW_MS) { results.receive() } ?: Outcome(false,lastSeen)
        } finally {
            accepting.set(false)
            runCatching { scanner.stopScan(callback) }
            results.close()
        }
    }
}
