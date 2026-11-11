package com.connectp2p.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the service after boot and after an app update, so the PC can reach the phone over BLE without
 * the user opening the app first. connectedDevice foreground services may be started from these broadcasts.
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    context.startForegroundService(Intent(context, P2pTransferService::class.java))
                } catch (t: Throwable) {
                    Log.w("ConnectP2P.AutoStart", "Could not start service on ${intent.action}: ${t.message}")
                }
            }
        }
    }
}
