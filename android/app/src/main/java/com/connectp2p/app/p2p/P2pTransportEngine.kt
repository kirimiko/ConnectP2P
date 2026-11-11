package com.connectp2p.app.p2p

import android.net.Network
import android.util.Log
import com.connectp2p.app.power.PowerPolicyManager
import com.connectp2p.app.protocol.P2pProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * High-Speed TCP Transport Engine & Event-Driven UDP Control Socket for Android.
 *
 * Implements:
 * - Breakpoint resume (`FILE_OFFER` -> `FILE_ACCEPT` with `resume_offset` -> `FILE_DATA` -> `FILE_ACK`)
 * - 256 KB buffered streaming + SHA-256 integrity verification
 * - Event-driven UDP control frames (`HELLO`, `CLIPBOARD_SYNC`, `P2P_WAKE_REQUEST`, `P2P_GROUP_READY`, `P2P_TEARDOWN`)
 * - Strict integration with `PowerPolicyManager` (`onTransferStarted` / `onTransferFinished`)
 */
class P2pTransportEngine(
    private val deviceId: String,
    @Volatile var deviceName: String,
    private val downloadDir: File,
    private val powerPolicy: PowerPolicyManager,
    private val onControlFrameReceived: (P2pProtocol.DecodedFrame, String) -> Unit,
    private val onClipboardReceived: (text: String, clipHash: String, senderName: String) -> Unit,
    private val onFileProgress: (fileName: String, transferred: Long, total: Long, isSending: Boolean) -> Unit,
    private val onFileCompleted: (file: File, senderName: String, verified: Boolean) -> Unit,
    private val isSenderAuthorized: (senderId: String, senderName: String, senderIp: String) -> Boolean = { _, _, _ -> true }
) {
    companion object {
        private const val TAG = "ConnectP2P.Transport"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tcpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    /**
     * Resolves destinations that live on a non-default network (the PC hotspot joined via
     * WifiNetworkSpecifier). Sockets to those IPs must be bound to that network explicitly,
     * otherwise they follow the default network (phone's own Wi-Fi / VPN) and never arrive.
     */
    @Volatile
    var networkResolver: (String) -> Network? = { null }

    private val boundUdpSockets = HashMap<Network, DatagramSocket>()

    private fun newTcpSocket(targetIp: String): Socket {
        val sock = Socket()
        networkResolver(targetIp)?.let { net ->
            try {
                net.bindSocket(sock)
            } catch (e: Exception) {
                Log.w(TAG, "bindSocket(tcp) to $net failed: ${e.message}")
            }
        }
        return sock
    }

    private fun udpSenderFor(targetIp: String): DatagramSocket? {
        val net = networkResolver(targetIp) ?: return udpSocket ?: try {
            DatagramSocket().apply { broadcast = true }
        } catch (_: Exception) {
            null
        }
        synchronized(boundUdpSockets) {
            boundUdpSockets[net]?.takeIf { !it.isClosed }?.let { return it }
            return try {
                DatagramSocket().apply {
                    broadcast = true
                    net.bindSocket(this)
                }.also { boundUdpSockets[net] = it }
            } catch (e: Exception) {
                Log.w(TAG, "bindSocket(udp) to $net failed: ${e.message}")
                udpSocket
            }
        }
    }

    fun releaseNetworkSockets() {
        synchronized(boundUdpSockets) {
            boundUdpSockets.values.forEach { runCatching { it.close() } }
            boundUdpSockets.clear()
        }
    }

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        downloadDir.mkdirs()

        scope.launch { runUdpControlLoop() }
        scope.launch { runTcpDataServerLoop() }
    }

    fun stop() {
        running = false
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            tcpServer?.close()
        } catch (_: Exception) {
        }
    }

    fun sendControlFrameUdp(frameBytes: ByteArray, targetIp: String = "255.255.255.255") {
        scope.launch {
            try {
                val sock = udpSenderFor(targetIp) ?: return@launch
                val addr = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(frameBytes, frameBytes.size, addr, P2pProtocol.CONTROL_UDP_PORT)
                sock.send(packet)
            } catch (e: Exception) {
                Log.d(TAG, "UDP control send to $targetIp failed: ${e.message}")
            }
        }
    }

    fun sendControlFrameTcp(frameBytes: ByteArray, targetIp: String, port: Int = P2pProtocol.DATA_TCP_PORT) {
        scope.launch {
            try {
                newTcpSocket(targetIp).use { sock ->
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(targetIp, port), 1500)
                    sock.getOutputStream().write(frameBytes)
                    sock.getOutputStream().flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP control send to $targetIp failed: ${e.message}")
            }
        }
    }

    suspend fun probePeerAlive(
        targetIp: String,
        port: Int = P2pProtocol.DATA_TCP_PORT,
        timeoutMs: Int = 1500
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            newTcpSocket(targetIp).use { sock ->
                sock.tcpNoDelay = true
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(targetIp, port), timeoutMs)
                val pingFrame = P2pProtocol.encodeFrame(
                    P2pProtocol.FrameType.PING_PONG,
                    JSONObject().put("ping", true).put("device_id", deviceId).put("device_name", deviceName)
                )
                sock.getOutputStream().write(pingFrame)
                sock.getOutputStream().flush()
                val dis = DataInputStream(BufferedInputStream(sock.getInputStream()))
                val (ftype, hdr) = P2pProtocol.readFrameHeaderFromStream(dis)
                val psize = hdr.optInt("payload_size", 0)
                if (psize > 0) dis.skipBytes(psize)
                ftype == P2pProtocol.FrameType.PING_PONG
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun broadcastControlFrameUdp(frameBytes: ByteArray, extraTargetIp: String? = null) {
        scope.launch {
            val targets = mutableSetOf(
                "255.255.255.255",
                P2pProtocol.MULTICAST_GROUP,
                P2pProtocol.ANDROID_GO_DEFAULT_IP,
                "192.168.49.255",
                P2pProtocol.WINDOWS_GO_DEFAULT_IP,
                "192.168.137.255"
            )
            if (!extraTargetIp.isNullOrBlank()) {
                targets.add(extraTargetIp)
            }
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val bcast = ia.broadcast
                        if (bcast is Inet4Address) {
                            bcast.hostAddress?.let { targets.add(it) }
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            for (ip in targets) {
                try {
                    val sock = udpSenderFor(ip) ?: continue
                    val addr = InetAddress.getByName(ip)
                    val packet = DatagramPacket(frameBytes, frameBytes.size, addr, P2pProtocol.CONTROL_UDP_PORT)
                    sock.send(packet)
                } catch (_: Throwable) {
                }
            }
        }
    }

    suspend fun sendClipboardOverTcp(targetIp: String, text: String, port: Int = P2pProtocol.DATA_TCP_PORT): Boolean =
        withContext(Dispatchers.IO) {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val clipHash = P2pProtocol.computeClipHash(payload)
            powerPolicy.onTransferStarted()
            try {
                newTcpSocket(targetIp).use { sock ->
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(targetIp, port), 6000)
                    val header = JSONObject().apply {
                        put("device_id", deviceId)
                        put("device_name", deviceName)
                        put("clip_id", clipHash)
                        put("mime", "text/plain")
                        put("timestamp", System.currentTimeMillis())
                        put("origin_device_id", deviceId)
                    }
                    val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.CLIPBOARD_SYNC, header, payload)
                    sock.getOutputStream().write(frame)
                    sock.getOutputStream().flush()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendClipboardOverTcp failed: ${e.message}")
                false
            } finally {
                powerPolicy.onTransferFinished()
            }
        }

    suspend fun sendFile(
        targetIp: String,
        file: File,
        port: Int = P2pProtocol.DATA_TCP_PORT
    ): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext false

        val fileSize = file.length()
        val sha256 = P2pProtocol.computeFileSha256(file)
        val fileId = sha256.take(16)

        powerPolicy.onTransferStarted()
        try {
            newTcpSocket(targetIp).use { sock ->
                sock.sendBufferSize = P2pProtocol.SOCKET_BUFFER_SIZE
                sock.tcpNoDelay = true
                sock.soTimeout = 20_000
                sock.connect(InetSocketAddress(targetIp, port), 10_000)

                val out = BufferedOutputStream(sock.getOutputStream(), P2pProtocol.SOCKET_BUFFER_SIZE)
                val dis = DataInputStream(BufferedInputStream(sock.getInputStream()))

                // 1. Send FILE_OFFER
                val offerHeader = JSONObject().apply {
                    put("file_id", fileId)
                    put("file_name", file.name)
                    put("file_size", fileSize)
                    put("sha256", sha256)
                    put("sender_id", deviceId)
                    put("sender_name", deviceName)
                }
                out.write(P2pProtocol.encodeFrame(P2pProtocol.FrameType.FILE_OFFER, offerHeader))
                out.flush()

                // 2. Receive FILE_ACCEPT
                val (acceptType, acceptHeader) = P2pProtocol.readFrameHeaderFromStream(dis)
                val acceptPayloadSize = acceptHeader.optInt("payload_size", 0)
                if (acceptPayloadSize > 0) {
                    dis.skipBytes(acceptPayloadSize)
                }
                if (acceptType != P2pProtocol.FrameType.FILE_ACCEPT || !acceptHeader.optBoolean("accepted", false)) {
                    return@withContext false
                }

                val resumeOffset = acceptHeader.optLong("resume_offset", 0L).coerceIn(0L, fileSize)
                val remaining = fileSize - resumeOffset

                // 3. Send AES-256-GCM encrypted FILE_DATA header + AES-256-CTR encrypted binary stream
                val streamIv = P2pProtocol.generateRandomIv16()
                val ctrCipher = P2pProtocol.createAesCtrCipher(encryptMode = true, iv16 = streamIv)
                val dataHeader = JSONObject().apply {
                    put("file_id", fileId)
                    put("offset", resumeOffset)
                    put("enc_stream_iv", P2pProtocol.bytesToHex(streamIv))
                }
                out.write(P2pProtocol.encodeStreamHeaderOnly(P2pProtocol.FrameType.FILE_DATA, dataHeader, remaining))

                FileInputStream(file).use { fis ->
                    if (resumeOffset > 0) {
                        var skipped = 0L
                        while (skipped < resumeOffset) {
                            val s = fis.skip(resumeOffset - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                    }
                    val buf = ByteArray(P2pProtocol.SOCKET_BUFFER_SIZE)
                    val encBuf = ByteArray(P2pProtocol.SOCKET_BUFFER_SIZE)
                    var sentTotal = resumeOffset
                    while (sentTotal < fileSize) {
                        val read = fis.read(buf)
                        if (read <= 0) break
                        val encLen = ctrCipher.update(buf, 0, read, encBuf, 0)
                        if (encLen > 0) {
                            out.write(encBuf, 0, encLen)
                        }
                        sentTotal += read
                        onFileProgress(file.name, sentTotal, fileSize, true)
                    }
                    val finalBytes = ctrCipher.doFinal()
                    if (finalBytes != null && finalBytes.isNotEmpty()) {
                        out.write(finalBytes)
                    }
                }
                out.flush()

                // 4. Read FILE_ACK
                val (ackType, ackHeader) = P2pProtocol.readFrameHeaderFromStream(dis)
                val ackPayloadSize = ackHeader.optInt("payload_size", 0)
                if (ackPayloadSize > 0) {
                    dis.skipBytes(ackPayloadSize)
                }
                ackType == P2pProtocol.FrameType.FILE_ACK && ackHeader.optBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFile failed for ${file.name}: ${e.message}")
            false
        } finally {
            powerPolicy.onTransferFinished()
        }
    }

    private suspend fun runUdpControlLoop() {
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(P2pProtocol.CONTROL_UDP_PORT))
            }
            udpSocket = sock
            val buf = ByteArray(65535)
            while (running && scope.isActive) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt) // Blocks in kernel with 0% CPU until a packet arrives
                val senderIp = pkt.address.hostAddress ?: ""
                try {
                    val decoded = P2pProtocol.decodeFrameFromBytes(pkt.data, pkt.length)
                    val remoteId = decoded.header.optString("device_id", "")
                    val targetId = decoded.header.optString("target_device_id", "")
                    if (remoteId.isNotEmpty() && remoteId != deviceId && (targetId.isEmpty() || targetId == deviceId)) {
                        onControlFrameReceived(decoded, senderIp)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Ignored malformed UDP frame from $senderIp: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "UDP control socket stopped: ${e.message}")
        }
    }

    private suspend fun runTcpDataServerLoop() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                receiveBufferSize = P2pProtocol.SOCKET_BUFFER_SIZE
                bind(InetSocketAddress("0.0.0.0", P2pProtocol.DATA_TCP_PORT))
            }
            tcpServer = server
            while (running && scope.isActive) {
                val client = server.accept() // Blocks in kernel with 0% CPU until transfer connection arrives
                scope.launch { handleTcpClient(client) }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "TCP data server stopped: ${e.message}")
        }
    }

    private fun handleTcpClient(client: Socket) {
        var didStartBurst = false
        try {
            client.receiveBufferSize = P2pProtocol.SOCKET_BUFFER_SIZE
            client.tcpNoDelay = true
            client.soTimeout = 30_000

            val dis = DataInputStream(BufferedInputStream(client.getInputStream(), P2pProtocol.SOCKET_BUFFER_SIZE))
            val out = BufferedOutputStream(client.getOutputStream())
            val senderIp = client.inetAddress.hostAddress ?: "Peer"

            val (frameType, header) = P2pProtocol.readFrameHeaderFromStream(dis)
            val payloadSize = header.optInt("payload_size", 0)
            val senderId = header.optString("device_id", header.optString("sender_id", ""))
            val senderName = header.optString("device_name", header.optString("sender_name", senderIp))

            when (frameType) {
                P2pProtocol.FrameType.CLIPBOARD_SYNC -> {
                    val wirePayload = ByteArray(payloadSize)
                    if (payloadSize > 0) dis.readFully(wirePayload)
                    if (!isSenderAuthorized(senderId, senderName, senderIp)) {
                        Log.w(TAG, "Blocked unauthorized TCP clipboard from $senderName ($senderIp)")
                        return
                    }
                    val payload = P2pProtocol.decryptPayloadIfNeeded(header, wirePayload)
                    val text = String(payload, StandardCharsets.UTF_8)
                    val clipId = header.optString("clip_id", P2pProtocol.computeClipHash(payload))
                    onClipboardReceived(text, clipId, senderName)
                }

                P2pProtocol.FrameType.FILE_OFFER -> {
                    if (payloadSize > 0) dis.skipBytes(payloadSize)
                    if (!isSenderAuthorized(senderId, senderName, senderIp)) {
                        Log.w(TAG, "Blocked unauthorized FILE_OFFER from $senderName ($senderIp)")
                        val rejectFrame = P2pProtocol.encodeFrame(
                            P2pProtocol.FrameType.FILE_ACCEPT,
                            JSONObject().apply {
                                put("file_id", header.optString("file_id", ""))
                                put("accepted", false)
                                put("resume_offset", 0)
                            }
                        )
                        out.write(rejectFrame)
                        out.flush()
                        return
                    }
                    didStartBurst = true
                    powerPolicy.onTransferStarted()
                    receiveFileWithResume(dis, out, header, senderIp)
                }

                P2pProtocol.FrameType.PING_PONG -> {
                    if (payloadSize > 0) dis.skipBytes(payloadSize)
                    val pong = P2pProtocol.encodeFrame(
                        P2pProtocol.FrameType.PING_PONG,
                        JSONObject().put("pong", true).put("device_id", deviceId)
                    )
                    out.write(pong)
                    out.flush()
                }

                else -> {
                    val wirePayload = ByteArray(maxOf(0, payloadSize))
                    if (payloadSize > 0) dis.readFully(wirePayload)
                    val plainPayload = P2pProtocol.decryptPayloadIfNeeded(header, wirePayload)
                    val targetId = header.optString("target_device_id", "")
                    if (senderId != deviceId && (targetId.isEmpty() || targetId == deviceId)) {
                        onControlFrameReceived(P2pProtocol.DecodedFrame(frameType, header, plainPayload), senderIp)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TCP client: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            if (didStartBurst) {
                powerPolicy.onTransferFinished()
            }
        }
    }

    private fun receiveFileWithResume(
        dis: DataInputStream,
        out: BufferedOutputStream,
        offerHeader: JSONObject,
        senderIp: String
    ) {
        val fileId = offerHeader.optString("file_id", "unknown")
        val rawName = File(offerHeader.optString("file_name", "file_$fileId.bin")).name
        val fileSize = offerHeader.optLong("file_size", 0L)
        val expectedSha256 = offerHeader.optString("sha256", "").lowercase()
        val senderName = offerHeader.optString("sender_name", senderIp)

        val partFile = File(downloadDir, "$rawName.$fileId.part")
        var finalFile = File(downloadDir, rawName)

        val resumeOffset = if (partFile.exists() && partFile.length() in 1 until fileSize) {
            partFile.length()
        } else {
            if (partFile.exists()) partFile.delete()
            0L
        }

        val acceptFrame = P2pProtocol.encodeFrame(
            P2pProtocol.FrameType.FILE_ACCEPT,
            JSONObject().apply {
                put("file_id", fileId)
                put("accepted", true)
                put("resume_offset", resumeOffset)
            }
        )
        out.write(acceptFrame)
        out.flush()

        val (dataType, dataHeader) = P2pProtocol.readFrameHeaderFromStream(dis)
        require(dataType == P2pProtocol.FrameType.FILE_DATA) { "Expected FILE_DATA, got $dataType" }
        val streamLen = dataHeader.optLong("payload_size", fileSize - resumeOffset)
        val ivHex = dataHeader.optString("enc_stream_iv", "")
        val ctrCipher = if (ivHex.isNotEmpty()) {
            P2pProtocol.createAesCtrCipher(encryptMode = false, iv16 = P2pProtocol.hexToBytes(ivHex))
        } else {
            null
        }

        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(resumeOffset)
            val buf = ByteArray(P2pProtocol.SOCKET_BUFFER_SIZE)
            val decBuf = ByteArray(P2pProtocol.SOCKET_BUFFER_SIZE)
            var remaining = streamLen
            var totalReceived = resumeOffset
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = dis.read(buf, 0, toRead)
                if (read < 0) throw IllegalStateException("Stream interrupted at $totalReceived/$fileSize")
                if (ctrCipher != null) {
                    val decLen = ctrCipher.update(buf, 0, read, decBuf, 0)
                    if (decLen > 0) {
                        raf.write(decBuf, 0, decLen)
                    }
                } else {
                    raf.write(buf, 0, read)
                }
                remaining -= read
                totalReceived += read
                onFileProgress(rawName, totalReceived, fileSize, false)
            }
            if (ctrCipher != null) {
                val finalDec = ctrCipher.doFinal()
                if (finalDec != null && finalDec.isNotEmpty()) {
                    raf.write(finalDec)
                }
            }
        }

        val actualSha256 = P2pProtocol.computeFileSha256(partFile).lowercase()
        val verified = expectedSha256.isEmpty() || actualSha256 == expectedSha256

        if (verified) {
            if (finalFile.exists()) {
                finalFile = File(downloadDir, "${finalFile.nameWithoutExtension}_${System.currentTimeMillis()}.${finalFile.extension}")
            }
            partFile.renameTo(finalFile)
        } else {
            partFile.delete()
        }

        val ackFrame = P2pProtocol.encodeFrame(
            P2pProtocol.FrameType.FILE_ACK,
            JSONObject().apply {
                put("file_id", fileId)
                put("success", verified)
                put("verified_sha256", actualSha256)
            }
        )
        out.write(ackFrame)
        out.flush()

        onFileCompleted(if (verified) finalFile else partFile, senderName, verified)
    }
}
