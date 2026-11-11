package com.connectp2p.app.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.connectp2p.app.protocol.P2pProtocol
import java.net.NetworkInterface

@SuppressLint("MissingPermission")
class WifiP2pBurstManager(
    private val context: Context,
    private val onGroupReady: (ssid: String, passphrase: String, goIp: String) -> Unit,
    private val onConnectedToGo: (goIp: String, isGroupOwner: Boolean) -> Unit,
    private val onPeersDiscovered: (List<WifiP2pDevice>) -> Unit,
    private val onDirectHotspotsScanned: (List<P2pProtocol.PeerInfo>) -> Unit = {},
    private val onDisconnected: () -> Unit,
    private val onLogMessage: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "ConnectP2P.WifiP2p"
        private const val CONNECT_COOLDOWN_MS = 10_000L
        private const val SCAN_THROTTLE_MS = 3_000L
    }

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false

    @Volatile
    var activeGroupSsid: String? = null
        private set

    @Volatile
    var activeGroupPassphrase: String? = null
        private set

    @Volatile
    var currentGoIp: String? = null
        private set

    @Volatile
    var isConnectingOrConnected: Boolean = false
        private set

    @Volatile
    private var lastConnectAttemptTimeMs: Long = 0L

    @Volatile
    private var lastFailedSsid: String? = null

    @Volatile
    private var lastScanParseTimeMs: Long = 0L

    fun initialize() {
        try {
            if (channel == null && wifiP2pManager != null) {
                channel = wifiP2pManager.initialize(context.applicationContext, Looper.getMainLooper(), null)
            }
            registerBroadcastReceiver()
        } catch (t: Throwable) {
            Log.e(TAG, "initialize failed: ${t.message}")
            onLogMessage("[P2P初始化提示] ${t.message}")
        }
    }

    fun shutdown() {
        teardownGroup()
        unregisterBroadcastReceiver()
    }

    /**
     * Spin up an on-demand Wi-Fi Direct Group Owner (`DIRECT-CP-...`).
     * Uses `encodeP2pSsid(deviceName)` + `deriveP2pPassphrase(ssid)` so nearby peers can
     * discover this phone's friendly name and connect with 1 click (0 password typing).
     * Also fixes the Android 10~17 `IllegalStateException: network name must be non-empty`
     * in `WifiP2pConfig.Builder.build()`.
     */
    fun createBurstGroupOnDemand(deviceName: String = Build.MODEL ?: "Phone") {
        val mgr = wifiP2pManager ?: run {
            onLogMessage("[错误] 当前设备不支持 Wi-Fi Direct 服务")
            return
        }
        if (channel == null) {
            initialize()
        }
        val ch = channel ?: return

        try {
            mgr.requestGroupInfo(ch) { existingGroup ->
                try {
                    if (existingGroup != null && existingGroup.isGroupOwner) {
                        extractAndNotifyGroupCredentials(existingGroup)
                        return@requestGroupInfo
                    }

                    val targetSsid = P2pProtocol.encodeP2pSsid(deviceName)
                    val targetPass = P2pProtocol.deriveP2pPassphrase(targetSsid)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val config5g = WifiP2pConfig.Builder()
                                .setNetworkName(targetSsid)
                                .setPassphrase(targetPass)
                                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                                .enablePersistentMode(false)
                                .build()

                            mgr.createGroup(ch, config5g, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    Log.i(TAG, "Created 5GHz Wi-Fi P2P Group: $targetSsid")
                                    requestGroupCredentialsWithRetry(fallbackSsid = targetSsid, fallbackPass = targetPass)
                                }

                                override fun onFailure(reason: Int) {
                                    Log.w(TAG, "5GHz createGroup failed (reason=$reason), trying AUTO band...")
                                    createGroupAutoBandOrDefault(mgr, ch, targetSsid, targetPass)
                                }
                            })
                        } catch (t: Throwable) {
                            Log.w(TAG, "WifiP2pConfig 5GHz build/create threw ${t.message}, falling back")
                            createGroupDefault(mgr, ch, targetSsid, targetPass)
                        }
                    } else {
                        createGroupDefault(mgr, ch, targetSsid, targetPass)
                    }
                } catch (inner: Throwable) {
                    Log.e(TAG, "Error inside requestGroupInfo callback: ${inner.message}", inner)
                    onLogMessage("[开启热点异常] 请确认已开启 Wi-Fi 并授予『附近设备/位置』权限 (${inner.javaClass.simpleName})")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createBurstGroupOnDemand failed: ${t.message}", t)
            onLogMessage("[开启热点失败] 请检查权限与 Wi-Fi 开关: ${t.message}")
        }
    }

    private fun createGroupAutoBandOrDefault(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        targetSsid: String,
        targetPass: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val configAuto = WifiP2pConfig.Builder()
                    .setNetworkName(targetSsid)
                    .setPassphrase(targetPass)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    .enablePersistentMode(false)
                    .build()
                mgr.createGroup(ch, configAuto, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Created Auto-Band Wi-Fi P2P Group: $targetSsid")
                        requestGroupCredentialsWithRetry(fallbackSsid = targetSsid, fallbackPass = targetPass)
                    }

                    override fun onFailure(reason: Int) {
                        createGroupDefault(mgr, ch, targetSsid, targetPass)
                    }
                })
                return
            } catch (_: Throwable) {
            }
        }
        createGroupDefault(mgr, ch, targetSsid, targetPass)
    }

    private fun createGroupDefault(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        fallbackSsid: String,
        fallbackPass: String
    ) {
        try {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Created default Wi-Fi P2P Group on demand")
                    requestGroupCredentialsWithRetry(fallbackSsid = fallbackSsid, fallbackPass = fallbackPass)
                }

                override fun onFailure(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持P2P"
                        WifiP2pManager.BUSY -> "系统Wi-Fi繁忙(请确认已打开Wi-Fi并授予附近设备/位置权限)"
                        else -> "错误码=$reason"
                    }
                    Log.e(TAG, "createGroup failed: $reasonStr")
                    onLogMessage("[热点启动提示] $reasonStr")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "Default createGroup threw: ${t.message}")
            onLogMessage("[热点启动异常] ${t.message}")
        }
    }

    fun canAutoAttemptConnect(ssid: String?): Boolean {
        if (isConnectingOrConnected || activeGroupSsid != null) return false
        val now = System.currentTimeMillis()
        if (now - lastConnectAttemptTimeMs < CONNECT_COOLDOWN_MS) return false
        if (ssid != null && ssid == lastFailedSsid && now - lastConnectAttemptTimeMs < 30_000L) return false
        return true
    }

    fun isSubnetAlreadyReachable(expectedGoIp: String): Boolean {
        val parts = expectedGoIp.split(".")
        if (parts.size != 4) return false
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith(prefix)) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return false
    }

    /**
     * Remove Wi-Fi suggestions registered by older builds: a high-priority suggestion for the PC hotspot
     * could make the phone's primary Wi-Fi roam onto the PC and lose internet.
     */
    fun removeLegacyNetworkSuggestions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                wifiManager?.removeNetworkSuggestions(emptyList())
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Connect to another Android phone's Wi-Fi Direct group (`DIRECT-CP-*`, GO at 192.168.49.1) by SSID +
     * passphrase. Windows hotspots are plain SoftAPs and are handled by [HotspotLinkManager] instead.
     */
    fun connectWithCredentials(ssid: String, passphrase: String, expectedGoIp: String, isManualUserClick: Boolean = false) {
        val normalizedSsid = if (ssid.startsWith("DIRECT-")) ssid else "DIRECT-CP-N-${ssid.take(20)}"
        val validPass = if (passphrase.length in 8..63) passphrase else P2pProtocol.deriveP2pPassphrase(normalizedSsid)

        val now = System.currentTimeMillis()
        if (!isManualUserClick && (isConnectingOrConnected || now - lastConnectAttemptTimeMs < CONNECT_COOLDOWN_MS)) {
            Log.d(TAG, "Skipping duplicate connectWithCredentials to $ssid (in progress or cooldown)")
            return
        }
        isConnectingOrConnected = true
        lastConnectAttemptTimeMs = now

        val mgr = wifiP2pManager
        val ch = channel
        if (mgr != null && ch != null) {
            try {
                mgr.stopPeerDiscovery(ch, null)
            } catch (_: Throwable) {
            }
        }

        if (mgr == null || ch == null) {
            isConnectingOrConnected = false
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(normalizedSsid)
                    .setPassphrase(validPass)
                    .enablePersistentMode(false)
                    .build()

                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Fast credential P2P connect initiated to $normalizedSsid ($expectedGoIp)")
                        currentGoIp = expectedGoIp
                        lastFailedSsid = null
                        onLogMessage("[正在直连] 已发起免弹窗直连 $normalizedSsid ($expectedGoIp)...")
                    }

                    override fun onFailure(reason: Int) {
                        isConnectingOrConnected = false
                        lastFailedSsid = normalizedSsid
                        val reasonDesc = if (reason == WifiP2pManager.BUSY) {
                            "系统Wi-Fi直连正忙(状态码2)，请稍候重试或在手机端点击『⚡ 开启本机热点』让电脑连接"
                        } else {
                            "状态码 $reason"
                        }
                        Log.e(TAG, "Fast credential connect failed: reason=$reason")
                        onLogMessage("[直连提示] 连接请求返回: $reasonDesc")
                    }
                })
            } else {
                isConnectingOrConnected = false
            }
        } catch (t: Throwable) {
            isConnectingOrConnected = false
            Log.e(TAG, "connectWithCredentials failed: ${t.message}")
            onLogMessage("[直连异常] ${t.message}")
        }
    }

    /**
     * Standard MAC-based Wi-Fi Direct connection for Android <-> Android when picked from peer list.
     */
    fun connectToPeerDevice(deviceAddress: String, isManualUserClick: Boolean = false) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        val now = System.currentTimeMillis()
        if (!isManualUserClick && (isConnectingOrConnected || now - lastConnectAttemptTimeMs < CONNECT_COOLDOWN_MS)) {
            return
        }
        isConnectingOrConnected = true
        lastConnectAttemptTimeMs = now
        try {
            try {
                mgr.stopPeerDiscovery(ch, null)
            } catch (_: Throwable) {
            }
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                wps.setup = WpsInfo.PBC
            }
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Initiated Wi-Fi P2P connection to $deviceAddress")
                    onLogMessage("[P2P连接] 已向设备 $deviceAddress 发起直连请求")
                }

                override fun onFailure(reason: Int) {
                    isConnectingOrConnected = false
                    Log.e(TAG, "Failed connecting to $deviceAddress: reason=$reason")
                    onLogMessage("[P2P连接提示] 状态码: $reason (若正忙请稍候重试)")
                }
            })
        } catch (t: Throwable) {
            isConnectingOrConnected = false
            Log.e(TAG, "connectToPeerDevice failed: ${t.message}")
        }
    }

    /**
     * Single-shot manual discovery:
     * 1. Triggers `WifiP2pManager.discoverPeers()` to find nearby Android Wi-Fi Direct devices.
     * 2. Scans Wi-Fi `DIRECT-*` networks via `WifiManager` to find Windows `DIRECT-CP-...` hotspots!
     */
    fun discoverPeersSingleShot() {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr != null && ch != null && !isConnectingOrConnected) {
            try {
                mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Started single-shot Wi-Fi P2P peer discovery")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "discoverPeers failed: $reason")
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "discoverPeers threw: ${t.message}")
            }
        }
        val wm = wifiManager
        if (wm != null) {
            try {
                @Suppress("DEPRECATION")
                wm.startScan()
            } catch (_: Throwable) {
            }
        }
        parseCachedDirectHotspots(force = true)
    }

    fun parseCachedDirectHotspots(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanParseTimeMs < SCAN_THROTTLE_MS) {
            return
        }
        lastScanParseTimeMs = now
        val wm = wifiManager ?: return
        try {
            val results = wm.scanResults ?: emptyList()
            val found = mutableListOf<P2pProtocol.PeerInfo>()
            val seenSsids = mutableSetOf<String>()
            for (sr in results) {
                @Suppress("DEPRECATION")
                val ssid = (sr.SSID ?: "").trim()
                // Only auto-list ConnectP2P hotspots (`DIRECT-CP-*`) so office printers/TVs don't clutter the list
                if (!ssid.startsWith("DIRECT-CP-") || ssid == activeGroupSsid || !seenSsids.add(ssid)) {
                    continue
                }
                val (friendlyName, isAuto) = P2pProtocol.decodeP2pSsid(ssid)
                val isWin = ssid.contains("WIN", ignoreCase = true) || friendlyName.contains("Win", ignoreCase = true)
                val goIp = if (isWin) P2pProtocol.WINDOWS_GO_DEFAULT_IP else P2pProtocol.ANDROID_GO_DEFAULT_IP
                val pass = if (isAuto) P2pProtocol.deriveP2pPassphrase(ssid) else null
                found.add(
                    P2pProtocol.PeerInfo(
                        deviceId = "p2p-$ssid",
                        deviceName = friendlyName,
                        platform = if (isWin) "windows" else "android",
                        ip = goIp,
                        p2pSsid = ssid,
                        p2pPassphrase = pass,
                        p2pGoIp = goIp,
                        discoverySource = "p2p_scan"
                    )
                )
            }
            Log.d(
                TAG,
                "scanResults=${results.size} direct=${results.count { (it.SSID ?: "").startsWith("DIRECT-") }} cp=${found.map { it.p2pSsid }}"
            )
            if (found.isNotEmpty()) {
                onDirectHotspotsScanned(found)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "scanResults read skipped: ${t.message}")
        }
    }

    /**
     * Tear down Wi-Fi P2P Group and stop any peer discovery immediately so the Wi-Fi radio
     * returns to DEEP_IDLE power saving state.
     */
    fun teardownGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        try {
            mgr.stopPeerDiscovery(ch, null)
        } catch (_: Throwable) {
        }

        try {
            mgr.requestGroupInfo(ch) { group ->
                try {
                    if (group != null) {
                        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                Log.i(TAG, "Wi-Fi P2P Group removed -> Wi-Fi chipset returned to DEEP_IDLE")
                                clearActiveState()
                                onDisconnected()
                            }

                            override fun onFailure(reason: Int) {
                                clearActiveState()
                                onDisconnected()
                            }
                        })
                    } else {
                        mgr.cancelConnect(ch, null)
                        clearActiveState()
                    }
                } catch (_: Throwable) {
                    clearActiveState()
                }
            }
        } catch (_: Throwable) {
            clearActiveState()
        }
    }

    private fun clearActiveState() {
        activeGroupSsid = null
        activeGroupPassphrase = null
        currentGoIp = null
        isConnectingOrConnected = false
    }

    private fun requestGroupCredentialsWithRetry(
        attemptsLeft: Int = 6,
        fallbackSsid: String? = null,
        fallbackPass: String? = null
    ) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.requestGroupInfo(ch) { group ->
                if (group != null && !group.networkName.isNullOrEmpty() && !group.passphrase.isNullOrEmpty()) {
                    extractAndNotifyGroupCredentials(group)
                } else if (attemptsLeft > 0) {
                    android.os.Handler(Looper.getMainLooper()).postDelayed(
                        { requestGroupCredentialsWithRetry(attemptsLeft - 1, fallbackSsid, fallbackPass) },
                        300L
                    )
                } else if (!fallbackSsid.isNullOrEmpty() && !fallbackPass.isNullOrEmpty()) {
                    activeGroupSsid = fallbackSsid
                    activeGroupPassphrase = fallbackPass
                    currentGoIp = P2pProtocol.ANDROID_GO_DEFAULT_IP
                    onGroupReady(fallbackSsid, fallbackPass, P2pProtocol.ANDROID_GO_DEFAULT_IP)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestGroupInfo failed: ${t.message}")
        }
    }

    private fun extractAndNotifyGroupCredentials(group: WifiP2pGroup) {
        val ssid = group.networkName ?: return
        val pass = group.passphrase ?: P2pProtocol.deriveP2pPassphrase(ssid)
        val alreadyNotified = (activeGroupSsid == ssid && activeGroupPassphrase == pass)
        activeGroupSsid = ssid
        activeGroupPassphrase = pass
        currentGoIp = P2pProtocol.ANDROID_GO_DEFAULT_IP
        if (!alreadyNotified) {
            onGroupReady(ssid, pass, P2pProtocol.ANDROID_GO_DEFAULT_IP)
        }
    }

    private fun registerBroadcastReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(p2pReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(p2pReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "registerBroadcastReceiver failed: ${t.message}")
        }
    }

    private fun unregisterBroadcastReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.applicationContext.unregisterReceiver(p2pReceiver)
        } catch (_: Throwable) {
        }
        isReceiverRegistered = false
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val mgr = wifiP2pManager ?: return
            val ch = channel ?: return
            try {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        mgr.requestPeers(ch) { peerList ->
                            if (peerList != null) {
                                onPeersDiscovered(peerList.deviceList.toList())
                            }
                        }
                    }
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        // Parse cached scan results without calling startScan() again!
                        parseCachedDirectHotspots(force = false)
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                            if (info != null && info.groupFormed) {
                                isConnectingOrConnected = true
                                val goIp = info.groupOwnerAddress?.hostAddress
                                    ?: currentGoIp
                                    ?: P2pProtocol.ANDROID_GO_DEFAULT_IP
                                currentGoIp = goIp
                                if (info.isGroupOwner) {
                                    requestGroupCredentialsWithRetry()
                                }
                                onConnectedToGo(goIp, info.isGroupOwner)
                            } else {
                                if (activeGroupSsid != null || currentGoIp != null) {
                                    clearActiveState()
                                    onDisconnected()
                                } else {
                                    isConnectingOrConnected = false
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "p2pReceiver error: ${t.message}")
            }
        }
    }
}
