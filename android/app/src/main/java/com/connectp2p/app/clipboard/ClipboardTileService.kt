package com.connectp2p.app.clipboard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Android 10~17 Zero-Battery Quick Settings Tile ("同步剪贴板 (P2P)").
 *
 * Launches `ClipboardSyncActivity` (1x1 invisible translucent window) via `startActivityAndCollapse`
 * so the app briefly obtains legal window focus (< 50ms) to read `ClipboardManager.primaryClip`
 * even when ConnectP2P is in the background, without switching away from the user's current app!
 */
class ClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "同步剪贴板 (P2P)"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val actIntent = Intent(this, ClipboardSyncActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    45822,
                    actIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(actIntent)
            }
        } catch (_: Throwable) {
            try {
                startActivity(actIntent)
            } catch (_: Throwable) {
            }
        }
    }
}
