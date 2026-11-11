package com.connectp2p.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.connectp2p.app.protocol.P2pProtocol
import java.nio.charset.StandardCharsets

/**
 * Loop-Safe Android Clipboard Synchronizer (Android 8.0 ~ Android 14+).
 *
 * Battery & Android 10+ Privacy Compliance:
 * 1. Does NOT use a background polling thread or battery-draining AccessibilityService!
 * 2. Registers event-driven `OnPrimaryClipChangedListener` + foreground focus hook (`onAppWindowFocused()`).
 * 3. Provides a 1-tap Quick Settings Tile (`ClipboardTileService`) and Share Target (`ACTION_SEND`)
 *    for instant background clipboard push from any app on Android 10+.
 * 4. Deduplicates clipboard content via `lastAppliedHash` (SHA-256 prefix) to prevent infinite A->B->A echo loops.
 */
class ClipboardSyncHelper(
    context: Context,
    private val onLocalClipboardChanged: (text: String, clipHash: String) -> Unit
) {
    companion object {
        private const val TAG = "ConnectP2P.Clipboard"
    }

    private val appContext = context.applicationContext
    private val clipboardManager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastAppliedHash: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        inspectAndNotifyIfChanged()
    }

    fun start() {
        // Baseline = whatever is already on the clipboard, so only text copied from now on is pushed.
        try {
            clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(appContext)?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?.let { lastAppliedHash = P2pProtocol.computeClipHash(it.toByteArray(StandardCharsets.UTF_8)) }
        } catch (_: Exception) {
        }
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
    }

    /**
     * Called when Activity gains window focus (`onWindowFocusChanged(true)`) or when
     * the Quick Settings Tile (`ClipboardTileService`) is tapped.
     * On Android 10+ (API 29+), reading clipboard is allowed as soon as the app or tile has focus.
     */
    fun inspectAndNotifyIfChanged(forceNotify: Boolean = false): Boolean {
        return try {
            val clip: ClipData = clipboardManager.primaryClip ?: return false
            if (clip.itemCount == 0) return false
            val text = clip.getItemAt(0).coerceToText(appContext)?.toString() ?: return false
            if (text.isEmpty()) return false

            val hash = P2pProtocol.computeClipHash(text.toByteArray(StandardCharsets.UTF_8))
            synchronized(this) {
                if (!forceNotify && hash == lastAppliedHash) {
                    return false // Suppress echo loop
                }
                lastAppliedHash = hash
            }
            onLocalClipboardChanged(text, hash)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Clipboard read skipped (background restriction): ${e.message}")
            false
        }
    }

    /**
     * Apply clipboard text received from a remote Android or Windows peer.
     * Note: Android allows background services to WRITE (`setPrimaryClip`) to the clipboard
     * on all Android versions without focus!
     */
    fun applyRemoteClipboard(text: String, remoteHash: String? = null): Boolean {
        val hash = remoteHash ?: P2pProtocol.computeClipHash(text.toByteArray(StandardCharsets.UTF_8))
        synchronized(this) {
            if (hash == lastAppliedHash) return false
            lastAppliedHash = hash
        }
        mainHandler.post {
            try {
                val clipData = ClipData.newPlainText("ConnectP2P", text)
                clipboardManager.setPrimaryClip(clipData)
                Log.i(TAG, "Applied remote clipboard (hash=$hash, len=${text.length})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing to Android clipboard: ${e.message}")
            }
        }
        return true
    }
}
