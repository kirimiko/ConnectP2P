package com.connectp2p.app.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Joins a Windows Mobile Hotspot (`DIRECT-CP-*`, normally 192.168.137.1) as a local-only Wi-Fi network
 * via [WifiNetworkSpecifier].
 *
 * On phones that support STA+STA concurrency for local-only connections the hotspot is joined on a
 * secondary interface, so the phone's own Wi-Fi stays connected. The network is NOT the default network,
 * so every socket that talks to the hotspot subnet must be bound with [networkFor] / [Network.bindSocket].
 *
 * The system shows a one-time approval dialog per SSID; afterwards requests are approved silently.
 * Requests are only honored while the app is in the foreground (activity visible or foreground service).
 */
class HotspotLinkManager(
    context: Context,
    private val onLinkUp: (ssid: String, gatewayIp: String) -> Unit,
    private val onLinkDown: (ssid: String, wasUp: Boolean) -> Unit,
    private val onLogMessage: (String) -> Unit
) {
    companion object {
        private const val TAG = "ConnectP2P.Hotspot"
        // The first request per SSID shows a system approval dialog; the timeout also bounds that dialog.
        private const val REQUEST_TIMEOUT_MS = 90_000
    }

    enum class State { IDLE, REQUESTING, UP }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var ssid: String? = null
        private set

    @Volatile
    var network: Network? = null
        private set

    @Volatile
    var gatewayIp: String? = null
        private set

    @Volatile
    private var subnetPrefix: Int = 24

    @Volatile
    private var subnetBase: ByteArray? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    val isUp: Boolean get() = state == State.UP && network != null

    fun supportsConcurrentLocalOnly(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported

    /** Returns the hotspot [Network] if [ip] lives on the hotspot subnet, so callers can bind sockets to it. */
    fun networkFor(ip: String): Network? {
        val net = network ?: return null
        val base = subnetBase ?: return null
        val addr = parseIpv4(ip) ?: return null
        return if (sameSubnet(addr, base, subnetPrefix)) net else null
    }

    fun connect(targetSsid: String, passphrase: String, expectedGatewayIp: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onLogMessage("[连接电脑热点] 需要 Android 10 及以上系统")
            return
        }
        if (ssid == targetSsid && state != State.IDLE) {
            if (state == State.UP) {
                onLinkUp(targetSsid, gatewayIp ?: expectedGatewayIp)
            }
            return
        }
        disconnect()

        ssid = targetSsid
        state = State.REQUESTING
        gatewayIp = expectedGatewayIp
        parseIpv4(expectedGatewayIp)?.let { subnetBase = it; subnetPrefix = 24 }

        val concurrency = supportsConcurrentLocalOnly()
        onLogMessage(
            if (concurrency) "[连接电脑热点] 正在接入 $targetSsid (使用副 Wi-Fi 通道，手机当前 Wi-Fi 不受影响)..."
            else "[连接电脑热点] 正在接入 $targetSsid (本机不支持双 Wi-Fi 并发，接入期间手机会暂时离开当前 Wi-Fi，移动数据不受影响)..."
        )
        Log.i(TAG, "requestNetwork ssid=$targetSsid concurrency=$concurrency")

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(targetSsid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                Log.i(TAG, "onAvailable $net for $targetSsid")
                network = net
                cm.getLinkProperties(net)?.let { applyLinkProperties(it) }
                state = State.UP
                val gw = gatewayIp ?: expectedGatewayIp
                mainHandler.post {
                    if (callback === this) onLinkUp(targetSsid, gw)
                }
            }

            override fun onLinkPropertiesChanged(net: Network, lp: LinkProperties) {
                if (net == network) applyLinkProperties(lp)
            }

            override fun onUnavailable() {
                Log.w(TAG, "onUnavailable for $targetSsid")
                mainHandler.post {
                    if (callback !== this) return@post
                    resetState()
                    onLogMessage("[连接电脑热点] 未能接入 $targetSsid（附近没有该热点、电脑端热点未开启，或系统连接弹窗被取消）")
                    onLinkDown(targetSsid, false)
                }
            }

            override fun onLost(net: Network) {
                Log.w(TAG, "onLost $net for $targetSsid")
                mainHandler.post {
                    if (callback !== this) return@post
                    resetState()
                    onLogMessage("[电脑热点已断开] $targetSsid 连接已断开")
                    onLinkDown(targetSsid, true)
                }
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb, REQUEST_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "requestNetwork failed: ${t.message}", t)
            callback = null
            resetState()
            onLogMessage("[连接电脑热点] 系统拒绝了连接请求: ${t.message}")
            onLinkDown(targetSsid, false)
        }
    }

    fun disconnect() {
        val cb = callback
        callback = null
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Throwable) {
            }
        }
        resetState()
    }

    private fun resetState() {
        state = State.IDLE
        network = null
        ssid = null
    }

    private fun applyLinkProperties(lp: LinkProperties) {
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
        subnetBase = v4.address.address
        subnetPrefix = v4.prefixLength
        val gw = lp.routes.firstOrNull { it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress else null
        gw?.hostAddress?.let { gatewayIp = it }
        Log.i(TAG, "link ${lp.interfaceName} addr=${v4.address.hostAddress}/${v4.prefixLength} gw=$gatewayIp")
    }

    private fun parseIpv4(ip: String): ByteArray? {
        if (ip.count { it == '.' } != 3) return null
        return try {
            InetAddress.getByName(ip).takeIf { it is Inet4Address }?.address
        } catch (_: Throwable) {
            null
        }
    }

    private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        var bits = prefix
        for (i in 0 until 4) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
