package com.connectp2p.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.connectp2p.app.ble.BleLowPowerChannel
import com.connectp2p.app.clipboard.ClipboardSyncActivity
import com.connectp2p.app.clipboard.ClipboardSyncHelper
import com.connectp2p.app.p2p.HotspotLinkManager
import com.connectp2p.app.p2p.P2pTransportEngine
import com.connectp2p.app.p2p.WifiP2pBurstManager
import com.connectp2p.app.power.PowerPolicyManager
import com.connectp2p.app.protocol.P2pProtocol
import com.connectp2p.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class P2pTransferService : Service() {

    companion object {
        private const val TAG = "ConnectP2P.Service"
        private const val CHANNEL_ID = "connect_p2p_burst_channel"
        private const val NOTIFICATION_ID = 45821
        private const val PAIR_NOTIFICATION_ID = 45825
        private const val PREFS_NAME = "connect_p2p_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TRUSTED_PEERS = "trusted_peers"
        private const val KEY_PEER_HOTSPOTS = "peer_hotspots"
        private const val HOTSPOT_RETRY_DELAY_MS = 8_000L

        const val ACTION_PUSH_CLIPBOARD_NOW = "com.connectp2p.app.action.PUSH_CLIPBOARD_NOW"
        const val ACTION_DISCONNECT_PEER = "com.connectp2p.app.action.DISCONNECT_PEER"
        const val ACTION_RESPOND_PAIR_REQUEST = "com.connectp2p.app.action.RESPOND_PAIR_REQUEST"
        const val ACTION_START_BURST_GO = "com.connectp2p.app.action.START_BURST_GO"
        const val ACTION_TEARDOWN_P2P = "com.connectp2p.app.action.TEARDOWN_P2P"

        const val EXTRA_CLIPBOARD_TEXT = "extra_clipboard_text"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PAIR_ACCEPTED = "extra_pair_accepted"
    }

    inner class LocalBinder : Binder() {
        fun getService(): P2pTransferService = this@P2pTransferService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var deviceId: String
        private set
    @Volatile
    var deviceName: String = ""
        private set

    lateinit var powerPolicy: PowerPolicyManager
        private set
    lateinit var wifiP2pManager: WifiP2pBurstManager
        private set
    lateinit var bleChannel: BleLowPowerChannel
        private set
    lateinit var transportEngine: P2pTransportEngine
        private set
    lateinit var clipboardHelper: ClipboardSyncHelper
        private set
    lateinit var hotspotLink: HotspotLinkManager
        private set

    private val discoveredPeers = ConcurrentHashMap<String, P2pProtocol.PeerInfo>()
    private val trustedPeers = ConcurrentHashMap.newKeySet<String>()

    /** deviceName -> (hotspot SSID, passphrase) for trusted PCs, so they can be reached from any network. */
    private val peerHotspots = ConcurrentHashMap<String, Pair<String, String>>()

    @Volatile
    private var lastHotspotAttemptMs: Long = 0L

    @Volatile
    private var lastHotspotAttemptManual: Boolean = false

    /** SSIDs whose last request was rejected/unavailable; not auto-retried until the user taps connect. */
    private val hotspotAutoRetryBlocked = ConcurrentHashMap.newKeySet<String>()

    private val livenessMisses = ConcurrentHashMap<String, Int>()

    /**
     * SSID -> time until which auto-connect is suppressed. Set when the hotspot was up but the PC app did
     * not answer (crashed/killed app leaves Windows' hotspot running), otherwise the phone would keep
     * hopping between its own Wi-Fi and a dead hotspot.
     */
    private val hotspotSuppressedUntil = ConcurrentHashMap<String, Long>()

    /** (destination IP, clip hash, sent at ms) of the last clipboard push. */
    @Volatile
    private var lastClipSend: Triple<String, String, Long>? = null
    private val manuallyDisconnectedPeerIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var connectedPeer: P2pProtocol.PeerInfo? = null
        private set

    @Volatile
    private var pendingP2pPeer: P2pProtocol.PeerInfo? = null

    @Volatile
    var isManuallyDisconnected: Boolean = false
        private set

    @Volatile
    var targetPeerIp: String = P2pProtocol.ANDROID_GO_DEFAULT_IP

    var uiCallback: ServiceUiCallback? = null

    interface ServiceUiCallback {
        fun onLog(message: String)
        fun onPowerStateChanged(state: P2pProtocol.PowerState)
        fun onP2pGroupCredentialsReady(ssid: String, passphrase: String, goIp: String)
        fun onPeersUpdated(peers: List<P2pProtocol.PeerInfo>)
        fun onConnectedPeerChanged(peer: P2pProtocol.PeerInfo?)
        fun onTransferProgress(fileName: String, transferred: Long, total: Long, isSending: Boolean)
        fun onPairRequestReceived(peer: P2pProtocol.PeerInfo)
    }

    override fun onCreate() {
        super.onCreate()
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0000"
        deviceId = "and-$androidId"

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultName = (Build.MODEL ?: "${Build.MANUFACTURER}-Phone").trim()
        deviceName = prefs.getString(KEY_DEVICE_NAME, defaultName)?.takeIf { it.isNotBlank() } ?: defaultName
        prefs.getStringSet(KEY_TRUSTED_PEERS, emptySet())?.let { trustedPeers.addAll(it) }

        createNotificationChannel()

        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val downloadDir = File(publicDownloads, "ConnectP2P").let { pubDir ->
            if (pubDir.exists() || pubDir.mkdirs()) {
                pubDir
            } else {
                File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "ConnectP2P")
            }
        }

        powerPolicy = PowerPolicyManager(
            context = this,
            idleTimeoutMs = P2pProtocol.P2P_IDLE_TEARDOWN_MS,
            onStateChanged = { newState ->
                handlePowerStateTransition(newState)
            },
            onScreenStateChanged = { interactive ->
                bleChannel.onScreenInteractiveChanged(interactive)
            },
            onIdleTeardown = {
                val hasActiveP2pPeer = discoveredPeers.values.any {
                    it.isConnected && (it.ip?.startsWith("192.168.137.") == true || it.ip?.startsWith("192.168.49.") == true)
                }
                if (hasActiveP2pPeer) {
                    emitLog("[空闲自动降功耗] 文件传输已完成，已释放全部唤醒锁并回归 DEEP_IDLE 超低功耗监听态")
                } else {
                    emitLog("[空闲自动降功耗] 热点无活动连接，已自动拆除 Wi-Fi P2P 热点并释放唤醒锁")
                    wifiP2pManager.teardownGroup()
                }
            }
        )

        wifiP2pManager = WifiP2pBurstManager(
            context = this,
            onGroupReady = { ssid, pass, goIp ->
                emitLog("[本机直连热点已开启] 设备名=\"$deviceName\" | 对端点击『扫描周围设备』即可一键免密连接")
                uiCallback?.onP2pGroupCredentialsReady(ssid, pass, goIp)
                broadcastP2pGroupReady(ssid, pass, goIp)
                announcePresenceHello()
            },
            onConnectedToGo = { goIp, isGo ->
                handleLinkEstablished(goIp, isGo)
            },
            onPeersDiscovered = { p2pDevices ->
                for (dev in p2pDevices) {
                    val addr = dev.deviceAddress ?: continue
                    val rawName = dev.deviceName?.takeIf { it.isNotBlank() } ?: addr
                    val pType = dev.primaryDeviceType ?: ""
                    // Filter out Wi-Fi Direct printers (category 3 = Printer) and Pantum/HP/Canon/Epson printers
                    if (pType.startsWith("3-") ||
                        rawName.contains("Pantum", ignoreCase = true) ||
                        rawName.contains("LaserJet", ignoreCase = true) ||
                        rawName.contains("Printer", ignoreCase = true) ||
                        rawName.contains("DIRECT-", ignoreCase = false) && !rawName.startsWith("DIRECT-CP-")
                    ) {
                        continue
                    }
                    // Primary category 1 = Computer (Windows PC)!
                    val isWinPc = pType.startsWith("1-") || rawName.contains("WIN", ignoreCase = true) || rawName.contains("PC", ignoreCase = true)
                    // Ignore passive Windows PC system P2P MAC entries that are not ConnectP2P hotspots (`DIRECT-CP-*`),
                    // because Windows ConnectP2P hotspots are discovered via `DIRECT-CP-*` scan or `HELLO` on 192.168.137.1.
                    if (isWinPc && !rawName.startsWith("DIRECT-CP-")) {
                        continue
                    }
                    val (friendlyName, isAuto) = P2pProtocol.decodeP2pSsid(rawName)
                    val peer = P2pProtocol.PeerInfo(
                        deviceId = "p2pdev-$addr",
                        deviceName = friendlyName,
                        platform = if (isWinPc) "windows" else "android",
                        ip = if (isWinPc) P2pProtocol.WINDOWS_GO_DEFAULT_IP else P2pProtocol.ANDROID_GO_DEFAULT_IP,
                        p2pDeviceAddress = addr,
                        p2pSsid = if (rawName.startsWith("DIRECT-")) rawName else null,
                        p2pPassphrase = if (isAuto) P2pProtocol.deriveP2pPassphrase(rawName) else null,
                        p2pGoIp = if (isWinPc) P2pProtocol.WINDOWS_GO_DEFAULT_IP else P2pProtocol.ANDROID_GO_DEFAULT_IP,
                        discoverySource = "p2p_peer"
                    )
                    upsertPeer(peer)
                    if (!isManuallyDisconnected && !isPeerManuallyDisconnected(peer) &&
                        isTrustedPeer(peer) && wifiP2pManager.canAutoAttemptConnect(peer.p2pSsid)
                    ) {
                        emitLog("[自动重连] 发现已信任设备 ${peer.deviceName}，正在自动连接...")
                        connectToPeerAndSync(peer, isManualUserClick = false)
                    }
                }
            },
            onDirectHotspotsScanned = { hotspots ->
                for (scanned in hotspots) {
                    upsertPeer(scanned)
                    val hp = storedPeerFor(scanned)
                    val ssid = scanned.p2pSsid ?: continue
                    val isPcHotspot = scanned.p2pGoIp?.startsWith("192.168.137.") == true
                    if (isTrustedPeer(hp) && isPcHotspot) {
                        rememberPeerHotspot(hp.deviceName, ssid, scanned.p2pPassphrase ?: P2pProtocol.deriveP2pPassphrase(ssid))
                    }
                    val canTry = if (isPcHotspot) canAutoConnectHotspot(ssid) else wifiP2pManager.canAutoAttemptConnect(ssid)
                    if (!isManuallyDisconnected && !isPeerManuallyDisconnected(hp) && isTrustedPeer(hp) && canTry) {
                        emitLog("[自动重连] 发现已信任热点 ${hp.deviceName} (${hp.p2pSsid})，正在自动免密连接...")
                        connectToPeerAndSync(hp, isManualUserClick = false)
                    }
                }
            },
            onDisconnected = {
                emitLog("[P2P已断开] Wi-Fi 芯片已回归 DEEP_IDLE 超低功耗待机态")
                powerPolicy.forceDeepIdleNow()
                serviceScope.launch {
                    verifyConnectedPeersLiveness(forceProbe = true)
                }
            },
            onLogMessage = { msg ->
                emitLog(msg)
            }
        )

        bleChannel = BleLowPowerChannel(
            context = this,
            onControlFrameReceived = { frame, senderAddr ->
                handleControlFrame(frame, senderAddr)
            }
        )

        transportEngine = P2pTransportEngine(
            deviceId = deviceId,
            deviceName = deviceName,
            downloadDir = downloadDir,
            powerPolicy = powerPolicy,
            onControlFrameReceived = { frame, senderIp ->
                handleControlFrame(frame, senderIp)
            },
            onClipboardReceived = { text, clipHash, senderName ->
                if (!isManuallyDisconnected && !manuallyDisconnectedPeerIds.contains("name:$senderName")) {
                    val applied = clipboardHelper.applyRemoteClipboard(text, clipHash)
                    if (applied) {
                        emitLog("[收到剪贴板 ← $senderName] \"${text.take(36)}\"")
                    }
                }
            },
            onFileProgress = { name, transferred, total, isSending ->
                uiCallback?.onTransferProgress(name, transferred, total, isSending)
            },
            onFileCompleted = { file, senderName, verified ->
                if (verified) {
                    try {
                        MediaScannerConnection.scanFile(
                            this,
                            arrayOf(file.absolutePath),
                            null,
                            null
                        )
                    } catch (_: Throwable) {
                    }
                    emitLog("[文件接收完成 ← $senderName] 已保存至 Download/ConnectP2P/${file.name} (AES-256解密 & SHA-256校验通过)")
                } else {
                    emitLog("[文件校验失败 ← $senderName] ${file.name} 已丢弃")
                }
            },
            isSenderAuthorized = { sId, sName, sIp ->
                isSenderAuthorized(sId, sName, sIp)
            }
        )

        clipboardHelper = ClipboardSyncHelper(
            context = this,
            onLocalClipboardChanged = { text, clipHash ->
                syncClipboardSmart(text, clipHash)
            }
        )

        hotspotLink = HotspotLinkManager(
            context = this,
            onLinkUp = { ssid, gatewayIp ->
                hotspotAutoRetryBlocked.remove(ssid)
                emitLog("[电脑热点已接通] $ssid，正在与电脑 $gatewayIp 握手...")
                handleLinkEstablished(gatewayIp, isGo = false)
            },
            onLinkDown = { ssid, wasUp -> handleHotspotLinkDown(ssid, wasUp) },
            onLogMessage = { emitLog(it) }
        )
        transportEngine.networkResolver = { ip -> hotspotLink.networkFor(ip) }
        wifiP2pManager.removeLegacyNetworkSuggestions()
        loadPeerHotspots()

        powerPolicy.startMonitoringScreenState()
        wifiP2pManager.initialize()
        transportEngine.start()
        bleChannel.startLowPowerChannel()
        clipboardHelper.start()
        registerNetworkCallback()
        startConnectedPeersLivenessLoop()

        announcePresenceHello()
        emitLog("[DEEP_IDLE] 服务已就绪 (AES-256 端到端加密已启用 | 接收目录: 内部存储/Download/ConnectP2P)")
    }

    /**
     * A Wi-Fi link to the peer exists (phone-to-phone Wi-Fi Direct group, or the PC hotspot). The peer is
     * only marked connected once it answers (HELLO reply / PAIR_RESPONSE), not when the radio link comes up.
     */
    private fun handleLinkEstablished(goIp: String, isGo: Boolean) {
        emitLog("[Wi-Fi直连已建立] 对端 IP=$goIp (${if (isGo) "本机为热点" else "已连上对端热点"})")
        powerPolicy.touchBurstActivity(timeoutMs = 120_000L)
        if (isGo) {
            announcePresenceHello()
            return
        }
        targetPeerIp = goIp
        announcePresenceHello(targetIp = goIp)
        val pending = pendingP2pPeer
        val awaitingApproval = pending != null && !isTrustedPeer(pending)
        if (awaitingApproval) {
            emitLog("[正在请求授权] Wi-Fi直连已接通，正在请求 ${pending!!.deviceName} ($goIp) 同意连接...")
        }
        sendPairRequestFrame(goIp, null, P2pProtocol.DATA_TCP_PORT)

        if (hotspotLink.isUp && !awaitingApproval) {
            serviceScope.launch {
                delay(10_000L)
                val anyAnswered = discoveredPeers.values.any { p -> p.isConnected && p.ip?.let { hotspotLink.networkFor(it) } != null }
                if (hotspotLink.isUp && !anyAnswered) releaseUnresponsiveHotspot()
            }
        }
    }

    private fun releaseUnresponsiveHotspot() {
        val ssid = hotspotLink.ssid ?: return
        hotspotSuppressedUntil[ssid] = System.currentTimeMillis() + 5 * 60_000L
        emitLog("[连接状态更新] 电脑热点在线但电脑端 App 无响应，已断开热点、手机恢复原网络（5 分钟内不自动重连，可手动点『连接』）")
        hotspotLink.disconnect()
        transportEngine.releaseNetworkSockets()
    }

    private fun handleHotspotLinkDown(ssid: String, wasUp: Boolean) {
        transportEngine.releaseNetworkSockets()
        var changed = false
        for (p in discoveredPeers.values) {
            if (p.isConnected && p.ip?.startsWith("192.168.137.") == true) {
                p.isConnected = false
                changed = true
            }
        }
        if (connectedPeer?.ip?.startsWith("192.168.137.") == true) {
            connectedPeer = discoveredPeers.values.firstOrNull { it.isConnected }
            connectedPeer?.ip?.let { targetPeerIp = it }
            changed = true
        }
        if (changed) {
            if (connectedPeer == null) powerPolicy.forceDeepIdleNow()
            updateConnectionNotification()
            serviceScope.launch {
                uiCallback?.onConnectedPeerChanged(connectedPeer)
                uiCallback?.onPeersUpdated(getPeerList())
            }
        }
        if (!wasUp) {
            // An automatic attempt can lose the race with the phone re-joining its own Wi-Fi ("scan busy");
            // retry it once. Manual attempts are not retried: the user may have cancelled the system dialog.
            if (!lastHotspotAttemptManual && !hotspotAutoRetryBlocked.contains(ssid) && !isManuallyDisconnected) {
                hotspotAutoRetryBlocked.add(ssid)
                serviceScope.launch {
                    delay(5_000L)
                    val cred = peerHotspots.values.firstOrNull { it.first == ssid } ?: return@launch
                    if (connectedPeer == null && hotspotLink.state == HotspotLinkManager.State.IDLE && !isManuallyDisconnected) {
                        emitLog("[自动重连] 再次尝试接入 $ssid...")
                        connectToPcHotspot(cred.first, cred.second, P2pProtocol.WINDOWS_GO_DEFAULT_IP, isManualUserClick = false)
                    }
                }
            } else {
                hotspotAutoRetryBlocked.add(ssid)
            }
        } else if (!isManuallyDisconnected) {
            serviceScope.launch {
                delay(HOTSPOT_RETRY_DELAY_MS)
                tryAutoConnectTrustedHotspot()
            }
        }
    }

    /**
     * @param visibleInScan the SSID was just seen in a Wi-Fi scan, so an earlier "unavailable" result is stale.
     */
    private fun canAutoConnectHotspot(ssid: String, visibleInScan: Boolean = true): Boolean {
        if (hotspotLink.state != HotspotLinkManager.State.IDLE) return false
        if (connectedPeer != null) return false
        if (System.currentTimeMillis() < (hotspotSuppressedUntil[ssid] ?: 0L)) return false
        val cooldown = if (visibleInScan) 30_000L else 15_000L
        if (System.currentTimeMillis() - lastHotspotAttemptMs < cooldown) return false
        if (!visibleInScan && hotspotAutoRetryBlocked.contains(ssid)) return false
        return true
    }

    /** Only effective while the app is in the foreground: Android ignores specifier requests from background apps. */
    private fun tryAutoConnectTrustedHotspot() {
        if (isManuallyDisconnected || connectedPeer != null) return
        for ((name, cred) in peerHotspots) {
            if (manuallyDisconnectedPeerIds.contains("name:$name")) continue
            if (!canAutoConnectHotspot(cred.first, visibleInScan = false)) continue
            emitLog("[自动重连] 正在接入已信任电脑 $name 的热点 ${cred.first}...")
            pendingP2pPeer = discoveredPeers.values.firstOrNull { it.deviceName == name }
            connectToPcHotspot(cred.first, cred.second, P2pProtocol.WINDOWS_GO_DEFAULT_IP, isManualUserClick = false)
            return
        }
    }

    private fun connectToPcHotspot(ssid: String, passphrase: String, gatewayIp: String, isManualUserClick: Boolean) {
        if (isManualUserClick) {
            hotspotAutoRetryBlocked.remove(ssid)
            hotspotSuppressedUntil.remove(ssid)
        }
        lastHotspotAttemptManual = isManualUserClick
        lastHotspotAttemptMs = System.currentTimeMillis()
        isManuallyDisconnected = false
        powerPolicy.onHandshakeStarted(handshakeTimeoutMs = 120_000L)
        if (!hotspotLink.isUp && wifiP2pManager.isSubnetAlreadyReachable(gatewayIp)) {
            handleLinkEstablished(gatewayIp, isGo = false)
            return
        }
        hotspotLink.connect(ssid, passphrase, gatewayIp)
    }

    private fun rememberPeerHotspot(name: String, ssid: String, passphrase: String) {
        if (name.isBlank() || !ssid.startsWith("DIRECT-CP-")) return
        if (peerHotspots[name] == Pair(ssid, passphrase)) return
        peerHotspots[name] = Pair(ssid, passphrase)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PEER_HOTSPOTS, peerHotspots.map { (n, c) -> "$n\t${c.first}\t${c.second}" }.toSet())
            .apply()
    }

    /** Restores trusted PCs' hotspots and lists them, so they can be connected even when not currently discovered. */
    private fun loadPeerHotspots() {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_PEER_HOTSPOTS, emptySet()) ?: emptySet()
        for (entry in raw) {
            val parts = entry.split("\t")
            if (parts.size != 3) continue
            val (name, ssid, pass) = parts
            if (!trustedPeers.contains("name:$name")) continue
            peerHotspots[name] = Pair(ssid, pass)
            if (discoveredPeers.values.none { it.deviceName == name }) {
                discoveredPeers["p2p-$ssid"] = P2pProtocol.PeerInfo(
                    deviceId = "p2p-$ssid",
                    deviceName = name,
                    platform = "windows",
                    ip = P2pProtocol.WINDOWS_GO_DEFAULT_IP,
                    p2pSsid = ssid,
                    p2pPassphrase = pass,
                    p2pGoIp = P2pProtocol.WINDOWS_GO_DEFAULT_IP,
                    discoverySource = "p2p_scan",
                    lastSeenMs = 0L
                )
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            serviceScope.launch {
                delay(400L)
                announcePresenceHello()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            serviceScope.launch {
                delay(300L)
                verifyConnectedPeersLiveness(forceProbe = true)
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
    }

    private fun startConnectedPeersLivenessLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5000L)
                if (powerPolicy.isScreenInteractive && discoveredPeers.values.any { it.isConnected }) {
                    verifyConnectedPeersLiveness(forceProbe = false)
                }
            }
        }
    }

    /**
     * Check if currently connected peers are still reachable via TCP `PING_PONG`.
     * Immediately updates UI and notification to disconnected if a peer went offline!
     */
    suspend fun verifyConnectedPeersLiveness(forceProbe: Boolean = false) {
        val now = System.currentTimeMillis()
        var statusChanged = false
        for (peer in discoveredPeers.values) {
            if (!peer.isConnected) continue
            val ip = peer.ip
            if (ip.isNullOrBlank()) continue
            if (!forceProbe && (now - peer.lastSeenMs) < 5000L) continue

            val alive = transportEngine.probePeerAlive(ip, peer.dataPort, timeoutMs = 1500)
            if (alive) {
                peer.lastSeenMs = System.currentTimeMillis()
                livenessMisses.remove(peer.deviceId)
            } else {
                val misses = (livenessMisses[peer.deviceId] ?: 0) + 1
                livenessMisses[peer.deviceId] = misses
                if (!forceProbe && misses < 2) continue
                livenessMisses.remove(peer.deviceId)
                peer.isConnected = false
                statusChanged = true
                emitLog("[连接状态更新] 检测到与 ${peer.deviceName} ($ip) 的连接已断开/离线")
                if (connectedPeer?.deviceId == peer.deviceId) {
                    connectedPeer = discoveredPeers.values.firstOrNull { it.isConnected && it.deviceId != peer.deviceId }
                    connectedPeer?.ip?.let { targetPeerIp = it }
                }
            }
        }
        if (statusChanged) {
            if (connectedPeer == null) {
                powerPolicy.forceDeepIdleNow()
            }
            // PC app gone but its hotspot still up (crash/kill): don't keep the phone parked off its own Wi-Fi.
            if (hotspotLink.isUp && discoveredPeers.values.none { it.isConnected && it.ip?.let { ip -> hotspotLink.networkFor(ip) } != null }) {
                releaseUnresponsiveHotspot()
            }
            updateConnectionNotification()
            serviceScope.launch(Dispatchers.Main) {
                uiCallback?.onConnectedPeerChanged(connectedPeer)
                uiCallback?.onPeersUpdated(getPeerList())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUSH_CLIPBOARD_NOW -> {
                val explicitText = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT)
                if (!explicitText.isNullOrEmpty()) {
                    clipboardHelper.applyRemoteClipboard(explicitText)
                    syncClipboardSmart(explicitText)
                } else {
                    clipboardHelper.inspectAndNotifyIfChanged(forceNotify = true)
                }
            }
            ACTION_DISCONNECT_PEER -> {
                val peerId = intent.getStringExtra(EXTRA_PEER_ID)
                if (!peerId.isNullOrBlank()) {
                    disconnectPeer(peerId)
                } else {
                    disconnectCurrentPeer()
                }
            }
            ACTION_RESPOND_PAIR_REQUEST -> {
                val peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: ""
                val accepted = intent.getBooleanExtra(EXTRA_PAIR_ACCEPTED, false)
                if (peerId.isNotEmpty()) {
                    respondToPairRequest(peerId, accepted)
                }
            }
            ACTION_START_BURST_GO -> {
                startBurstGroupOnDemand()
            }
            ACTION_TEARDOWN_P2P -> {
                teardownBurstGroupNow()
            }
        }
        return START_NOT_STICKY // Do not needlessly restart in background when idle
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        for (p in discoveredPeers.values) {
            if (p.isConnected) {
                sendP2pTeardownSignal(targetPeer = p)
            }
        }
        unregisterNetworkCallback()
        clipboardHelper.stop()
        bleChannel.stopLowPowerChannel()
        hotspotLink.disconnect()
        transportEngine.releaseNetworkSockets()
        transportEngine.stop()
        wifiP2pManager.shutdown()
        powerPolicy.stopMonitoringScreenState()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun getPeerList(): List<P2pProtocol.PeerInfo> =
        discoveredPeers.values.sortedWith(
            compareByDescending<P2pProtocol.PeerInfo> { it.deviceId == connectedPeer?.deviceId }
                .thenByDescending { it.isConnected }
                .thenByDescending { it.lastSeenMs }
        )

    fun isPeerManuallyDisconnected(peer: P2pProtocol.PeerInfo): Boolean {
        return manuallyDisconnectedPeerIds.contains(peer.deviceId) ||
            (peer.deviceName.isNotBlank() && manuallyDisconnectedPeerIds.contains("name:${peer.deviceName}"))
    }

    fun isSenderAuthorized(senderId: String, senderName: String, senderIp: String): Boolean {
        if (isManuallyDisconnected) return false
        if (senderId.isNotEmpty() && manuallyDisconnectedPeerIds.contains(senderId)) return false
        if (senderName.isNotBlank() && manuallyDisconnectedPeerIds.contains("name:$senderName")) return false
        if (senderId.isNotEmpty() && trustedPeers.contains(senderId)) return true
        if (senderName.isNotBlank() && trustedPeers.contains("name:$senderName")) return true
        val peer = if (senderId.isNotEmpty()) discoveredPeers[senderId] else null
        if (peer != null && peer.isConnected) return true
        val cur = connectedPeer
        if (cur != null && ((senderId.isNotEmpty() && cur.deviceId == senderId) || (senderIp.isNotEmpty() && cur.ip == senderIp))) {
            return true
        }
        emitLog("[安全拦截] 已拦截未授权陌生设备 \"$senderName\" ($senderIp) 的数据请求，请先同意连接")
        return false
    }

    fun updateDeviceName(newName: String) {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return
        deviceName = cleaned
        transportEngine.deviceName = cleaned
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_NAME, cleaned)
            .apply()
        announcePresenceHello()
        emitLog("[名称已保存] 本机设备名称已设为 \"$cleaned\"")
    }

    /**
     * Scan nearby devices via:
     * 1. UDP broadcast/multicast `HELLO` across all active network interfaces
     * 2. BLE low-power scan & advertisement
     * 3. Wi-Fi Direct `discoverPeers` + Wi-Fi `DIRECT-*` hotspot scan
     */
    fun scanNearbyDevices() {
        emitLog("[正在扫描周围设备] 正在探测附近电脑与手机 (无需手动输入SSID)...")
        announcePresenceHello()
        wifiP2pManager.discoverPeersSingleShot()
        serviceScope.launch(Dispatchers.IO) {
            verifyConnectedPeersLiveness(forceProbe = true)
        }
        uiCallback?.onPeersUpdated(getPeerList())
        // Give LAN discovery a moment; if no trusted peer answered, reach a trusted PC through its hotspot.
        serviceScope.launch {
            delay(2_500L)
            tryAutoConnectTrustedHotspot()
        }
    }

    fun isTrustedPeer(peer: P2pProtocol.PeerInfo): Boolean {
        return trustedPeers.contains(peer.deviceId) ||
            (peer.deviceName.isNotBlank() && trustedPeers.contains("name:${peer.deviceName}"))
    }

    private fun rememberTrustedPeer(peer: P2pProtocol.PeerInfo) {
        if (!peer.deviceId.startsWith("p2p-")) {
            trustedPeers.add(peer.deviceId)
        }
        if (peer.deviceName.isNotBlank()) {
            trustedPeers.add("name:${peer.deviceName}")
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_TRUSTED_PEERS, HashSet(trustedPeers))
            .apply()
    }

    /**
     * Remove a peer from the trusted list and disconnect it so next connection requires manual approval.
     */
    fun forgetPeerTrust(peerId: String) {
        val peer = discoveredPeers[peerId]
        trustedPeers.remove(peerId)
        if (peer != null && peer.deviceName.isNotBlank()) {
            trustedPeers.remove("name:${peer.deviceName}")
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_TRUSTED_PEERS, HashSet(trustedPeers))
            .apply()
        disconnectPeer(peerId)
        emitLog("[已取消信任] 已将 \"${peer?.deviceName ?: peerId}\" 移出信任列表，下次连接需手动同意")
    }

    /**
     * Set a specific connected device as the current active target for file & clipboard sending.
     */
    fun selectTargetPeer(peer: P2pProtocol.PeerInfo) {
        if (!peer.isConnected) {
            connectToPeerAndSync(peer)
            return
        }
        connectedPeer = peer
        peer.ip?.let { targetPeerIp = it }
        updateConnectionNotification()
        serviceScope.launch {
            uiCallback?.onConnectedPeerChanged(peer)
            uiCallback?.onPeersUpdated(getPeerList())
        }
        emitLog("[已切换发送目标] 当前指定目标设备: ${peer.deviceName} (${peer.ip ?: "直连"})")
    }

    /**
     * Connect to a discovered peer:
     * - If the peer is a STRANGER (`!isTrustedPeer`): sends `PAIR_REQUEST` and waits for the
     *   remote user to manually click "Approve" on their device!
     * - If the peer is ALREADY TRUSTED (`★已信任`): connects immediately and syncs clipboard!
     */
    fun connectToPeerAndSync(peer: P2pProtocol.PeerInfo, isManualUserClick: Boolean = true) {
        isManuallyDisconnected = false
        manuallyDisconnectedPeerIds.remove(peer.deviceId)
        if (peer.deviceName.isNotBlank()) {
            manuallyDisconnectedPeerIds.remove("name:${peer.deviceName}")
            discoveredPeers.values.filter { it.deviceName == peer.deviceName }.forEach { manuallyDisconnectedPeerIds.remove(it.deviceId) }
        }

        when {
            peer.discoverySource == "p2p_scan" && !peer.p2pSsid.isNullOrEmpty() -> {
                pendingP2pPeer = peer
                val ssid = peer.p2pSsid!!
                val pass = peer.p2pPassphrase ?: P2pProtocol.deriveP2pPassphrase(ssid)
                val goIp = peer.p2pGoIp ?: peer.ip ?: P2pProtocol.WINDOWS_GO_DEFAULT_IP
                emitLog("[一键免密直连] 正在连接 ${peer.deviceName} ($ssid)...")
                connectToPeerBurstGroup(ssid, pass, goIp, isManualUserClick = isManualUserClick)
            }
            peer.discoverySource == "p2p_peer" && !peer.p2pDeviceAddress.isNullOrEmpty() -> {
                pendingP2pPeer = peer
                if (!peer.p2pSsid.isNullOrEmpty()) {
                    val ssid = peer.p2pSsid!!
                    val pass = peer.p2pPassphrase ?: P2pProtocol.deriveP2pPassphrase(ssid)
                    val goIp = peer.p2pGoIp ?: peer.ip ?: P2pProtocol.ANDROID_GO_DEFAULT_IP
                    connectToPeerBurstGroup(ssid, pass, goIp, isManualUserClick = isManualUserClick)
                } else {
                    emitLog("[一键P2P连接] 正在连接附近设备 ${peer.deviceName}...")
                    powerPolicy.onHandshakeStarted()
                    wifiP2pManager.connectToPeerDevice(peer.p2pDeviceAddress!!, isManualUserClick = isManualUserClick)
                    requestPeerToWakeP2p(peer.ip ?: P2pProtocol.ANDROID_GO_DEFAULT_IP, peer.deviceId)
                }
            }
            else -> {
                val ip = peer.ip ?: P2pProtocol.ANDROID_GO_DEFAULT_IP
                targetPeerIp = ip
                if (!isTrustedPeer(peer)) {
                    requestPairing(peer)
                    return
                }
                emitLog("[正在连接] ★${peer.deviceName} ($ip)...")
                serviceScope.launch {
                    if (transportEngine.probePeerAlive(ip, peer.dataPort, timeoutMs = 1500)) {
                        markPeerConnected(peer, ip)
                        sendPairRequestFrame(ip, peer.deviceId, peer.dataPort)
                        announcePresenceHello(targetIp = ip)
                        return@launch
                    }
                    val hotspot = peerHotspots[peer.deviceName]
                        ?: peer.p2pSsid?.takeIf { it.startsWith("DIRECT-CP-") }?.let {
                            Pair(it, peer.p2pPassphrase ?: P2pProtocol.deriveP2pPassphrase(it))
                        }
                    if (hotspot != null) {
                        emitLog("[正在连接] ${peer.deviceName} 在当前网络不可达，改为接入其热点 ${hotspot.first}...")
                        pendingP2pPeer = peer
                        connectToPcHotspot(hotspot.first, hotspot.second, peer.p2pGoIp ?: P2pProtocol.WINDOWS_GO_DEFAULT_IP, isManualUserClick)
                    } else {
                        emitLog("[连接失败] ${peer.deviceName} ($ip) 无响应：请确认对方 App 已打开且双方在同一网络，或在电脑端开启热点")
                    }
                }
            }
        }
    }

    private fun markPeerConnected(peer: P2pProtocol.PeerInfo, ip: String) {
        peer.isConnected = true
        peer.ip = ip
        peer.lastSeenMs = System.currentTimeMillis()
        rememberTrustedPeer(peer)
        connectedPeer = peer
        targetPeerIp = ip
        updateConnectionNotification()
        uiCallback?.onConnectedPeerChanged(peer)
        uiCallback?.onPeersUpdated(getPeerList())
        emitLog("[已连接] ★${peer.deviceName} ($ip)")
    }

    /**
     * Send `PAIR_REQUEST` to an untrusted stranger device and wait for remote manual approval.
     */
    fun requestPairing(peer: P2pProtocol.PeerInfo) {
        val ip = peer.ip ?: P2pProtocol.ANDROID_GO_DEFAULT_IP
        sendPairRequestFrame(ip, peer.deviceId, peer.dataPort)
        emitLog("[等待对方同意连接] 已向陌生设备 \"${peer.deviceName}\" ($ip) 发送连接请求，请在对端设备上点击『同意连接』...")
    }

    private fun sendPairRequestFrame(destIp: String, targetDeviceId: String?, port: Int = P2pProtocol.DATA_TCP_PORT) {
        val header = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("platform", "android")
            put("data_port", P2pProtocol.DATA_TCP_PORT)
            if (!targetDeviceId.isNullOrBlank() && !targetDeviceId.startsWith("p2p-")) {
                put("target_device_id", targetDeviceId)
            }
            put("timestamp", System.currentTimeMillis())
        }
        val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.PAIR_REQUEST, header)
        bleChannel.broadcastFrameToDiscoveredBlePeers(frame, isClipboard = false)
        transportEngine.sendControlFrameUdp(frame, destIp)
        transportEngine.sendControlFrameTcp(frame, destIp, port)
    }

    /**
     * Called when the local user approves or rejects an incoming connection request from a stranger device.
     */
    fun respondToPairRequest(peerId: String, accepted: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            nm?.cancel(PAIR_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }

        val peer = discoveredPeers[peerId] ?: return
        val destIp = peer.ip ?: targetPeerIp
        val header = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("platform", "android")
            put("data_port", P2pProtocol.DATA_TCP_PORT)
            put("accepted", accepted)
            if (!peer.deviceId.startsWith("p2p-")) {
                put("target_device_id", peer.deviceId)
            }
            put("timestamp", System.currentTimeMillis())
        }
        val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.PAIR_RESPONSE, header)
        bleChannel.broadcastFrameToDiscoveredBlePeers(frame, isClipboard = false)
        transportEngine.sendControlFrameUdp(frame, destIp)
        transportEngine.sendControlFrameTcp(frame, destIp, peer.dataPort)

        if (accepted) {
            isManuallyDisconnected = false
            manuallyDisconnectedPeerIds.remove(peer.deviceId)
            if (peer.deviceName.isNotBlank()) {
                manuallyDisconnectedPeerIds.remove("name:${peer.deviceName}")
            }
            peer.isConnected = true
            rememberTrustedPeer(peer)
            connectedPeer = peer
            targetPeerIp = destIp
            updateConnectionNotification()
            serviceScope.launch {
                uiCallback?.onConnectedPeerChanged(peer)
                uiCallback?.onPeersUpdated(getPeerList())
            }
            emitLog("[已同意连接] 已同意 \"${peer.deviceName}\" ($destIp) 的连接请求并加入 ★已信任设备")
        } else {
            peer.isConnected = false
            serviceScope.launch {
                uiCallback?.onPeersUpdated(getPeerList())
            }
            emitLog("[已拒绝连接] 已拒绝陌生设备 \"${peer.deviceName}\" ($destIp) 的连接请求")
        }
    }

    /**
     * Disconnect a single specific device without affecting other connected devices!
     */
    fun disconnectPeer(peerId: String) {
        val peer = discoveredPeers[peerId] ?: run {
            if (connectedPeer?.deviceId == peerId) {
                disconnectCurrentPeer()
            }
            return
        }
        peer.isConnected = false
        manuallyDisconnectedPeerIds.add(peer.deviceId)
        if (peer.deviceName.isNotBlank()) {
            manuallyDisconnectedPeerIds.add("name:${peer.deviceName}")
        }

        // Send unicast teardown only to this specific peer
        sendP2pTeardownSignal(targetPeer = peer)

        // If this was the active target peer, switch to another still-connected peer (if any)
        if (connectedPeer?.deviceId == peer.deviceId) {
            connectedPeer = discoveredPeers.values.firstOrNull { it.isConnected && it.deviceId != peer.deviceId }
            connectedPeer?.ip?.let { targetPeerIp = it }
        }

        val wasOnPcHotspot = peer.ip?.startsWith("192.168.137.") == true
        if (connectedPeer == null) {
            isManuallyDisconnected = true
            wifiP2pManager.teardownGroup()
            powerPolicy.forceDeepIdleNow()
        }
        if (wasOnPcHotspot && discoveredPeers.values.none { it.isConnected && it.ip?.startsWith("192.168.137.") == true }) {
            // Let the teardown frame leave over the hotspot before releasing the network.
            serviceScope.launch {
                delay(600L)
                hotspotLink.disconnect()
                transportEngine.releaseNetworkSockets()
            }
        }

        updateConnectionNotification()
        serviceScope.launch {
            uiCallback?.onConnectedPeerChanged(connectedPeer)
            uiCallback?.onPeersUpdated(getPeerList())
        }
        emitLog("[已单独断开设备] 已断开与 ${peer.deviceName} 的连接 (点击该设备的『连接』可随时重连)")
    }

    /**
     * Manually disconnect from the current target peer (or all if none active).
     */
    fun disconnectCurrentPeer() {
        val cur = connectedPeer
        if (cur != null) {
            disconnectPeer(cur.deviceId)
            return
        }
        isManuallyDisconnected = true
        for (p in discoveredPeers.values) {
            p.isConnected = false
            manuallyDisconnectedPeerIds.add(p.deviceId)
        }
        connectedPeer = null
        updateConnectionNotification()
        uiCallback?.onConnectedPeerChanged(null)
        uiCallback?.onPeersUpdated(getPeerList())
        sendP2pTeardownSignal()
        wifiP2pManager.teardownGroup()
        powerPolicy.forceDeepIdleNow()
        serviceScope.launch {
            delay(600L)
            hotspotLink.disconnect()
            transportEngine.releaseNetworkSockets()
        }
        emitLog("[已手动断开连接] 当前连接已断开，Wi-Fi P2P 已关闭回到低功耗待机")
    }

    /**
     * Spin up Wi-Fi P2P Autonomous Group Owner on demand (for Android<->Android or Android<->Windows).
     */
    fun startBurstGroupOnDemand() {
        isManuallyDisconnected = false
        powerPolicy.onHandshakeStarted(handshakeTimeoutMs = 120_000L)
        wifiP2pManager.createBurstGroupOnDemand(deviceName = deviceName)
    }

    /**
     * Connect to peer's Autonomous GO (another Android phone or Windows laptop `DIRECT-...`)
     * using SSID + Passphrase with zero WPS dialogs.
     */
    fun connectToPeerBurstGroup(ssid: String, passphrase: String, goIp: String, isManualUserClick: Boolean = true) {
        if (goIp.startsWith("192.168.137.")) {
            connectToPcHotspot(ssid, passphrase, goIp, isManualUserClick)
            return
        }
        isManuallyDisconnected = false
        powerPolicy.onHandshakeStarted(handshakeTimeoutMs = 120_000L)
        targetPeerIp = goIp
        wifiP2pManager.connectWithCredentials(ssid, passphrase, goIp, isManualUserClick = isManualUserClick)
    }

    fun teardownBurstGroupNow() {
        disconnectCurrentPeer()
    }

    fun announcePresenceHello(targetIp: String? = null, isReply: Boolean = false) {
        val header = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("platform", "android")
            put("data_port", P2pProtocol.DATA_TCP_PORT)
            put("is_reply", isReply)
            wifiP2pManager.activeGroupSsid?.let { put("ssid", it) }
            wifiP2pManager.activeGroupPassphrase?.let { put("passphrase", it) }
            wifiP2pManager.currentGoIp?.let { put("go_ip", it) }
            put("timestamp", System.currentTimeMillis())
        }
        val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.HELLO, header)
        bleChannel.broadcastFrameToDiscoveredBlePeers(frame, isClipboard = false)
        if (!targetIp.isNullOrBlank()) {
            transportEngine.sendControlFrameUdp(frame, targetIp)
            transportEngine.sendControlFrameTcp(frame, targetIp, P2pProtocol.DATA_TCP_PORT)
        } else {
            transportEngine.broadcastControlFrameUdp(frame, extraTargetIp = targetPeerIp)
            val pcGateway = if (hotspotLink.isUp) hotspotLink.gatewayIp else null
            if (pcGateway != null) {
                transportEngine.sendControlFrameUdp(frame, pcGateway)
                transportEngine.sendControlFrameTcp(frame, pcGateway, P2pProtocol.DATA_TCP_PORT)
            } else if (wifiP2pManager.isSubnetAlreadyReachable(P2pProtocol.WINDOWS_GO_DEFAULT_IP)) {
                transportEngine.sendControlFrameTcp(frame, P2pProtocol.WINDOWS_GO_DEFAULT_IP, P2pProtocol.DATA_TCP_PORT)
            }
            if (wifiP2pManager.isSubnetAlreadyReachable(P2pProtocol.ANDROID_GO_DEFAULT_IP)) {
                transportEngine.sendControlFrameTcp(frame, P2pProtocol.ANDROID_GO_DEFAULT_IP, P2pProtocol.DATA_TCP_PORT)
            }
        }
    }

    /** A scan/Wi-Fi Direct placeholder for a device that already answered is folded into the real entry. */
    private fun storedPeerFor(peer: P2pProtocol.PeerInfo): P2pProtocol.PeerInfo =
        discoveredPeers[peer.deviceId]
            ?: if (peer.deviceId.startsWith("p2p")) {
                discoveredPeers.values.firstOrNull { !it.deviceId.startsWith("p2p") && it.deviceName == peer.deviceName } ?: peer
            } else {
                peer
            }

    private fun upsertPeer(peer: P2pProtocol.PeerInfo): Boolean {
        if (peer.deviceId.startsWith("p2p")) {
            val real = storedPeerFor(peer)
            if (real !== peer && !real.deviceId.startsWith("p2p")) {
                if (!peer.p2pSsid.isNullOrBlank()) real.p2pSsid = peer.p2pSsid
                if (!peer.p2pPassphrase.isNullOrBlank()) real.p2pPassphrase = peer.p2pPassphrase
                if (!peer.p2pGoIp.isNullOrBlank()) real.p2pGoIp = peer.p2pGoIp
                return false
            }
        }
        val existing = discoveredPeers[peer.deviceId]
        val isNewOrChanged: Boolean
        if (existing != null) {
            isNewOrChanged = (existing.deviceName != peer.deviceName) || (existing.ip != peer.ip && !peer.ip.isNullOrBlank())
            existing.deviceName = peer.deviceName.ifBlank { existing.deviceName }
            if (!peer.ip.isNullOrBlank()) existing.ip = peer.ip
            if (!peer.p2pSsid.isNullOrBlank()) existing.p2pSsid = peer.p2pSsid
            if (!peer.p2pPassphrase.isNullOrBlank()) existing.p2pPassphrase = peer.p2pPassphrase
            if (!peer.p2pGoIp.isNullOrBlank()) existing.p2pGoIp = peer.p2pGoIp
            existing.discoverySource = peer.discoverySource
            existing.lastSeenMs = System.currentTimeMillis()
        } else {
            isNewOrChanged = true
            discoveredPeers[peer.deviceId] = peer
        }
        // Once the real device answered, drop scan/Wi-Fi Direct placeholders that describe the same device.
        if (!peer.deviceId.startsWith("p2p")) {
            val real = discoveredPeers[peer.deviceId] ?: peer
            val staleIds = discoveredPeers.values
                .filter {
                    it.deviceId.startsWith("p2p") &&
                        (it.deviceName == real.deviceName || (real.p2pSsid != null && it.p2pSsid == real.p2pSsid))
                }
                .map { it.deviceId }
            for (sid in staleIds) {
                val removed = discoveredPeers.remove(sid)
                if (removed != null && connectedPeer?.deviceId == sid) {
                    connectedPeer = real
                }
            }
        }
        serviceScope.launch {
            uiCallback?.onPeersUpdated(getPeerList())
        }
        return isNewOrChanged
    }

    /** Placeholder entries (`p2p-<ssid>`, `p2pdev-<mac>`) are not IDs the peer recognizes as its own. */
    private fun realDeviceId(peer: P2pProtocol.PeerInfo?): String? =
        peer?.deviceId?.takeIf { !it.startsWith("p2p") }

    /**
     * Smart Clipboard Sync (Unicast to the specified/connected target device ONLY — never broadcast!):
     */
    fun syncClipboardSmart(
        text: String,
        clipHash: String? = null,
        targetPeer: P2pProtocol.PeerInfo? = connectedPeer
    ) {
        val destPeer = targetPeer ?: connectedPeer
        if (destPeer == null && isManuallyDisconnected) {
            return
        }
        val destIp = destPeer?.ip ?: targetPeerIp
        val destName = destPeer?.deviceName ?: destIp
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val digest = clipHash ?: P2pProtocol.computeClipHash(payload)

        // Connect handshakes arrive over both UDP and TCP and each one triggers a sync; send a clip once.
        val now = System.currentTimeMillis()
        val last = lastClipSend
        if (last != null && last.first == destIp && last.second == digest && now - last.third < 3_000L) {
            return
        }
        lastClipSend = Triple(destIp, digest, now)

        if (payload.size <= P2pProtocol.MAX_INLINE_CLIPBOARD_BYTES) {
            val header = JSONObject().apply {
                put("device_id", deviceId)
                put("device_name", deviceName)
                put("clip_id", digest)
                put("mime", "text/plain")
                put("timestamp", System.currentTimeMillis())
                put("origin_device_id", deviceId)
                realDeviceId(destPeer)?.let { put("target_device_id", it) }
            }
            val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.CLIPBOARD_SYNC, header, payload)
            // UDP for latency, TCP for delivery; the receiver de-duplicates by clip hash.
            transportEngine.sendControlFrameUdp(frame, destIp)
            transportEngine.sendControlFrameTcp(frame, destIp, destPeer?.dataPort ?: P2pProtocol.DATA_TCP_PORT)
            val preview = text.replace("\r", " ").replace("\n", " ").take(36)
            emitLog("[已发送剪贴板 → $destName] \"$preview\" (${payload.size} B)")
        } else {
            serviceScope.launch {
                val ok = transportEngine.sendClipboardOverTcp(destIp, text)
                if (ok) {
                    val preview = text.replace("\r", " ").replace("\n", " ").take(36)
                    emitLog("[已发送大剪贴板 → $destName] \"$preview\" (${payload.size} B)")
                }
            }
        }
    }

    /**
     * Send files on demand to a SPECIFIC target device (never broadcast!):
     */
    fun sendFileOnDemand(
        file: File,
        destIp: String = connectedPeer?.ip ?: targetPeerIp,
        targetPeer: P2pProtocol.PeerInfo? = connectedPeer
    ) {
        val resolvedPeer = targetPeer ?: discoveredPeers.values.firstOrNull { it.ip == destIp } ?: connectedPeer
        val finalIp = resolvedPeer?.ip ?: destIp
        val finalName = resolvedPeer?.deviceName ?: finalIp

        serviceScope.launch {
            emitLog("[准备定向发送文件 → $finalName] ${file.name} (${file.length()} B) [$finalIp]")
            val ok = transportEngine.sendFile(finalIp, file)
            if (ok) {
                emitLog("[文件发送成功 → $finalName] ${file.name} 校验通过，闲置15s后自动释放 P2P 省电")
            } else {
                emitLog("[文件发送提示] 正在定向唤醒 $finalName ($finalIp) 的高速通道...")
                requestPeerToWakeP2p(finalIp, targetDeviceId = resolvedPeer?.deviceId)
            }
        }
    }

    fun requestPeerToWakeP2p(destIp: String = targetPeerIp, targetDeviceId: String? = connectedPeer?.deviceId) {
        powerPolicy.onHandshakeStarted()
        val header = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("platform", "android")
            put("reason", "file_transfer")
            targetDeviceId?.takeIf { !it.startsWith("p2p") }?.let { put("target_device_id", it) }
            put("timestamp", System.currentTimeMillis())
        }
        val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.P2P_WAKE_REQUEST, header)
        bleChannel.broadcastFrameToDiscoveredBlePeers(frame, isClipboard = false)
        // Send unicast to target IP instead of broadcasting to all devices
        transportEngine.sendControlFrameUdp(frame, destIp)
    }

    private fun broadcastP2pGroupReady(ssid: String, passphrase: String, goIp: String) {
        val header = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("platform", "android")
            put("ssid", ssid)
            put("passphrase", passphrase)
            put("go_ip", goIp)
            put("port", P2pProtocol.DATA_TCP_PORT)
            realDeviceId(connectedPeer)?.let { put("target_device_id", it) }
            put("timestamp", System.currentTimeMillis())
        }
        val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.P2P_GROUP_READY, header)
        bleChannel.broadcastFrameToDiscoveredBlePeers(frame, isClipboard = false)
        val destIp = connectedPeer?.ip
        if (!destIp.isNullOrBlank()) {
            transportEngine.sendControlFrameUdp(frame, destIp)
        } else {
            transportEngine.broadcastControlFrameUdp(frame)
        }
    }

    private fun sendP2pTeardownSignal(targetPeer: P2pProtocol.PeerInfo? = connectedPeer) {
        val header = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            targetPeer?.deviceId?.takeIf { !it.startsWith("p2p-") }?.let { put("target_device_id", it) }
            put("timestamp", System.currentTimeMillis())
        }
        val frame = P2pProtocol.encodeFrame(P2pProtocol.FrameType.P2P_TEARDOWN, header)
        val destIp = targetPeer?.ip ?: targetPeerIp
        transportEngine.sendControlFrameUdp(frame, destIp)
        transportEngine.sendControlFrameTcp(frame, destIp, targetPeer?.dataPort ?: P2pProtocol.DATA_TCP_PORT)
    }

    private fun handleControlFrame(frame: P2pProtocol.DecodedFrame, senderAddr: String) {
        val remoteId = frame.header.optString("device_id", senderAddr)
        if (remoteId == deviceId) return

        // If frame specifies a target_device_id and it's not us, ignore it!
        val targetDeviceId = frame.header.optString("target_device_id", "")
        if (targetDeviceId.isNotEmpty() && targetDeviceId != deviceId) {
            return
        }

        val remoteName = frame.header.optString("device_name", senderAddr)
        val remotePlatform = frame.header.optString("platform", "windows")
        val remotePort = frame.header.optInt("data_port", P2pProtocol.DATA_TCP_PORT)
        val ipAddr = if (senderAddr.contains(".")) senderAddr else null

        val peer = P2pProtocol.PeerInfo(
            deviceId = remoteId,
            deviceName = remoteName,
            platform = remotePlatform,
            ip = ipAddr,
            dataPort = remotePort,
            p2pSsid = (frame.header.optString("hotspot_ssid", "").ifEmpty { frame.header.optString("ssid", "") }).takeIf { it.isNotEmpty() },
            p2pPassphrase = (frame.header.optString("hotspot_passphrase", "").ifEmpty { frame.header.optString("passphrase", "") }).takeIf { it.isNotEmpty() },
            p2pGoIp = (frame.header.optString("hotspot_ip", "").ifEmpty { frame.header.optString("go_ip", "") }).takeIf { it.isNotEmpty() },
            discoverySource = if (ipAddr != null) "lan" else "ble"
        )
        val isNewOrChanged = upsertPeer(peer)
        val storedPeer = discoveredPeers[remoteId] ?: peer
        val trusted = isTrustedPeer(storedPeer)
        val peerManuallyDisconnected = isPeerManuallyDisconnected(storedPeer)
        val wasConnectedBefore = storedPeer.isConnected

        val hotspotSsid = frame.header.optString("hotspot_ssid", "")
        if (trusted && hotspotSsid.isNotEmpty()) {
            rememberPeerHotspot(remoteName, hotspotSsid, frame.header.optString("hotspot_passphrase", P2pProtocol.deriveP2pPassphrase(hotspotSsid)))
        }

        // ONLY auto-connect if the peer is ALREADY a trusted (`★已信任`) device!
        // Stranger devices must NEVER auto-connect without manual user approval.
        val isTeardown = frame.type == P2pProtocol.FrameType.P2P_TEARDOWN
        if (!isTeardown && !isManuallyDisconnected && !peerManuallyDisconnected && trusted && ipAddr != null) {
            val wasConnected = storedPeer.isConnected && (connectedPeer?.deviceId == remoteId)
            storedPeer.isConnected = true
            rememberTrustedPeer(storedPeer)
            if (connectedPeer == null || connectedPeer?.deviceId == remoteId) {
                connectedPeer = storedPeer
                targetPeerIp = ipAddr
                updateConnectionNotification()
            }
            serviceScope.launch {
                uiCallback?.onConnectedPeerChanged(connectedPeer)
                uiCallback?.onPeersUpdated(getPeerList())
            }
            if (!wasConnected) {
                emitLog("[自动重连成功] 已自动恢复与 ★$remoteName ($ipAddr) 的连接")
            }
        } else if (!isTeardown && !peerManuallyDisconnected && trusted && (connectedPeer?.deviceId == remoteId || (ipAddr != null && connectedPeer?.ip == ipAddr))) {
            storedPeer.isConnected = true
            connectedPeer = storedPeer
            if (ipAddr != null) targetPeerIp = ipAddr
            updateConnectionNotification()
            serviceScope.launch {
                uiCallback?.onConnectedPeerChanged(connectedPeer)
                uiCallback?.onPeersUpdated(getPeerList())
            }
        }

        when (frame.type) {
            P2pProtocol.FrameType.HELLO -> {
                val isReply = frame.header.optBoolean("is_reply", false)
                if (isNewOrChanged) {
                    val star = if (trusted) "★已信任 " else "[陌生设备] "
                    emitLog("[发现设备] $star$remoteName [$remotePlatform] (${ipAddr ?: senderAddr})")
                }
                if (!isReply) {
                    announcePresenceHello(targetIp = ipAddr, isReply = true)
                }
            }

            P2pProtocol.FrameType.PAIR_REQUEST -> {
                if (trusted) {
                    respondToPairRequest(remoteId, accepted = true)
                } else {
                    emitLog("[收到连接请求 ← $remoteName] 陌生设备 ($senderAddr) 请求与本机建立连接，请确认是否同意")
                    postPairRequestNotification(storedPeer)
                    serviceScope.launch {
                        uiCallback?.onPairRequestReceived(storedPeer)
                    }
                }
            }

            P2pProtocol.FrameType.PAIR_RESPONSE -> {
                val accepted = frame.header.optBoolean("accepted", false)
                if (accepted) {
                    isManuallyDisconnected = false
                    manuallyDisconnectedPeerIds.remove(storedPeer.deviceId)
                    if (storedPeer.deviceName.isNotBlank()) {
                        manuallyDisconnectedPeerIds.remove("name:${storedPeer.deviceName}")
                    }
                    storedPeer.isConnected = true
                    rememberTrustedPeer(storedPeer)
                    connectedPeer = storedPeer
                    if (ipAddr != null) targetPeerIp = ipAddr
                    updateConnectionNotification()
                    serviceScope.launch {
                        uiCallback?.onConnectedPeerChanged(connectedPeer)
                        uiCallback?.onPeersUpdated(getPeerList())
                    }
                    // Connecting never pushes this phone's clipboard: the peer pushes its own on connect, and
                    // pushing both ways at once makes the two clipboards swap.
                    if (!wasConnectedBefore) {
                        emitLog("[对方已同意连接] \"$remoteName\" (${ipAddr ?: senderAddr}) 已同意连接请求！已加入 ★已信任设备")
                    }
                } else {
                    storedPeer.isConnected = false
                    serviceScope.launch {
                        uiCallback?.onPeersUpdated(getPeerList())
                    }
                    emitLog("[连接请求被拒绝] \"$remoteName\" (${ipAddr ?: senderAddr}) 拒绝了连接请求")
                }
            }

            P2pProtocol.FrameType.CLIPBOARD_SYNC -> {
                if (!isManuallyDisconnected && !peerManuallyDisconnected && (trusted || storedPeer.isConnected)) {
                    val text = String(frame.payload, StandardCharsets.UTF_8)
                    val clipId = frame.header.optString("clip_id", P2pProtocol.computeClipHash(frame.payload))
                    val applied = clipboardHelper.applyRemoteClipboard(text, clipId)
                    if (applied) {
                        emitLog("[收到剪贴板 ← $remoteName] \"${text.take(36)}\"")
                    }
                } else {
                    emitLog("[安全拦截] 已拦截未授权陌生设备 \"$remoteName\" 发来的剪贴板")
                }
            }

            P2pProtocol.FrameType.P2P_WAKE_REQUEST -> {
                if (!peerManuallyDisconnected && (trusted || storedPeer.isConnected)) {
                    emitLog("[收到定向唤醒请求 ← $remoteName] 正在拉起本机 5GHz Wi-Fi P2P 热点...")
                    startBurstGroupOnDemand()
                } else {
                    emitLog("[安全拦截] 已忽略未授权陌生设备 \"$remoteName\" 的P2P唤醒请求")
                }
            }

            P2pProtocol.FrameType.P2P_GROUP_READY -> {
                val ssid = frame.header.optString("ssid", "")
                val pass = frame.header.optString("passphrase", "")
                val goIp = frame.header.optString("go_ip", P2pProtocol.WINDOWS_GO_DEFAULT_IP)
                if (!isManuallyDisconnected && !peerManuallyDisconnected && (trusted || storedPeer.isConnected) && ssid.isNotEmpty() && pass.isNotEmpty()) {
                    emitLog("[收到对端P2P凭据 ← $remoteName] 正在免确认直连 $ssid ($goIp)...")
                    connectToPeerBurstGroup(ssid, pass, goIp)
                }
            }

            P2pProtocol.FrameType.P2P_TEARDOWN -> {
                emitLog("[收到对端断开/休眠信令 ← $remoteName] 已断开与 $remoteName 的连接")
                storedPeer.isConnected = false
                if (connectedPeer?.deviceId == remoteId) {
                    connectedPeer = discoveredPeers.values.firstOrNull { it.isConnected && it.deviceId != remoteId }
                }
                if (connectedPeer == null) {
                    wifiP2pManager.teardownGroup()
                    powerPolicy.forceDeepIdleNow()
                }
                if (ipAddr != null && hotspotLink.networkFor(ipAddr) != null &&
                    discoveredPeers.values.none { it.isConnected && it.ip?.let { ip -> hotspotLink.networkFor(ip) } != null }
                ) {
                    hotspotLink.disconnect()
                    transportEngine.releaseNetworkSockets()
                }
                updateConnectionNotification()
                serviceScope.launch {
                    uiCallback?.onConnectedPeerChanged(connectedPeer)
                    uiCallback?.onPeersUpdated(getPeerList())
                }
            }

            else -> {}
        }
    }

    private fun handlePowerStateTransition(state: P2pProtocol.PowerState) {
        uiCallback?.onPowerStateChanged(state)
        when (state) {
            P2pProtocol.PowerState.P2P_BURST_ACTIVE -> {
                promoteOrUpdateForegroundNotification("⚡ 高速文件传输中 (完成后15s自动省电断链)")
            }
            P2pProtocol.PowerState.DEEP_IDLE -> {
                updateConnectionNotification()
            }
            P2pProtocol.PowerState.P2P_HANDSHAKE -> {}
        }
    }

    private fun promoteOrUpdateForegroundNotification(contentText: String) {
        val notif = buildInteractiveNotification(contentText)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            return
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground fallback to notify: ${e.message}")
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, notif)
        } catch (_: Throwable) {
        }
    }

    private fun updateConnectionNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val cur = connectedPeer
        if (cur != null) {
            // Keep service in foreground state while actively connected to a peer so OEM ROMs
            // (Honor MagicOS hwpged / MIUI / ColorOS) do not cgroup-freeze the UDP receiver in background!
            // Note: WakeLock & WifiLock remain RELEASED in DEEP_IDLE so battery impact is near zero.
            promoteOrUpdateForegroundNotification(
                "已连接: ${cur.deviceName} (${cur.ip ?: "直连"}) — 后台实时接收剪贴板与文件中"
            )
        } else if (powerPolicy.currentState == P2pProtocol.PowerState.DEEP_IDLE) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            try {
                nm.cancel(NOTIFICATION_ID)
            } catch (_: Throwable) {
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ConnectP2P 连接与快捷同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供通知栏一键发送剪贴板、单独断开连接与文件传输进度"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildInteractiveNotification(contentText: String): Notification {
        val openMainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPending = PendingIntent.getActivity(
            this,
            45820,
            openMainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: 1-tap background clipboard send via translucent ClipboardSyncActivity
        val clipActIntent = Intent(this, ClipboardSyncActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val clipPending = PendingIntent.getActivity(
            this,
            45822,
            clipActIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: 1-tap disconnect current peer
        val discIntent = Intent(this, P2pTransferService::class.java).apply {
            action = ACTION_DISCONNECT_PEER
            connectedPeer?.deviceId?.let { putExtra(EXTRA_PEER_ID, it) }
        }
        val discPending = PendingIntent.getService(
            this,
            45823,
            discIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val curName = connectedPeer?.deviceName ?: "ConnectP2P"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ConnectP2P · 目标: $curName")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(contentPending)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_share,
                    "📋 发送剪贴板",
                    clipPending
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "🔌 断开该设备",
                    discPending
                ).build()
            )
        return builder.build()
    }

    private fun postPairRequestNotification(peer: P2pProtocol.PeerInfo) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            val openMainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val contentPending = PendingIntent.getActivity(
                this,
                45826,
                openMainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val approveIntent = Intent(this, P2pTransferService::class.java).apply {
                action = ACTION_RESPOND_PAIR_REQUEST
                putExtra(EXTRA_PEER_ID, peer.deviceId)
                putExtra(EXTRA_PAIR_ACCEPTED, true)
            }
            val approvePending = PendingIntent.getService(
                this,
                45827,
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val rejectIntent = Intent(this, P2pTransferService::class.java).apply {
                action = ACTION_RESPOND_PAIR_REQUEST
                putExtra(EXTRA_PEER_ID, peer.deviceId)
                putExtra(EXTRA_PAIR_ACCEPTED, false)
            }
            val rejectPending = PendingIntent.getService(
                this,
                45828,
                rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("收到陌生设备连接请求: ${peer.deviceName}")
                .setContentText("设备 ${peer.deviceName} (${peer.ip ?: "直连"}) 请求连接并同步数据")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(contentPending)
                .setAutoCancel(true)
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_input_add,
                        "✅ 同意并信任",
                        approvePending
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_delete,
                        "❌ 拒绝",
                        rejectPending
                    ).build()
                )
                .build()
            nm.notify(PAIR_NOTIFICATION_ID, notif)
        } catch (_: Throwable) {
        }
    }

    private fun emitLog(msg: String) {
        Log.i(TAG, msg)
        serviceScope.launch {
            uiCallback?.onLog(msg)
        }
    }
}
