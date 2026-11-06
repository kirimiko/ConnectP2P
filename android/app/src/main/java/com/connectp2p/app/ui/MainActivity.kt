package com.connectp2p.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.connectp2p.app.R
import com.connectp2p.app.protocol.P2pProtocol
import com.connectp2p.app.service.P2pTransferService
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity(), P2pTransferService.ServiceUiCallback {

    companion object {
        private const val REQ_PICK_FILES = 2001
        private const val REQ_PERMISSIONS = 1001
    }

    private var transferService: P2pTransferService? = null
    private var isBound = false
    private var pendingFileTargetPeer: P2pProtocol.PeerInfo? = null

    private lateinit var etDeviceName: EditText
    private lateinit var tvPowerState: TextView
    private lateinit var tvConnectedPeer: TextView
    private lateinit var tvGroupCredentials: TextView
    private lateinit var lvPeers: ListView
    private lateinit var etTargetIp: EditText
    private lateinit var etPeerSsid: EditText
    private lateinit var etPeerPassphrase: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView

    private val displayedPeers = mutableListOf<P2pProtocol.PeerInfo>()
    private lateinit var peerListAdapter: BaseAdapter

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? P2pTransferService.LocalBinder ?: return
            val svc = localBinder.getService()
            transferService = svc
            svc.uiCallback = this@MainActivity
            isBound = true
            etDeviceName.setText(svc.deviceName)
            onPowerStateChanged(svc.powerPolicy.currentState)
            onPeersUpdated(svc.getPeerList())
            onConnectedPeerChanged(svc.connectedPeer)
            svc.scanNearbyDevices()
            handleIncomingShareIntent(intent)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService?.uiCallback = null
            transferService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etDeviceName = findViewById(R.id.etDeviceName)
        tvPowerState = findViewById(R.id.tvPowerState)
        tvConnectedPeer = findViewById(R.id.tvConnectedPeer)
        tvGroupCredentials = findViewById(R.id.tvGroupCredentials)
        lvPeers = findViewById(R.id.lvPeers)
        etTargetIp = findViewById(R.id.etTargetIp)
        etPeerSsid = findViewById(R.id.etPeerSsid)
        etPeerPassphrase = findViewById(R.id.etPeerPassphrase)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)

        peerListAdapter = object : BaseAdapter() {
            override fun getCount(): Int = if (displayedPeers.isEmpty()) 1 else displayedPeers.size

            override fun getItem(position: Int): Any? =
                if (position in displayedPeers.indices) displayedPeers[position] else null

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val density = resources.displayMetrics.density
                val padH = (10 * density).toInt()
                val padV = (6 * density).toInt()

                if (displayedPeers.isEmpty()) {
                    return TextView(this@MainActivity).apply {
                        text = "暂未发现设备 — 请点击『🔍 扫描周围设备』或在另一端开启热点"
                        setTextColor(0xFFAAAAAA.toInt())
                        textSize = 12f
                        setPadding(padH, padV * 2, padH, padV * 2)
                        setOnClickListener { transferService?.scanNearbyDevices() }
                    }
                }

                val peer = displayedPeers[position]
                val svc = transferService
                val isTarget = (svc?.connectedPeer?.deviceId == peer.deviceId && peer.isConnected)
                val isTrusted = svc?.isTrustedPeer(peer) == true
                val icon = if (peer.platform == "android") "📱" else "💻"
                val star = if (isTrusted) "★" else "[陌生] "

                val statusText = when {
                    isTarget -> "🎯 当前目标 (已连接 ${peer.ip ?: "直连"}) · 长按可取消信任"
                    peer.isConnected -> "✅ 已连接 (${peer.ip ?: "直连"}) · 点击设为目标"
                    isTrusted -> "⚪ 已断开 (${peer.ip ?: "待连接"}) · ★已信任设备"
                    peer.discoverySource == "p2p_scan" -> "⚪ 陌生设备 · Wi-Fi直连热点"
                    else -> "⚪ 陌生设备 (${peer.ip ?: "待连接"}) · 连接需对方同意"
                }

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                }

                val infoLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        peer.ip?.let { etTargetIp.setText(it) }
                        if (peer.isConnected) {
                            transferService?.selectTargetPeer(peer)
                            Toast.makeText(
                                this@MainActivity,
                                "已指定 ${peer.deviceName} 为当前发送目标",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            transferService?.connectToPeerAndSync(peer)
                            val msg = if (isTrusted) {
                                "正在连接信任设备 ${peer.deviceName}..."
                            } else {
                                "已向 ${peer.deviceName} 发送连接请求，等待对方同意..."
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    setOnLongClickListener {
                        if (isTrusted) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("取消信任该设备")
                                .setMessage("是否取消对「${peer.deviceName}」的信任？取消后下次连接需手动同意。")
                                .setPositiveButton("取消信任") { _, _ ->
                                    transferService?.forgetPeerTrust(peer.deviceId)
                                }
                                .setNegativeButton("保留", null)
                                .show()
                        }
                        true
                    }
                }

                val titleTv = TextView(this@MainActivity).apply {
                    text = "$icon $star${peer.deviceName}"
                    setTextColor(if (isTarget) 0xFF4FC1FF.toInt() else 0xFFEFEFEF.toInt())
                    textSize = 13f
                    paint.isFakeBoldText = true
                }

                val subTv = TextView(this@MainActivity).apply {
                    text = statusText
                    setTextColor(if (peer.isConnected) 0xFF6A9955.toInt() else 0xFFAAAAAA.toInt())
                    textSize = 11f
                }

                infoLayout.addView(titleTv)
                infoLayout.addView(subTv)
                row.addView(infoLayout)

                // Per-device button 1: Send File specifically to this device
                val btnSendToPeer = Button(this@MainActivity).apply {
                    text = "📤 发文件"
                    textSize = 11f
                    minimumWidth = 0
                    minWidth = 0
                    minimumHeight = 0
                    minHeight = 0
                    setPadding(padH, padV, padH, padV)
                    setOnClickListener {
                        if (!peer.isConnected) {
                            transferService?.connectToPeerAndSync(peer)
                        } else {
                            transferService?.selectTargetPeer(peer)
                        }
                        launchFilePickerForPeer(peer)
                    }
                }
                row.addView(btnSendToPeer)

                // Per-device button 2: Connect or Disconnect THIS specific device
                val btnToggleConn = Button(this@MainActivity).apply {
                    text = when {
                        peer.isConnected -> "🔌 断开"
                        isTrusted -> "🔗 连接"
                        else -> "🔗 请求连接"
                    }
                    textSize = 11f
                    minimumWidth = 0
                    minWidth = 0
                    minimumHeight = 0
                    minHeight = 0
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (6 * density).toInt()
                    }
                    layoutParams = lp
                    setPadding(padH, padV, padH, padV)
                    setOnClickListener {
                        if (peer.isConnected) {
                            transferService?.disconnectPeer(peer.deviceId)
                            Toast.makeText(
                                this@MainActivity,
                                "已单独断开 ${peer.deviceName}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            peer.ip?.let { etTargetIp.setText(it) }
                            transferService?.connectToPeerAndSync(peer)
                            val msg = if (isTrusted) {
                                "正在连接 ${peer.deviceName}..."
                            } else {
                                "已向陌生设备 ${peer.deviceName} 发送连接请求，请在对端点击同意"
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                row.addView(btnToggleConn)

                return row
            }
        }
        lvPeers.adapter = peerListAdapter

        findViewById<Button>(R.id.btnSaveName).setOnClickListener {
            val newName = etDeviceName.text.toString().trim()
            if (newName.isNotEmpty()) {
                transferService?.updateDeviceName(newName)
                Toast.makeText(this, "设备名称已保存: $newName", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnScanPeers).setOnClickListener {
            requestNecessaryPermissions()
            transferService?.scanNearbyDevices()
        }

        findViewById<Button>(R.id.btnStartGo).setOnClickListener {
            requestNecessaryPermissions()
            val curName = etDeviceName.text.toString().trim()
            if (curName.isNotEmpty()) {
                transferService?.updateDeviceName(curName)
            }
            transferService?.startBurstGroupOnDemand()
        }

        findViewById<Button>(R.id.btnConnectPeerGo).setOnClickListener {
            val ssid = etPeerSsid.text.toString().trim()
            val rawPass = etPeerPassphrase.text.toString().trim()
            val pass = rawPass.ifEmpty {
                if (ssid.startsWith("DIRECT-CP-")) P2pProtocol.deriveP2pPassphrase(ssid) else ""
            }
            val ip = etTargetIp.text.toString().trim().ifEmpty {
                if (ssid.contains("WIN", ignoreCase = true)) P2pProtocol.WINDOWS_GO_DEFAULT_IP
                else P2pProtocol.ANDROID_GO_DEFAULT_IP
            }
            etTargetIp.setText(ip)
            if (ssid.isNotEmpty() && pass.isNotEmpty()) {
                transferService?.connectToPeerBurstGroup(ssid, pass, ip)
            } else {
                transferService?.requestPeerToWakeP2p(ip)
            }
        }

        findViewById<Button>(R.id.btnTeardownP2p).setOnClickListener {
            transferService?.disconnectCurrentPeer()
            tvGroupCredentials.text = "本机直连热点: 未开启 (已断开连接，回归 DEEP_IDLE 超低功耗待机)"
            Toast.makeText(this, "已断开当前设备连接", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnPickFiles).setOnClickListener {
            promptTargetPeerThenPickFiles()
        }

        findViewById<Button>(R.id.btnPushClipboard).setOnClickListener {
            val svc = transferService ?: return@setOnClickListener
            val target = svc.connectedPeer
            val ip = etTargetIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                svc.targetPeerIp = ip
            }
            val pushed = svc.clipboardHelper.inspectAndNotifyIfChanged(forceNotify = true)
            if (pushed) {
                val targetName = target?.deviceName ?: ip
                Toast.makeText(this, "已向 $targetName 定向发送剪贴板", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "当前系统剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        requestNecessaryPermissions()

        val svcIntent = Intent(this, P2pTransferService::class.java)
        startService(svcIntent)
        bindService(svcIntent, serviceConn, Context.BIND_AUTO_CREATE)
    }

    private fun promptTargetPeerThenPickFiles() {
        val svc = transferService
        val peers = displayedPeers.toList()
        if (peers.size > 1) {
            val labels = peers.map { p ->
                val tag = if (p.deviceId == svc?.connectedPeer?.deviceId) " [当前目标]" else if (p.isConnected) " [已连接]" else ""
                "${p.deviceName} (${p.ip ?: p.p2pSsid ?: "直连"})$tag"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("选择要接收文件的设备")
                .setItems(labels) { _, which ->
                    val chosen = peers[which]
                    svc?.selectTargetPeer(chosen)
                    launchFilePickerForPeer(chosen)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            val target = svc?.connectedPeer ?: peers.firstOrNull()
            if (target != null && !target.isConnected) {
                svc?.connectToPeerAndSync(target)
            }
            launchFilePickerForPeer(target)
        }
    }

    private fun launchFilePickerForPeer(targetPeer: P2pProtocol.PeerInfo?) {
        pendingFileTargetPeer = targetPeer
        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pickIntent, REQ_PICK_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILES && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                sendSharedUris(uris, explicitTarget = pendingFileTargetPeer)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isBound) {
            handleIncomingShareIntent(intent)
        }
    }

    /**
     * Android 10+ allows clipboard reads only when the app window has focus.
     * Automatically inspect and sync clipboard on focus gain with zero background polling!
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val svc = transferService ?: return
            if (!svc.isManuallyDisconnected && svc.connectedPeer != null) {
                svc.clipboardHelper.inspectAndNotifyIfChanged()
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            transferService?.uiCallback = null
            unbindService(serviceConn)
            isBound = false
        }
        super.onDestroy()
    }

    private val logLines = ArrayDeque<String>(80)

    override fun onLog(message: String) {
        runOnUiThread {
            if (logLines.size >= 60) {
                logLines.removeFirst()
            }
            logLines.addLast(message)
            tvLogs.text = logLines.joinToString("\n", postfix = "\n")
            scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onPowerStateChanged(state: P2pProtocol.PowerState) {
        runOnUiThread {
            when (state) {
                P2pProtocol.PowerState.DEEP_IDLE -> {
                    tvPowerState.text = "● 功耗状态: DEEP_IDLE (超低功耗待机 | Wi-Fi P2P关闭 | 无唤醒锁)"
                    tvPowerState.setTextColor(0xFF1B7F37.toInt())
                }
                P2pProtocol.PowerState.P2P_HANDSHAKE -> {
                    tvPowerState.text = "◐ 功耗状态: P2P_HANDSHAKE (正在按需建立 Wi-Fi P2P 直连...)"
                    tvPowerState.setTextColor(0xFFB58900.toInt())
                }
                P2pProtocol.PowerState.P2P_BURST_ACTIVE -> {
                    tvPowerState.text = "⚡ 功耗状态: P2P_BURST_ACTIVE (高速传输中 | 闲置15s自动断链省电)"
                    tvPowerState.setTextColor(0xFFD9534F.toInt())
                }
            }
        }
    }

    override fun onP2pGroupCredentialsReady(ssid: String, passphrase: String, goIp: String) {
        runOnUiThread {
            tvGroupCredentials.text = "本机热点已开启: 对端扫描即可免密连接 (SSID=$ssid | 备用密码=$passphrase)"
        }
    }

    override fun onPeersUpdated(peers: List<P2pProtocol.PeerInfo>) {
        runOnUiThread {
            displayedPeers.clear()
            displayedPeers.addAll(peers)
            peerListAdapter.notifyDataSetChanged()
        }
    }

    override fun onConnectedPeerChanged(peer: P2pProtocol.PeerInfo?) {
        runOnUiThread {
            if (peer != null) {
                val icon = if (peer.platform == "android") "📱" else "💻"
                tvConnectedPeer.text = "🎯 当前指定目标: $icon ${peer.deviceName} (${peer.ip ?: "直连中"}) — 定向发文件 & 剪贴板同步中"
                tvConnectedPeer.setTextColor(0xFF005FB8.toInt())
                peer.ip?.let { etTargetIp.setText(it) }
            } else {
                val manual = transferService?.isManuallyDisconnected == true
                tvConnectedPeer.text = if (manual) {
                    "🔌 当前无连接设备 (在下方设备列表点击『🔗 连接』即可连接指定设备)"
                } else {
                    "当前连接: 暂未连接设备 (发现 ★已信任设备 将自动重连，或点击下方『🔗 连接』)"
                }
                tvConnectedPeer.setTextColor(0xFF888888.toInt())
            }
            peerListAdapter.notifyDataSetChanged()
        }
    }

    override fun onTransferProgress(fileName: String, transferred: Long, total: Long, isSending: Boolean) {
        runOnUiThread {
            val pct = if (total > 0) ((transferred * 100L) / total).toInt() else 100
            progressBar.progress = pct
            val dir = if (isSending) "↑发送" else "↓接收"
            tvProgress.text = "$dir $fileName: $pct% (${transferred / 1024} / ${total / 1024} KB)"
        }
    }

    override fun onPairRequestReceived(peer: P2pProtocol.PeerInfo) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle("收到陌生设备连接请求")
                .setMessage(
                    "陌生设备「${peer.deviceName}」(${peer.ip ?: "直连"}) 请求与本机建立连接并同步剪贴板/传输文件。\n\n" +
                        "是否同意连接并将其加入 ★已信任设备？"
                )
                .setPositiveButton("✅ 同意并信任") { _, _ ->
                    transferService?.respondToPairRequest(peer.deviceId, accepted = true)
                    Toast.makeText(this, "已同意连接 ${peer.deviceName}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("❌ 拒绝") { _, _ ->
                    transferService?.respondToPairRequest(peer.deviceId, accepted = false)
                    Toast.makeText(this, "已拒绝 ${peer.deviceName} 的连接请求", Toast.LENGTH_SHORT).show()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun handleIncomingShareIntent(shareIntent: Intent?) {
        val action = shareIntent?.action ?: return
        val destIp = etTargetIp.text.toString().trim().ifEmpty { P2pProtocol.ANDROID_GO_DEFAULT_IP }
        transferService?.targetPeerIp = destIp

        if (action == Intent.ACTION_SEND) {
            val sharedText = shareIntent.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION")
            val sharedStream = shareIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (sharedStream != null) {
                sendSharedUris(listOf(sharedStream), explicitTarget = transferService?.connectedPeer)
            } else if (!sharedText.isNullOrEmpty()) {
                transferService?.syncClipboardSmart(sharedText, targetPeer = transferService?.connectedPeer)
            }
            shareIntent.action = null
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val uris = shareIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (!uris.isNullOrEmpty()) {
                sendSharedUris(uris, explicitTarget = transferService?.connectedPeer)
            }
            shareIntent.action = null
        }
    }

    private fun sendSharedUris(uris: List<Uri>, explicitTarget: P2pProtocol.PeerInfo? = null) {
        val svc = transferService ?: return
        val target = explicitTarget ?: svc.connectedPeer
        val destIp = target?.ip ?: etTargetIp.text.toString().trim().ifEmpty { P2pProtocol.ANDROID_GO_DEFAULT_IP }
        svc.targetPeerIp = destIp

        Thread {
            for (uri in uris) {
                val tempFile = copyUriToTempFile(uri)
                if (tempFile != null) {
                    svc.sendFileOnDemand(tempFile, destIp, targetPeer = target)
                }
            }
        }.start()
    }

    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            var displayName = "shared_${System.currentTimeMillis()}.bin"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIdx)
                }
            }
            val outDir = File(cacheDir, "outgoing_shares").apply { mkdirs() }
            val outFile = File(outDir, displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, P2pProtocol.SOCKET_BUFFER_SIZE)
                }
            }
            outFile
        } catch (e: Exception) {
            onLog("[读取文件失败] ${e.message}")
            null
        }
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }
}
