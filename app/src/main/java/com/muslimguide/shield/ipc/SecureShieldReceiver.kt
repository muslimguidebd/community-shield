package com.muslimguide.shield.ipc

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.muslimguide.shield.AdminReceiver
import com.muslimguide.shield.security.ShieldNativeCore
import org.json.JSONObject

class SecureShieldReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecureShieldReceiver"
        const val ACTION_SECURE_COMMAND = "com.muslimguide.shield.SECURE_IPC_COMMAND"
        const val EXTRA_PAYLOAD = "enc_payload"
        const val EXTRA_CALLER_PKG = "caller_pkg"

        const val CMD_STATUS_CHECK = "CMD_STATUS_CHECK"
        const val CMD_ENABLE_18_PLUS_FILTER = "CMD_ENABLE_18_PLUS_FILTER"
        const val CMD_DISABLE_18_PLUS_FILTER = "CMD_DISABLE_18_PLUS_FILTER"
        const val CMD_SELF_DESTRUCT = "CMD_SELF_DESTRUCT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECURE_COMMAND) return

        val callerPkg = intent.getStringExtra(EXTRA_CALLER_PKG) ?: ""
        if (!ShieldNativeCore.isAuthorizedCaller(context, callerPkg)) {
            Log.e(TAG, "Unauthorized caller blocked: $callerPkg")
            return
        }

        val encryptedPayload = intent.getStringExtra(EXTRA_PAYLOAD) ?: return
        val decryptedJsonString = ShieldNativeCore.decryptPayload(encryptedPayload)
        if (decryptedJsonString.isNullOrBlank()) {
            Log.e(TAG, "Decryption failed or forged packet")
            return
        }

        try {
            val json = JSONObject(decryptedJsonString)
            val command = json.optString("command", "")
            val timestamp = json.optLong("timestamp", 0L)

            // Replay attack defense: Reject commands older than 60 seconds
            val now = System.currentTimeMillis()
            if (Math.abs(now - timestamp) > 60_000) {
                Log.w(TAG, "Rejected expired IPC command (Time difference > 60s)")
                return
            }

            Log.d(TAG, "Secure IPC Command verified: $command")
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = AdminReceiver.getComponentName(context)

            when (command) {
                CMD_ENABLE_18_PLUS_FILTER -> {
                    if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                        val dns = ShieldNativeCore.getAdultDns()
                        try {
                            dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
                            dpm.setGlobalSetting(admin, "private_dns_specifier", dns)
                            Log.d(TAG, "CleanBrowsing adult filter activated securely")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed setting adult filter DNS", e)
                        }
                    }
                }
                CMD_DISABLE_18_PLUS_FILTER -> {
                    if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                        try {
                            dpm.setGlobalSetting(admin, "private_dns_mode", "off")
                            Log.d(TAG, "Adult filter DNS set to OFF as commanded")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed disabling adult filter DNS", e)
                        }
                    }
                }
                CMD_SELF_DESTRUCT -> {
                    Log.d(TAG, "Secure Self-Destruct command received from authenticated main app")
                    AdminReceiver.relinquishDeviceOwner(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing secure IPC command", e)
        }
    }
}
