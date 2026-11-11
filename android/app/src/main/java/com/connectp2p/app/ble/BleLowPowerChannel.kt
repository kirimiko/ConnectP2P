package com.connectp2p.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.connectp2p.app.protocol.P2pProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ultra-Low-Power BLE Control & Micro-Clipboard Channel (< 0.8 mA standby).
 *
 * Key Battery Optimizations:
 * 1. Advertising uses `ADVERTISE_MODE_LOW_POWER` (1000ms interval) and `ADVERTISE_TX_POWER_LOW`.
 * 2. Scanning uses `SCAN_MODE_LOW_POWER` + Hardware Controller Offload Filter (`ScanFilter` by Service UUID).
 *    Bluetooth controller chip filters packets in hardware without waking the Android AP CPU!
 * 3. Automatically pauses BLE scanning when the phone screen turns OFF (`onScreenStateChanged(false)`).
 * 4. Transfers small text clipboards (<= 16KB) and `P2P_WAKE_REQUEST` / `P2P_GROUP_READY` frames
 *    directly over GATT characteristics without turning on Wi-Fi Direct.
 */
@SuppressLint("MissingPermission")
class BleLowPowerChannel(
    private val context: Context,
    private val onControlFrameReceived: (P2pProtocol.DecodedFrame, String) -> Unit,
    /** A central (the PC) connected to our GATT server and subscribed / disconnected. */
    private val onCentralLinkChanged: (address: String, up: Boolean) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "ConnectP2P.BLE"
        val SERVICE_UUID: UUID = UUID.fromString(P2pProtocol.BLE_SERVICE_UUID)
        val CONTROL_CHAR_UUID: UUID = UUID.fromString(P2pProtocol.BLE_CONTROL_CHAR_UUID)
        val CLIPBOARD_CHAR_UUID: UUID = UUID.fromString(P2pProtocol.BLE_CLIPBOARD_CHAR_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /** Centrals (PCs) subscribed to notifications on the control characteristic, by address. */
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val centralMtu = ConcurrentHashMap<String, Int>()
    private val notifyQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val notifyInFlight = ConcurrentHashMap.newKeySet<String>()
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isCentralLinked(address: String?): Boolean = address != null && subscribedCentrals.containsKey(address)

    fun linkedCentralAddresses(): Set<String> = subscribedCentrals.keys.toSet()

    /** Largest frame that fits in one chunked BLE message to this central (255 chunks max). */
    fun maxFrameBytesFor(address: String): Int = 255 * (chunkSizeFor(address) - 4)

    private fun chunkSizeFor(address: String): Int = ((centralMtu[address] ?: 23) - 3).coerceIn(20, 512)

    /**
     * Push a frame to a connected central via notifications. Notifications are serialized per central:
     * the next chunk is only sent from onNotificationSent, otherwise the stack silently drops them.
     */
    fun notifyFrame(address: String, frameBytes: ByteArray): Boolean {
        val device = subscribedCentrals[address] ?: return false
        if (frameBytes.size > maxFrameBytesFor(address)) return false
        val chunks = try {
            P2pProtocol.chunkBlePayload(frameBytes, chunkSizeFor(address))
        } catch (e: Exception) {
            return false
        }
        val queue = notifyQueues.getOrPut(address) { ArrayDeque() }
        synchronized(queue) { chunks.forEach { queue.addLast(it) } }
        if (notifyInFlight.add(address)) {
            sendNextNotification(device)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun sendNextNotification(device: BluetoothDevice) {
        val address = device.address ?: return
        val queue = notifyQueues[address]
        val next = queue?.let { synchronized(it) { it.removeFirstOrNull() } }
        val server = gattServer
        val char = controlCharacteristic
        if (next == null || server == null || char == null || !subscribedCentrals.containsKey(address)) {
            notifyInFlight.remove(address)
            return
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, char, false, next) == BluetoothStatusCodes.SUCCESS
            } else {
                char.value = next
                server.notifyCharacteristicChanged(device, char, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "notifyCharacteristicChanged failed: ${e.message}")
            false
        }
        if (!ok) {
            queue?.let { synchronized(it) { it.clear() } }
            notifyInFlight.remove(address)
        }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false

    private val discoveredBleDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val reassemblyBuffers = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val reassemblyTotals = ConcurrentHashMap<String, Int>()

    fun startLowPowerChannel() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth disabled; BLE low-power wake channel skipped")
            return
        }
        setupGattServer()
        startLowPowerAdvertising()
        startHardwareFilteredLowPowerScan()
    }

    fun stopLowPowerChannel() {
        stopLowPowerScan()
        stopLowPowerAdvertising()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        controlCharacteristic = null
        subscribedCentrals.clear()
        notifyQueues.clear()
        notifyInFlight.clear()
    }

    fun onScreenInteractiveChanged(interactive: Boolean) {
        if (interactive) {
            startHardwareFilteredLowPowerScan()
        } else {
            // When screen is off, stop active scanning; keep ultra-low-power GATT server advertisement only
            stopLowPowerScan()
        }
    }

    /**
     * Send a ConnectP2P frame (e.g. `CLIPBOARD_SYNC` <= 16KB or `P2P_WAKE_REQUEST`)
     * to a discovered BLE peer by chunking into MTU-safe packets.
     */
    fun sendFrameOverBle(targetDevice: BluetoothDevice, frameBytes: ByteArray, isClipboard: Boolean = false) {
        val chunks = P2pProtocol.chunkBlePayload(frameBytes)
        val targetCharUuid = if (isClipboard) CLIPBOARD_CHAR_UUID else CONTROL_CHAR_UUID

        targetDevice.connectGatt(context, false, object : BluetoothGattCallback() {
            private var chunkIndex = 0

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeNextChunk(gatt)
                } else {
                    gatt.disconnect()
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                chunkIndex++
                if (chunkIndex < chunks.size) {
                    writeNextChunk(gatt)
                } else {
                    gatt.disconnect()
                }
            }

            @Suppress("DEPRECATION")
            private fun writeNextChunk(gatt: BluetoothGatt) {
                val service = gatt.getService(SERVICE_UUID) ?: run {
                    gatt.disconnect()
                    return
                }
                val char = service.getCharacteristic(targetCharUuid) ?: run {
                    gatt.disconnect()
                    return
                }
                char.value = chunks[chunkIndex]
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(char)
            }
        })
    }

    fun broadcastFrameToDiscoveredBlePeers(frameBytes: ByteArray, isClipboard: Boolean = false) {
        for (device in discoveredBleDevices.values) {
            sendFrameOverBle(device, frameBytes, isClipboard)
        }
    }

    private fun setupGattServer() {
        val manager = bluetoothManager ?: return
        if (gattServer != null) return

        val server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val address = device.address ?: return
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    centralMtu.remove(address)
                    notifyQueues.remove(address)
                    notifyInFlight.remove(address)
                    reassemblyBuffers.keys.removeIf { it.startsWith("$address/") }
                    if (subscribedCentrals.remove(address) != null) {
                        Log.i(TAG, "Central $address disconnected")
                        onCentralLinkChanged(address, false)
                    }
                    // Some controllers stop connectable advertising while a link exists; make sure we are findable again.
                    restartAdvertising()
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Central $address connected")
                    // The LE link is owned by the system Bluetooth process and survives our process being
                    // killed; a PC still holding it never re-subscribes to this new server instance. Drop such
                    // links so the PC reconnects from scratch.
                    mainHandler.postDelayed({
                        if (!subscribedCentrals.containsKey(address)) {
                            Log.i(TAG, "Central $address did not subscribe; dropping link")
                            try {
                                gattServer?.cancelConnection(device)
                            } catch (_: Exception) {
                            }
                        }
                    }, 20_000L)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                device.address?.let { centralMtu[it] = mtu }
                Log.i(TAG, "MTU ${device.address} = $mtu")
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                val address = device.address ?: return
                if (descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != CONTROL_CHAR_UUID) return
                val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
                if (enabled) {
                    if (subscribedCentrals.put(address, device) == null) {
                        Log.i(TAG, "Central $address subscribed")
                        onCentralLinkChanged(address, true)
                        restartAdvertising()
                    }
                } else if (subscribedCentrals.remove(address) != null) {
                    onCentralLinkChanged(address, false)
                    restartAdvertising()
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                val value = if (subscribedCentrals.containsKey(device.address)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                sendNextNotification(device)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (value != null && value.size >= 4) {
                    handleIncomingBleChunk(value, device.address ?: "BLE")
                }
            }
        }) ?: return

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val controlChar = BluetoothGattCharacteristic(
            CONTROL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        controlChar.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val clipboardChar = BluetoothGattCharacteristic(
            CLIPBOARD_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(controlChar)
        service.addCharacteristic(clipboardChar)
        server.addService(service)
        gattServer = server
        controlCharacteristic = controlChar
    }

    private fun restartAdvertising() {
        stopLowPowerAdvertising()
        startLowPowerAdvertising()
    }

    private fun startLowPowerAdvertising() {
        if (isAdvertising) return
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        // Unlinked: ~4 Hz so a PC with a busy Bluetooth radio (headsets, mice) still finds us quickly.
        // Linked: 1 Hz, only so that another PC can discover us.
        val linked = subscribedCentrals.isNotEmpty()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (linked) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(if (linked) AdvertiseSettings.ADVERTISE_TX_POWER_LOW else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        try {
            adv.startAdvertising(settings, data, advertiseCallback)
            advertiser = adv
            isAdvertising = true
            Log.i(TAG, "Started BLE advertising (${if (linked) "LOW_POWER" else "BALANCED"})")
        } catch (e: Exception) {
            Log.w(TAG, "BLE Advertising start failed: ${e.message}")
        }
    }

    private fun stopLowPowerAdvertising() {
        if (!isAdvertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        isAdvertising = false
    }

    private fun startHardwareFilteredLowPowerScan() {
        if (isScanning) return
        val leScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        // Hardware offloading filter: only wakes host when matching our exact Service UUID!
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0L)
            .build()

        try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            scanner = leScanner
            isScanning = true
            Log.i(TAG, "Started hardware-filtered BLE SCAN_MODE_LOW_POWER")
        } catch (e: Exception) {
            Log.w(TAG, "BLE Scan start failed: ${e.message}")
        }
    }

    private fun stopLowPowerScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        isScanning = false
    }

    private fun handleIncomingBleChunk(chunk: ByteArray, senderAddress: String) {
        val msgId = "$senderAddress/${chunk[0].toInt() and 0xFF}"
        val idx = chunk[1].toInt() and 0xFF
        val total = chunk[2].toInt() and 0xFF
        val body = chunk.copyOfRange(4, chunk.size)

        if (reassemblyTotals[msgId] != total) {
            reassemblyBuffers[msgId] = ConcurrentHashMap()
            reassemblyTotals[msgId] = total
        }
        val partMap = reassemblyBuffers.getOrPut(msgId) { ConcurrentHashMap() }
        partMap[idx] = body

        if (partMap.size == total) {
            val totalBytes = (0 until total).sumOf { partMap[it]?.size ?: 0 }
            val assembled = ByteArray(totalBytes)
            var offset = 0
            for (i in 0 until total) {
                val part = partMap[i] ?: return
                System.arraycopy(part, 0, assembled, offset, part.size)
                offset += part.size
            }
            reassemblyBuffers.remove(msgId)
            reassemblyTotals.remove(msgId)
            try {
                val decoded = P2pProtocol.decodeFrameFromBytes(assembled)
                onControlFrameReceived(decoded, senderAddress)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode reassembled BLE frame: ${e.message}")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Low-Power Advertising active")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertising failed with code $errorCode")
            isAdvertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val addr = device.address ?: return
            discoveredBleDevices[addr] = device
        }
    }
}
