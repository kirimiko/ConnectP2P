package com.connectp2p.app.protocol

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ConnectP2P Wire Protocol (v1.0) - Byte-identical with `windows/protocol.py` and `protocol/schema.json`.
 *
 * Application-Layer Encryption:
 * - Headers & Inline Payloads: AES-256-GCM (96-bit random nonce + 128-bit authentication tag)
 * - File Streams (`FILE_DATA`): AES-256-CTR (128-bit random IV per stream) + SHA-256 verification
 */
object P2pProtocol {
    const val MAGIC_BYTE_0: Byte = 0x50 // 'P'
    const val MAGIC_BYTE_1: Byte = 0x32 // '2'
    const val ENC_HDR_BYTE_0: Byte = 0x45 // 'E'
    const val ENC_HDR_BYTE_1: Byte = 0x31 // '1'
    const val FIXED_PREFIX_LEN = 7

    private const val DEFAULT_APP_SECRET = "ConnectP2P-E2EE-AES256-Key-v1"
    private val secureRandom = SecureRandom()

    @Volatile
    var appAesKeyBytes: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(DEFAULT_APP_SECRET.toByteArray(StandardCharsets.UTF_8))
        private set

    fun setAppSecret(secret: String) {
        appAesKeyBytes = MessageDigest.getInstance("SHA-256")
            .digest(secret.trim().toByteArray(StandardCharsets.UTF_8))
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Encrypt bytes with AES-256-GCM (12-byte nonce, 128-bit tag).
     * Returns `Pair(nonce12B, ciphertextWith16BTag)`.
     */
    fun aesGcmEncrypt(plaintext: ByteArray, customNonce: ByteArray? = null): Pair<ByteArray, ByteArray> {
        val nonce = if (customNonce != null && customNonce.size == 12) {
            customNonce
        } else {
            ByteArray(12).also { secureRandom.nextBytes(it) }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(appAesKeyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val cipherWithTag = cipher.doFinal(plaintext)
        return nonce to cipherWithTag
    }

    /**
     * Decrypt and verify AES-256-GCM `ciphertextWith16BTag`.
     */
    fun aesGcmDecrypt(nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        require(nonce.size == 12 && ciphertextWithTag.size >= 16) { "Invalid AES-256-GCM parameters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(appAesKeyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Create an AES-256-CTR stream cipher (`AES/CTR/NoPadding`) with a 16-byte IV.
     */
    fun createAesCtrCipher(encryptMode: Boolean, iv16: ByteArray): Cipher {
        require(iv16.size == 16) { "AES-256-CTR IV must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(appAesKeyBytes, "AES")
        val ivSpec = IvParameterSpec(iv16)
        cipher.init(if (encryptMode) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher
    }

    fun generateRandomIv16(): ByteArray =
        ByteArray(16).also { secureRandom.nextBytes(it) }

    const val CONTROL_UDP_PORT = 45820
    const val DATA_TCP_PORT = 45821
    const val MULTICAST_GROUP = "239.255.77.88"

    const val ANDROID_GO_DEFAULT_IP = "192.168.49.1"
    const val WINDOWS_GO_DEFAULT_IP = "192.168.137.1"

    const val BLE_SERVICE_UUID = "0000c0de-0000-1000-8000-00805f9b34fb"
    const val BLE_CONTROL_CHAR_UUID = "0000c0df-0000-1000-8000-00805f9b34fb"
    const val BLE_CLIPBOARD_CHAR_UUID = "0000c0e0-0000-1000-8000-00805f9b34fb"

    const val MAX_INLINE_CLIPBOARD_BYTES = 16384 // <= 16KB syncs via low-power BLE/UDP without waking Wi-Fi P2P
    const val BLE_MTU_CHUNK_SIZE = 480
    const val SOCKET_BUFFER_SIZE = 262144 // 256 KB
    const val P2P_IDLE_TEARDOWN_MS = 15_000L // 15 seconds idle auto-teardown

    enum class FrameType(val code: Int) {
        HELLO(1),
        CLIPBOARD_SYNC(2),
        FILE_OFFER(3),
        FILE_ACCEPT(4),
        FILE_DATA(5),
        FILE_ACK(6),
        P2P_WAKE_REQUEST(7),
        P2P_GROUP_READY(8),
        P2P_TEARDOWN(9),
        PING_PONG(10),
        PAIR_REQUEST(11),
        PAIR_RESPONSE(12);

        companion object {
            fun fromCode(code: Int): FrameType =
                entries.firstOrNull { it.code == code }
                    ?: throw IllegalArgumentException("Unknown FrameType code: $code")
        }
    }

    enum class PowerState {
        DEEP_IDLE,         // Wi-Fi P2P OFF, WakeLock/WifiLock RELEASED, hardware-filtered BLE only
        P2P_HANDSHAKE,     // Spinning up Wi-Fi P2P Group or connecting via WifiP2pConfig
        P2P_BURST_ACTIVE   // High-speed TCP file transfer active; auto-teardown after 15s idle
    }

    data class DecodedFrame(
        val type: FrameType,
        val header: JSONObject,
        val payload: ByteArray
    )

    data class PeerInfo(
        val deviceId: String,
        var deviceName: String,
        var platform: String = "windows", // "android" | "windows"
        var ip: String? = null,
        var dataPort: Int = DATA_TCP_PORT,
        var p2pSsid: String? = null,
        var p2pPassphrase: String? = null,
        var p2pGoIp: String? = null,
        var p2pDeviceAddress: String? = null,
        /** Address of the BLE link to this peer (the PC connects to us as a GATT central). */
        var bleAddress: String? = null,
        var discoverySource: String = "lan", // "lan" | "p2p_scan" | "p2p_peer" | "ble"
        var isConnected: Boolean = false,
        var lastSeenMs: Long = System.currentTimeMillis()
    )

    /**
     * Derive a deterministic 12-char WPA2 passphrase from a ConnectP2P SSID (`DIRECT-CP-...`).
     * Byte-identical with `derive_p2p_passphrase()` in `windows/protocol.py`.
     */
    fun deriveP2pPassphrase(ssid: String): String {
        val normalized = ssid.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("ConnectP2P-P2PKey-v1:$normalized".toByteArray(StandardCharsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * Encode a friendly device name (including Chinese/Unicode) into a valid IEEE 802.11 SSID (<= 32 bytes)
     * that strictly satisfies Android WifiP2pConfig's `^DIRECT-[a-zA-Z0-9]{2}.*` requirement.
     * Byte-identical with `encode_p2p_ssid()` in `windows/protocol.py`.
     */
    fun encodeP2pSsid(deviceName: String): String {
        val cleaned = deviceName.trim().replace(" ", "-").ifEmpty { "Phone" }
        val isPureAsciiSafe = cleaned.all { ch ->
            (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-'
        }
        if (isPureAsciiSafe) {
            return "DIRECT-CP-N-${cleaned.take(20)}"
        }

        val outBytes = ArrayList<Byte>(10)
        for (ch in deviceName.trim()) {
            val chBytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
            if (outBytes.size + chBytes.size > 10) break
            for (b in chBytes) outBytes.add(b)
        }
        val hexPart = if (outBytes.isEmpty()) {
            "444556"
        } else {
            outBytes.joinToString("") { "%02X".format(it) }
        }
        return "DIRECT-CP-H-$hexPart"
    }

    /**
     * Decode a Wi-Fi Direct SSID into `(friendlyDeviceName, isConnectP2pAuto)`.
     */
    fun decodeP2pSsid(ssid: String): Pair<String, Boolean> {
        val s = ssid.trim()
        if (s.startsWith("DIRECT-CP-N-")) {
            val name = s.removePrefix("DIRECT-CP-N-").ifEmpty { s }
            return name to true
        }
        if (s.startsWith("DIRECT-CP-H-")) {
            val hex = s.removePrefix("DIRECT-CP-H-")
            return try {
                val bytes = ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val name = String(bytes, StandardCharsets.UTF_8).ifEmpty { s }
                name to true
            } catch (_: Exception) {
                s to true
            }
        }
        if (s.startsWith("DIRECT-")) {
            val parts = s.split("-", limit = 3)
            if (parts.size == 3 && parts[2].isNotEmpty()) {
                return parts[2] to false
            }
        }
        return s to false
    }

    fun computeClipHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun computeFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(SOCKET_BUFFER_SIZE)
        FileInputStream(file).use { fis ->
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun encryptHeaderJson(header: JSONObject): ByteArray {
        val rawBytes = header.toString().toByteArray(StandardCharsets.UTF_8)
        val (nonce, cipherWithTag) = aesGcmEncrypt(rawBytes)
        val out = ByteArray(2 + nonce.size + cipherWithTag.size)
        out[0] = ENC_HDR_BYTE_0
        out[1] = ENC_HDR_BYTE_1
        System.arraycopy(nonce, 0, out, 2, nonce.size)
        System.arraycopy(cipherWithTag, 0, out, 2 + nonce.size, cipherWithTag.size)
        return out
    }

    private fun decryptHeaderBytes(headerBytes: ByteArray): JSONObject {
        if (headerBytes.size >= 2 + 12 + 16 &&
            headerBytes[0] == ENC_HDR_BYTE_0 &&
            headerBytes[1] == ENC_HDR_BYTE_1
        ) {
            val nonce = headerBytes.copyOfRange(2, 14)
            val cipherWithTag = headerBytes.copyOfRange(14, headerBytes.size)
            val plainBytes = aesGcmDecrypt(nonce, cipherWithTag)
            return JSONObject(String(plainBytes, StandardCharsets.UTF_8))
        }
        return JSONObject(String(headerBytes, StandardCharsets.UTF_8))
    }

    fun decryptPayloadIfNeeded(header: JSONObject, wirePayload: ByteArray): ByteArray {
        val nonceHex = header.optString("enc_payload_nonce", "")
        if (nonceHex.isNotEmpty() && wirePayload.isNotEmpty()) {
            return aesGcmDecrypt(hexToBytes(nonceHex), wirePayload)
        }
        return wirePayload
    }

    fun encodeFrame(
        type: FrameType,
        header: JSONObject,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val wirePayload = if (payload.isNotEmpty()) {
            val (pNonce, encPayload) = aesGcmEncrypt(payload)
            header.put("enc_payload_nonce", bytesToHex(pNonce))
            encPayload
        } else {
            ByteArray(0)
        }
        header.put("payload_size", wirePayload.size)
        val headerBytes = encryptHeaderJson(header)
        val buf = ByteBuffer.allocate(FIXED_PREFIX_LEN + headerBytes.size + wirePayload.size)
        buf.put(MAGIC_BYTE_0)
        buf.put(MAGIC_BYTE_1)
        buf.put(type.code.toByte())
        buf.putInt(headerBytes.size)
        buf.put(headerBytes)
        if (wirePayload.isNotEmpty()) {
            buf.put(wirePayload)
        }
        return buf.array()
    }

    fun encodeStreamHeaderOnly(
        type: FrameType,
        header: JSONObject,
        streamPayloadSize: Long
    ): ByteArray {
        header.put("payload_size", streamPayloadSize)
        val headerBytes = encryptHeaderJson(header)
        val buf = ByteBuffer.allocate(FIXED_PREFIX_LEN + headerBytes.size)
        buf.put(MAGIC_BYTE_0)
        buf.put(MAGIC_BYTE_1)
        buf.put(type.code.toByte())
        buf.putInt(headerBytes.size)
        buf.put(headerBytes)
        return buf.array()
    }

    fun decodeFrameFromBytes(data: ByteArray, length: Int = data.size): DecodedFrame {
        require(length >= FIXED_PREFIX_LEN) { "Frame too short: $length" }
        val bb = ByteBuffer.wrap(data, 0, length)
        val m0 = bb.get()
        val m1 = bb.get()
        require(m0 == MAGIC_BYTE_0 && m1 == MAGIC_BYTE_1) { "Invalid magic bytes" }
        val typeCode = bb.get().toInt() and 0xFF
        val headerLen = bb.int
        require(headerLen in 0..(length - FIXED_PREFIX_LEN)) { "Invalid header length: $headerLen" }

        val headerBytes = ByteArray(headerLen)
        bb.get(headerBytes)
        val header = decryptHeaderBytes(headerBytes)
        val payloadSize = header.optInt("payload_size", 0)
        require(payloadSize >= 0 && FIXED_PREFIX_LEN + headerLen + payloadSize <= length) {
            "Truncated payload: expected $payloadSize"
        }
        val wirePayload = ByteArray(payloadSize)
        if (payloadSize > 0) {
            bb.get(wirePayload)
        }
        val plainPayload = decryptPayloadIfNeeded(header, wirePayload)
        header.put("payload_size", plainPayload.size)
        return DecodedFrame(FrameType.fromCode(typeCode), header, plainPayload)
    }

    fun readFrameHeaderFromStream(dis: DataInputStream): Pair<FrameType, JSONObject> {
        val m0 = dis.readByte()
        val m1 = dis.readByte()
        if (m0 != MAGIC_BYTE_0 || m1 != MAGIC_BYTE_1) {
            throw EOFException("Invalid magic bytes on TCP stream")
        }
        val typeCode = dis.readUnsignedByte()
        val headerLen = dis.readInt()
        require(headerLen in 1..(10 * 1024 * 1024)) { "Unreasonable header length: $headerLen" }
        val headerBytes = ByteArray(headerLen)
        dis.readFully(headerBytes)
        val header = decryptHeaderBytes(headerBytes)
        return FrameType.fromCode(typeCode) to header
    }

    /**
     * Split a control or micro-clipboard frame into BLE MTU chunks.
     * Chunk header: [msgId(1B)][chunkIdx(1B)][totalChunks(1B)][flags(1B)]
     */
    fun chunkBlePayload(frameBytes: ByteArray, mtuChunk: Int = BLE_MTU_CHUNK_SIZE): List<ByteArray> {
        val msgId = (UUID.randomUUID().leastSignificantBits and 0xFF).toByte()
        val usable = (mtuChunk - 4).coerceAtLeast(20)
        val total = ((frameBytes.size + usable - 1) / usable).coerceAtLeast(1)
        require(total <= 255) { "Payload too large for BLE micro-channel" }
        return (0 until total).map { idx ->
            val start = idx * usable
            val end = (start + usable).coerceAtMost(frameBytes.size)
            val partLen = end - start
            val out = ByteArray(4 + partLen)
            out[0] = msgId
            out[1] = idx.toByte()
            out[2] = total.toByte()
            out[3] = 0
            System.arraycopy(frameBytes, start, out, 4, partLen)
            out
        }
    }
}
