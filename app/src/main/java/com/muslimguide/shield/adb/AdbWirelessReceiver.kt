package com.muslimguide.shield.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput

class AdbWirelessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AdbWirelessNotificationService.ACTION_SEND_PAIRING_CODE) {
            val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
            val code = remoteInputResults?.getCharSequence(AdbWirelessNotificationService.EXTRA_PAIRING_CODE)?.toString()
                ?: intent.getStringExtra(AdbWirelessNotificationService.EXTRA_PAIRING_CODE)

            val port = intent.getStringExtra(AdbWirelessNotificationService.EXTRA_PAIRING_PORT)
                ?: AdbWirelessNotificationService.pendingPairingPort

            if (!code.isNullOrBlank()) {
                val serviceIntent = Intent(context, AdbWirelessNotificationService::class.java).apply {
                    action = AdbWirelessNotificationService.ACTION_SEND_PAIRING_CODE
                    putExtra(AdbWirelessNotificationService.EXTRA_PAIRING_CODE, code.trim())
                    if (!port.isNullOrBlank()) {
                        putExtra(AdbWirelessNotificationService.EXTRA_PAIRING_PORT, port.trim())
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
