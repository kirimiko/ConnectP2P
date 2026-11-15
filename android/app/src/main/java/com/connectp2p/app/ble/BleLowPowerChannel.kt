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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.connectp2p.app.protocol.P2pProtocol
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Low-power BLE link layer.
 *
 * - Peripheral role (always on): GATT server + connectable advertising. PCs and other phones connect to us,
 *   subscribe to notifications on the control characteristic and write frames to it.
 * - Central role: this phone connects to another phone's GATT server (see [connectToPeripheral]). Between
 *   two phones exactly one side connects, chosen by the device id hash carried in the advertisement.
 *
 * Frames are chunked to the link MTU with [P2pProtocol.chunkBlePayload]. Both roles look the same to the
 * caller: [isLinked], [sendFrame], [linkedAddresses] and [onLinkChanged] work per remote BLE address.
 *
 * Advertising: [version][platform][4-byte id hash] as service data, the display name in the scan response.
 * Scanning: hardware-filtered SCAN_MODE_LOW_POWER; paused with the screen off unless a trusted phone is
 * waiting to be reconnected.
 */
@SuppressLint("MissingPermission")
class BleLowPowerChannel(
    private val context: Context,
    private val localDeviceId: String,
    private val localName: () -> String,
    private val onControlFrameReceived: (P2pProtocol.DecodedFrame, String) -> Unit,
    /** A BLE link (either role) became usable for frames / went away. */
    private val onLinkChanged: (address: String, up: Boolean) -> Unit = { _, _ -> },
    /** Another phone running ConnectP2P was seen advertising. */
    private val onPeerAdvertised: (PeerAdvertisement) -> Unit = {},
    /** Frame sent to idle peripheral links to prove the remote app is still alive. */
    private val pingFrame: () -> ByteArray = { ByteArray(0) },
    private val shouldScanWhileScreenOff: () -> Boolean = { false }
) {
    data class PeerAdvertisement(
        val address: String,
        val idHash: String,
        val platform: Char,
        val name: String,
        val rssi: Int
    )

    companion object {
        private const val TAG = "ConnectP2P.BLE"
        val SERVICE_UUID: UUID = UUID.fromString(P2pProtocol.BLE_SERVICE_UUID)
        val CONTROL_CHAR_UUID: UUID = UUID.fromString(P2pProtocol.BLE_CONTROL_CHAR_UUID)
        val CLIPBOARD_CHAR_UUID: UUID = UUID.fromString(P2pProtocol.BLE_CLIPBOARD_CHAR_UUID)
        val NAME_DATA_UUID: UUID = UUID.fromString(P2pProtocol.BLE_NAME_DATA_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_NAME_BYTES = 20
        private const val PING_AFTER_MS = 25_000L
        private const val PING_ANSWER_MS = 12_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    val localIdHash: String = P2pProtocol.bleIdHash(localDeviceId)

    // ---------------- peripheral (server) role ----------------
    private var gattServer: BluetoothGattServer? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    /** Centrals subscribed to notifications on the control characteristic, by address. */
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val centralMtu = ConcurrentHashMap<String, Int>()
    private val notifyQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val notifyInFlight = ConcurrentHashMap.newKeySet<String>()

    // ---------------- central (client) role ----------------
    private class ClientLink(val address: String, val gatt: BluetoothGatt) {
        @Volatile var mtu = 23
        @Volatile var subscribed = false
        @Volatile var characteristic: BluetoothGattCharacteristic? = null
        val queue = ArrayDeque<ByteArray>()
        @Volatile var writing = false
        @Volatile var lastRx = SystemClock.elapsedRealtime()
        @Volatile var lastPing = 0L
    }

    private val clientLinks = ConcurrentHashMap<String, ClientLink>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false
    @Volatile
    private var screenInteractive = true
    private val lastAdReport = ConcurrentHashMap<String, Long>()
    private val reassemblyBuffers = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val reassemblyTotals = ConcurrentHashMap<String, Int>()

    @Volatile
    private var channelStopped = false
    private var adapterReceiverRegistered = false

    // ---------------- public link API ----------------

    fun isLinked(address: String?): Boolean =
        address != null && (subscribedCentrals.containsKey(address) || clientLinks[address]?.subscribed == true)

    fun linkedAddresses(): Set<String> =
        subscribedCentrals.keys + clientLinks.values.filter { it.subscribed }.map { it.address }

    /** True while we are connecting to, or connected to, this peripheral. */
    fun hasClientLink(address: String): Boolean = clientLinks.containsKey(address)

    /** Largest frame that fits in one chunked BLE message on this link (255 chunks max). */
    fun maxFrameBytesFor(address: String): Int = 255 * (chunkSizeFor(address) - 4)

    private fun chunkSizeFor(address: String): Int {
        val mtu = centralMtu[address] ?: clientLinks[address]?.mtu ?: 23
        return (mtu - 3).coerceIn(20, 512)
    }

    fun sendFrame(address: String, frameBytes: ByteArray): Boolean {
        if (subscribedCentrals.containsKey(address)) return notifyFrame(address, frameBytes)
        val link = clientLinks[address]
        if (link != null && link.subscribed) return writeFrame(link, frameBytes)
        return false
    }

    /** Connect to another phone's GATT server as central. The link is reported via [onLinkChanged]. */
    fun connectToPeripheral(address: String): Boolean {
        if (clientLinks.containsKey(address) || subscribedCentrals.containsKey(address)) return false
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            return false
        }
        val gatt = try {
            device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt($address) failed: ${e.message}")
            null
        } ?: return false
        clientLinks[address] = ClientLink(address, gatt)
        Log.i(TAG, "Connecting to peripheral $address")
        mainHandler.postDelayed({
            val link = clientLinks[address]
            if (link != null && !link.subscribed) {
                Log.i(TAG, "Peripheral $address not ready in time; giving up")
                closeClient(address)
            }
        }, 20_000L)
        return true
    }

    fun disconnectLink(address: String) {
        if (clientLinks.containsKey(address)) {
            closeClient(address)
            return
        }
        subscribedCentrals[address]?.let { device ->
            try {
                gattServer?.cancelConnection(device)
            } catch (_: Exception) {
            }
        }
    }

    /** Re-advertise, e.g. after the display name changed. */
    fun refreshAdvertising() = restartAdvertising()

    // ---------------- lifecycle ----------------

    fun startLowPowerChannel() {
        registerAdapterStateReceiver()
        scheduleHealthCheck()
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth disabled; BLE channel starts when it is turned on")
            return
        }
        setupGattServer()
        startLowPowerAdvertising()
        startHardwareFilteredLowPowerScan()
    }

    fun stopLowPowerChannel() {
        channelStopped = true
        mainHandler.removeCallbacks(healthCheck)
        mainHandler.removeCallbacks(clientKeepalive)
        if (adapterReceiverRegistered) {
            try {
                context.applicationContext.unregisterReceiver(adapterStateReceiver)
            } catch (_: Exception) {
            }
            adapterReceiverRegistered = false
        }
        stopLowPowerScan()
        stopLowPowerAdvertising()
        clientLinks.keys.toList().forEach { closeClient(it, notify = false) }
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
        screenInteractive = interactive
        if (interactive || shouldScanWhileScreenOff()) {
            startHardwareFilteredLowPowerScan()
        } else {
            // With the screen off only advertising stays on, unless a trusted phone needs reconnecting.
            stopLowPowerScan()
        }
    }

    /**
     * Turning Bluetooth off (Quick Settings, OEM power savers, a stack restart) silently destroys our GATT
     * server, client links and advertising set; nothing tells the app to recreate them. Rebuild on STATE_ON.
     */
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth turning off; dropping BLE channel state")
                    resetAfterAdapterLoss()
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on; rebuilding GATT server and advertising")
                    resetAfterAdapterLoss()
                    if (!channelStopped) startLowPowerChannel()
                }
            }
        }
    }

    private fun registerAdapterStateReceiver() {
        if (adapterReceiverRegistered) return
        channelStopped = false
        try {
            context.applicationContext.registerReceiver(
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            adapterReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch Bluetooth state: ${e.message}")
        }
    }

    private fun resetAfterAdapterLoss() {
        val lost = linkedAddresses().toList()
        clientLinks.keys.toList().forEach { closeClient(it, notify = false) }
        subscribedCentrals.clear()
        centralMtu.clear()
        notifyQueues.clear()
        notifyInFlight.clear()
        reassemblyBuffers.clear()
        reassemblyTotals.clear()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        controlCharacteristic = null
        advertiser = null
        scanner = null
        isAdvertising = false
        isScanning = false
        lost.forEach { onLinkChanged(it, false) }
    }

    /** Safety net in case a state broadcast is missed or advertising / scanning failed to start. */
    private val healthCheck = object : Runnable {
        override fun run() {
            if (channelStopped) return
            if (bluetoothAdapter?.isEnabled == true) {
                if (gattServer == null || !isAdvertising) {
                    Log.i(TAG, "BLE channel incomplete (server=${gattServer != null}, advertising=$isAdvertising); restarting")
                    if (gattServer == null) resetAfterAdapterLoss()
                    setupGattServer()
                    startLowPowerAdvertising()
                }
                if (screenInteractive || shouldScanWhileScreenOff()) {
                    startHardwareFilteredLowPowerScan()
                } else {
                    stopLowPowerScan()
                }
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    private fun scheduleHealthCheck() {
        mainHandler.removeCallbacks(healthCheck)
        mainHandler.postDelayed(healthCheck, 60_000L)
    }

    // ---------------- peripheral role internals ----------------

    /**
     * Push a frame to a subscribed central via notifications. Notifications are serialized per central:
     * the next chunk is only sent from onNotificationSent, otherwise the stack silently drops them.
     */
    private fun notifyFrame(address: String, frameBytes: ByteArray): Boolean {
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

    private fun setupGattServer() {
        val manager = bluetoothManager ?: return
        if (gattServer != null) return

        val server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val address = device.address ?: return
                if (clientLinks.containsKey(address)) return // our own outgoing client link
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    centralMtu.remove(address)
                    notifyQueues.remove(address)
                    notifyInFlight.remove(address)
                    reassemblyBuffers.keys.removeIf { it.startsWith("$address/") }
                    if (subscribedCentrals.remove(address) != null) {
                        Log.i(TAG, "Central $address disconnected")
                        onLinkChanged(address, false)
                    }
                    // Some controllers stop connectable advertising while a link exists; make sure we are findable again.
                    restartAdvertising()
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Central $address connected")
                    // The LE link is owned by the system Bluetooth process and survives our process being
                    // killed; a PC still holding it never re-subscribes to this new server instance. Drop such
                    // links so the PC reconnects from scratch.
                    mainHandler.postDelayed({
                        if (!subscribedCentrals.containsKey(address) && !clientLinks.containsKey(address)) {
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
                        onLinkChanged(address, true)
                        restartAdvertising()
                    }
                } else if (subscribedCentrals.remove(address) != null) {
                    onLinkChanged(address, false)
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

    // ---------------- central role internals ----------------

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Connected to peripheral $address")
                if (!gatt.requestMtu(517)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Peripheral $address disconnected (status=$status)")
                closeClient(address)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.device?.address?.let { clientLinks[it]?.mtu = mtu }
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device?.address ?: return
            val link = clientLinks[address] ?: return
            val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_CHAR_UUID)
            val cccd = char?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || char == null || cccd == null) {
                Log.w(TAG, "Peripheral $address has no ConnectP2P service (status=$status)")
                closeClient(address)
                return
            }
            link.characteristic = char
            gatt.setCharacteristicNotification(char, true)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            if (!ok) closeClient(address)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val address = gatt.device?.address ?: return
            val link = clientLinks[address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeClient(address)
                return
            }
            if (!link.subscribed) {
                link.subscribed = true
                link.lastRx = SystemClock.elapsedRealtime()
                Log.i(TAG, "Subscribed to peripheral $address (mtu=${link.mtu})")
                onLinkChanged(address, true)
                scheduleClientKeepalive()
                restartAdvertising()
            }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleClientNotification(gatt, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleClientNotification(gatt, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val link = clientLinks[gatt.device?.address ?: return] ?: return
            link.writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                synchronized(link.queue) { link.queue.clear() }
                return
            }
            writeNextChunk(link)
        }
    }

    private fun handleClientNotification(gatt: BluetoothGatt, value: ByteArray) {
        val address = gatt.device?.address ?: return
        clientLinks[address]?.lastRx = SystemClock.elapsedRealtime()
        if (value.size >= 4) handleIncomingBleChunk(value, address)
    }

    private fun writeFrame(link: ClientLink, frameBytes: ByteArray): Boolean {
        if (frameBytes.size > maxFrameBytesFor(link.address)) return false
        val chunks = try {
            P2pProtocol.chunkBlePayload(frameBytes, chunkSizeFor(link.address))
        } catch (e: Exception) {
            return false
        }
        val startNow = synchronized(link.queue) {
            chunks.forEach { link.queue.addLast(it) }
            !link.writing
        }
        if (startNow) writeNextChunk(link)
        return true
    }

    @Suppress("DEPRECATION")
    private fun writeNextChunk(link: ClientLink) {
        val char = link.characteristic ?: return
        val next = synchronized(link.queue) {
            if (link.writing) return
            val chunk = link.queue.removeFirstOrNull() ?: return
            link.writing = true
            chunk
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                link.gatt.writeCharacteristic(char, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                char.value = next
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                link.gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeCharacteristic to ${link.address} failed: ${e.message}")
            false
        }
        if (!ok) {
            synchronized(link.queue) {
                link.queue.clear()
                link.writing = false
            }
        }
    }

    private fun closeClient(address: String, notify: Boolean = true) {
        val link = clientLinks.remove(address) ?: return
        try {
            link.gatt.disconnect()
        } catch (_: Exception) {
        }
        try {
            link.gatt.close()
        } catch (_: Exception) {
        }
        reassemblyBuffers.keys.removeIf { it.startsWith("$address/") }
        if (notify && link.subscribed) onLinkChanged(address, false)
        if (link.subscribed) restartAdvertising()
    }

    /**
     * A peripheral phone's LE link outlives its app process, so a connected link proves nothing: ping idle
     * links and drop those whose app does not answer.
     */
    private val clientKeepalive = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            for (link in clientLinks.values.toList()) {
                if (!link.subscribed) continue
                if (link.lastPing > link.lastRx && now - link.lastPing >= PING_ANSWER_MS) {
                    Log.i(TAG, "Peripheral ${link.address} did not answer a ping; dropping link")
                    closeClient(link.address)
                } else if (now - link.lastRx >= PING_AFTER_MS && link.lastPing <= link.lastRx) {
                    link.lastPing = now
                    writeFrame(link, pingFrame())
                }
            }
            if (clientLinks.isNotEmpty() && !channelStopped) mainHandler.postDelayed(this, 4_000L)
        }
    }

    private fun scheduleClientKeepalive() {
        mainHandler.removeCallbacks(clientKeepalive)
        mainHandler.postDelayed(clientKeepalive, 4_000L)
    }

    // ---------------- advertising & scanning ----------------

    private fun restartAdvertising() {
        stopLowPowerAdvertising()
        startLowPowerAdvertising()
    }

    private fun startLowPowerAdvertising() {
        if (isAdvertising) return
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        // Unlinked: ~4 Hz so a PC with a busy Bluetooth radio (headsets, mice) still finds us quickly.
        // Linked: 1 Hz, only so that other devices can still discover us.
        val linked = linkedAddresses().isNotEmpty()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (linked) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(if (linked) AdvertiseSettings.ADVERTISE_TX_POWER_LOW else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val idData = byteArrayOf(P2pProtocol.BLE_AD_VERSION, 'a'.code.toByte()) + P2pProtocol.bleIdHashBytes(localDeviceId)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), idData)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(NAME_DATA_UUID), truncatedName(localName()))
            .build()

        try {
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertiser = adv
            isAdvertising = true
            Log.i(TAG, "Started BLE advertising (${if (linked) "LOW_POWER" else "BALANCED"})")
        } catch (e: Exception) {
            Log.w(TAG, "BLE Advertising start failed: ${e.message}")
        }
    }

    private fun truncatedName(name: String): ByteArray {
        val sb = StringBuilder()
        for (ch in name) {
            if ((sb.toString() + ch).toByteArray(StandardCharsets.UTF_8).size > MAX_NAME_BYTES) break
            sb.append(ch)
        }
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
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
            Log.d(TAG, "BLE advertising active")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertising failed with code $errorCode")
            isAdvertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val address = r.device?.address ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - (lastAdReport[address] ?: 0L) < 3_000L) return
            lastAdReport[address] = now
            val record = r.scanRecord ?: return
            // Builds without the id service data (and PCs, which do not advertise) are ignored.
            val idData = record.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (idData.size < 6 || idData[0] != P2pProtocol.BLE_AD_VERSION) return
            val idHash = P2pProtocol.bytesToHex(idData.copyOfRange(2, 6))
            if (idHash == localIdHash) return
            val name = record.getServiceData(ParcelUuid(NAME_DATA_UUID))?.toString(StandardCharsets.UTF_8).orEmpty()
            onPeerAdvertised(PeerAdvertisement(address, idHash, idData[1].toInt().toChar(), name, r.rssi))
        }
    }
}
