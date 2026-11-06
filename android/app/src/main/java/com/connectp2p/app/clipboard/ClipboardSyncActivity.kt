package com.connectp2p.app.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.connectp2p.app.service.P2pTransferService

/**
 * Translucent Focus-Acquiring Activity for 1-tap Background Clipboard Sync
 * from the Notification Shade Action or Quick Settings Tile on Android 10~17 (MagicOS).
 *
 * Why previous 1x1 window failed when clicked from the Notification Shade:
 * 1. On Android 12~17 / Honor MagicOS, collapsing the Notification Shade / Control Center
 *    takes ~400-700ms. While the shade is animating closed, `StatusBar` (`com.android.systemui`)
 *    still holds window focus, so calling `getPrimaryClip()` at 350ms gets denied by `ClipboardService`
 *    and returns `null`.
 * 2. By using a focusable translucent window (without `FLAG_NOT_TOUCH_MODAL`) and waiting until
 *    `hasWindowFocus() == true` + retrying every 50ms until `ClipboardService` updates `mFocusedUid`,
 *    it reliably reads the clipboard as soon as the shade finishes closing and then auto-finishes.
 */
class ClipboardSyncActivity : Activity() {

    companion object {
        private const val TAG = "ConnectP2P.ClipAct"
        private const val POLL_INTERVAL_MS = 50L
        private const val MAX_TOTAL_ATTEMPTS = 36 // up to 1.8s for notification shade collapse animation
    }

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false
    private var totalAttempts = 0
    private var focusedAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        // Request active window focus so WindowManager collapses the notification shade
        // and grants ClipboardService access to our UID.
        window?.apply {
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.05f
            params.gravity = Gravity.CENTER
            attributes = params
        }

        val density = resources.displayMetrics.density
        val rootLayout = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val pill = TextView(this).apply {
            text = "📋 正在同步剪贴板..."
            setTextColor(Color.WHITE)
            textSize = 13f
            val padH = (18 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0xDD252526.toInt())
                cornerRadius = 24 * density
            }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        rootLayout.addView(pill, lp)
        setContentView(rootLayout)
        rootLayout.requestFocus()

        // Collapse status bar / notification shade panels if system allows
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Throwable) {
        }

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, 35L)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (handled || isFinishing || isDestroyed) return
            totalAttempts++

            val focused = hasWindowFocus()
            if (focused) {
                focusedAttempts++
                val text = tryReadClipboardText()
                if (!text.isNullOrEmpty()) {
                    handled = true
                    val svcIntent = Intent(this@ClipboardSyncActivity, P2pTransferService::class.java).apply {
                        action = P2pTransferService.ACTION_PUSH_CLIPBOARD_NOW
                        putExtra(P2pTransferService.EXTRA_CLIPBOARD_TEXT, text)
                    }
                    startService(svcIntent)
                    val preview = text.replace("\r", " ").replace("\n", " ").take(24)
                    Toast.makeText(
                        applicationContext,
                        "已发送剪贴板: \"$preview\"",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.w(TAG, "Background clipboard synced (${text.length} chars) after $totalAttempts ticks")
                    finishSilently()
                    return
                }
            }

            if (totalAttempts < MAX_TOTAL_ATTEMPTS && focusedAttempts < 10) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            } else {
                handled = true
                Toast.makeText(
                    applicationContext,
                    "当前系统剪贴板为空",
                    Toast.LENGTH_SHORT
                ).show()
                finishSilently()
            }
        }
    }

    private fun tryReadClipboardText(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val item = clip.getItemAt(0)
            val txt = item.text?.toString() ?: item.coerceToText(this)?.toString()
            txt?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun finishSilently() {
        handler.removeCallbacksAndMessages(null)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
