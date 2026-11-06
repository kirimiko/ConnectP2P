package com.connectp2p.app.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.connectp2p.app.protocol.P2pProtocol
import java.util.concurrent.atomic.AtomicInteger

/**
 * Battery & Power State Governor for Android.
 *
 * Strictly enforces zero battery impact when not transferring files:
 * 1. In `DEEP_IDLE`:
 *    - `WakeLock` and `WifiLock` are NEVER held (allowing CPU Doze and Wi-Fi DTIM sleep).
 *    - Wi-Fi P2P group and peer discovery are completely OFF.
 *    - Listens to `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` to suspend active discovery while screen is off.
 * 2. In `P2P_BURST_ACTIVE`:
 *    - Acquires `WIFI_MODE_FULL_LOW_LATENCY` (or `WIFI_MODE_FULL_HIGH_PERF`) and a timeout-bounded
 *      `PARTIAL_WAKE_LOCK` ONLY while `activeTransfers > 0`.
 *    - Starts a 15-second idle countdown (`P2P_IDLE_TEARDOWN_MS`) as soon as `activeTransfers` drops to 0.
 *    - When countdown expires, invokes `onIdleTeardown()` to destroy the Wi-Fi P2P Group and release all locks.
 */
class PowerPolicyManager(
    context: Context,
    private val idleTimeoutMs: Long = P2pProtocol.P2P_IDLE_TEARDOWN_MS,
    private val onStateChanged: (P2pProtocol.PowerState) -> Unit,
    private val onScreenStateChanged: (Boolean) -> Unit,
    private val onIdleTeardown: () -> Unit
) {
    companion object {
        private const val TAG = "ConnectP2P.Power"
        private const val MAX_WAKELOCK_HOLD_MS = 10 * 60 * 1000L // Hard 10-min safety ceiling
    }

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentState: P2pProtocol.PowerState = P2pProtocol.PowerState.DEEP_IDLE
        private set

    @Volatile
    var isScreenInteractive: Boolean = powerManager.isInteractive
        private set

    private val activeTransfers = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val idleTeardownRunnable = Runnable {
        if (activeTransfers.get() == 0) {
            Log.i(TAG, "Idle timeout (${idleTimeoutMs}ms) reached -> tearing down Wi-Fi P2P & releasing locks")
            releaseAllBurstLocks()
            updateState(P2pProtocol.PowerState.DEEP_IDLE)
            onIdleTeardown()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    Log.i(TAG, "Screen turned OFF -> pausing active scanning to preserve battery")
                    onScreenStateChanged(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    Log.i(TAG, "Screen turned ON -> resuming low-power listener")
                    onScreenStateChanged(true)
                }
            }
        }
    }

    fun startMonitoringScreenState() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(screenReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "registerReceiver for screen state failed: ${e.message}")
        }
    }

    fun stopMonitoringScreenState() {
        try {
            appContext.unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        forceDeepIdleNow()
    }

    fun onHandshakeStarted(handshakeTimeoutMs: Long = 120_000L) {
        mainHandler.removeCallbacks(idleTeardownRunnable)
        updateState(P2pProtocol.PowerState.P2P_HANDSHAKE)
        mainHandler.postDelayed(idleTeardownRunnable, maxOf(handshakeTimeoutMs, idleTimeoutMs))
    }

    fun onTransferStarted() {
        mainHandler.removeCallbacks(idleTeardownRunnable)
        val count = activeTransfers.incrementAndGet()
        if (count == 1) {
            acquireBurstLocks()
        }
        updateState(P2pProtocol.PowerState.P2P_BURST_ACTIVE)
    }

    fun onTransferFinished() {
        val remaining = activeTransfers.decrementAndGet().coerceAtLeast(0)
        activeTransfers.set(remaining)
        if (remaining == 0) {
            // Release CPU/Wi-Fi locks immediately after stream finishes, and schedule P2P group teardown
            releaseAllBurstLocks()
            mainHandler.removeCallbacks(idleTeardownRunnable)
            mainHandler.postDelayed(idleTeardownRunnable, idleTimeoutMs)
        }
    }

    fun touchBurstActivity(timeoutMs: Long = idleTimeoutMs) {
        updateState(P2pProtocol.PowerState.P2P_BURST_ACTIVE)
        if (activeTransfers.get() == 0) {
            mainHandler.removeCallbacks(idleTeardownRunnable)
            mainHandler.postDelayed(idleTeardownRunnable, timeoutMs)
        }
    }

    fun forceDeepIdleNow() {
        mainHandler.removeCallbacks(idleTeardownRunnable)
        activeTransfers.set(0)
        releaseAllBurstLocks()
        updateState(P2pProtocol.PowerState.DEEP_IDLE)
    }

    @Synchronized
    private fun acquireBurstLocks() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ConnectP2P::BurstTransferWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(MAX_WAKELOCK_HOLD_MS)
            }

            if (wifiLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockMode, "ConnectP2P::BurstWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
            Log.d(TAG, "Acquired burst WakeLock & Low-Latency WifiLock for active file transfer")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire burst locks: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseAllBurstLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            Log.d(TAG, "Released all WakeLock & WifiLock -> CPU/Wi-Fi free to enter Doze sleep")
        } catch (e: Exception) {
            Log.w(TAG, "Failed releasing locks: ${e.message}")
        }
    }

    private fun updateState(newState: P2pProtocol.PowerState) {
        if (currentState != newState) {
            currentState = newState
            mainHandler.post { onStateChanged(newState) }
        }
    }
}
