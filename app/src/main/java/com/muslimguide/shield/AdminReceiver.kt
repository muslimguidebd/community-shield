package com.muslimguide.shield

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "ShieldAdminReceiver"
        private const val MAIN_APP_PACKAGE = "com.muslimguide.bd"
        private const val RTDB_BASE_URL = "https://muslimguide-bd-default-rtdb.firebaseio.com"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, AdminReceiver::class.java)
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isDeviceOwnerApp(context.packageName) == true
        }

        fun applyProtectionPolicies(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val admin = getComponentName(context)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    // 1. Block Uninstalling Plugin & Main App
                    dpm.setUninstallBlocked(admin, context.packageName, true)
                    dpm.setUninstallBlocked(admin, MAIN_APP_PACKAGE, true)

                    // 2. Block Factory Reset & Safe Mode
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)

                    // 3. DNS is managed dynamically by ShieldService via devices/{deviceToken}.
                    //    On first boot, read cached pref (default: ON) before cloud sync arrives.
                    val prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    val isPremium = prefs.getBoolean("is_premium", false)
                    val filterEnabled = prefs.getBoolean("adult_filter_enabled", true)
                    if (isPremium && filterEnabled) {
                        try {
                            dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
                            dpm.setGlobalSetting(admin, "private_dns_specifier", "adult-filter-dns.cleanbrowsing.org")
                        } catch (_: Exception) {}
                    }

                    Log.d(TAG, "Device Owner protection policies applied")
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying protection policies", e)
                }
            }
        }

        fun relinquishDeviceOwner(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val admin = getComponentName(context)
            try {
                dpm.setUninstallBlocked(admin, context.packageName, false)
                dpm.setUninstallBlocked(admin, MAIN_APP_PACKAGE, false)
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.d(TAG, "Device Owner surrendered successfully for self-removal")
            } catch (e: Exception) {
                Log.e(TAG, "Error relinquishing device owner", e)
            }
        }

        /**
         * Derives the MG-XXXX-XXXX device token from ANDROID_ID.
         * Must match the algorithm in ShieldService and MuslimCommunityActivity.
         */
        fun getDeviceToken(context: Context): String {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "DEVICE001"
            val hash = (Math.abs(androidId.hashCode().toLong()) % 90000000 + 10000000).toString()
            return "MG-${hash.substring(0, 4)}-${hash.substring(4, 8)}"
        }

        /**
         * Clears device registration in RTDB when shield is unlocked or self-destructs.
         */
        fun unregisterDeviceFromFirebase(context: Context) {
            val childToken = getDeviceToken(context)
            val prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
            val guardianUid = prefs.getString("guardian_uid", null)

            kotlin.concurrent.thread(name = "ShieldUnregisterThread") {
                try {
                    val now = System.currentTimeMillis()
                    // 1. Update devices/{childToken}
                    val devUrl = java.net.URL("$RTDB_BASE_URL/devices/$childToken.json")
                    val devConn = devUrl.openConnection() as java.net.HttpURLConnection
                    devConn.requestMethod = "PATCH"
                    devConn.setRequestProperty("Content-Type", "application/json")
                    devConn.doOutput = true
                    devConn.outputStream.bufferedWriter().use {
                        it.write("""{"status":"UNLOCKED","unlockedAt":$now}""")
                    }
                    devConn.responseCode
                    devConn.disconnect()

                    // 2. Update guardian_devices/{guardianUid} if linked
                    if (!guardianUid.isNullOrBlank()) {
                        val gUrl = java.net.URL("$RTDB_BASE_URL/guardian_devices/$guardianUid.json")
                        val gConn = gUrl.openConnection() as java.net.HttpURLConnection
                        gConn.requestMethod = "PATCH"
                        gConn.setRequestProperty("Content-Type", "application/json")
                        gConn.doOutput = true
                        gConn.outputStream.bufferedWriter().use {
                            it.write("""{"status":"UNLOCKED","unlockedAt":$now}""")
                        }
                        gConn.responseCode
                        gConn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister device from RTDB: ${e.message}")
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.muslimguide.shield.ACTION_SELF_DESTRUCT" -> {
                Log.d(TAG, "Received ACTION_SELF_DESTRUCT broadcast")
                unregisterDeviceFromFirebase(context)
                relinquishDeviceOwner(context)
                return
            }
            "com.muslimguide.shield.ACTION_LINK_GUARDIAN" -> {
                val guardianUid = intent.getStringExtra("guardian_uid")
                Log.d(TAG, "Received ACTION_LINK_GUARDIAN broadcast with UID: $guardianUid")
                if (!guardianUid.isNullOrBlank()) {
                    linkChildToGuardian(context, guardianUid)
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Community Shield Device Admin Enabled")
        applyProtectionPolicies(context)
        ShieldToast.showSuccess(context, "স্থায়ী সুরক্ষা সফলভাবে সক্রিয় হয়েছে", title = "কমিউনিটি শিল্ড")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Community Shield Device Admin Disabled")
        unregisterDeviceFromFirebase(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile provisioning complete — reading guardian extras")
        applyProtectionPolicies(context)

        // Read permanent guardian UID passed via PROVISIONING_ADMIN_EXTRAS_BUNDLE
        val extras = intent.getBundleExtra("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
        val guardianUid = extras?.getString("guardian_uid")

        if (!guardianUid.isNullOrBlank()) {
            linkChildToGuardian(context, guardianUid)
        } else {
            Log.d(TAG, "No guardian extras in provisioning — standalone setup")
        }
    }

    /**
     * Enforces the single-device-per-account rule:
     * 1. Checks guardian_devices/{guardianUid}.
     *    - If an active device ALREADY exists with a different token and status == "ACTIVE":
     *      REJECTS installation, relinquishes device owner, and notifies user.
     * 2. If no active device or previous was unlocked:
     *    - Registers this device as the single active device under guardian_devices/{guardianUid}.
     *    - Bootstraps devices/{childToken} for premium & DNS sync.
     */
    private fun linkChildToGuardian(context: Context, guardianUid: String) {
        val childToken  = getDeviceToken(context)
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        kotlin.concurrent.thread(name = "ShieldGuardianLinkThread") {
            try {
                // 1. Verify single-device constraint from guardian_devices/{guardianUid}
                val checkUrl = "$RTDB_BASE_URL/guardian_devices/$guardianUid.json"
                val checkConn = java.net.URL(checkUrl).openConnection() as java.net.HttpURLConnection
                checkConn.requestMethod = "GET"
                checkConn.connectTimeout = 8000
                checkConn.readTimeout = 8000

                var alreadyActiveDevice: String? = null
                if (checkConn.responseCode == 200) {
                    val body = checkConn.inputStream.bufferedReader().readText()
                    if (!body.isNullOrBlank() && body != "null") {
                        val json = org.json.JSONObject(body)
                        val activeToken = json.optString("activeToken", "")
                        val status = json.optString("status", "")
                        if (status.equals("ACTIVE", ignoreCase = true) && activeToken.isNotBlank() && activeToken != childToken) {
                            alreadyActiveDevice = json.optString("deviceModel", activeToken)
                        }
                    }
                }
                checkConn.disconnect()

                // Reject if another device is currently active on this account
                if (alreadyActiveDevice != null) {
                    Log.w(TAG, "Account already has an active device: $alreadyActiveDevice. Rejecting setup.")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        ShieldToast.showError(
                            context,
                            "এই অ্যাকাউন্টে ইতিমধ্যে অন্য একটি ডিভাইস ($alreadyActiveDevice) সক্রিয় রয়েছে! ডিভাইস পরিবর্তন করতে হলে মূল অ্যাপ থেকে আগে আনলক আবেদন করুন।",
                            title = "অন্য ডিভাইস সক্রিয়"
                        )
                        relinquishDeviceOwner(context)
                    }
                    return@thread
                }

                val now = System.currentTimeMillis()

                // 2. Set this device as the single active device for the guardian
                val linkUrl = "$RTDB_BASE_URL/guardian_devices/$guardianUid.json"
                val http = java.net.URL(linkUrl).openConnection() as java.net.HttpURLConnection
                http.requestMethod = "PUT"
                http.setRequestProperty("Content-Type", "application/json")
                http.doOutput = true
                val payload = """{"activeToken":"$childToken","deviceModel":"$deviceModel","status":"ACTIVE","linkedAt":$now,"updatedAt":$now}"""
                http.outputStream.bufferedWriter().use { it.write(payload) }
                val responseCode = http.responseCode
                http.disconnect()
                Log.d(TAG, "Guardian single-device link written: HTTP $responseCode")

                // Cache guardianUid locally so service/receiver can unregister later
                context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("guardian_uid", guardianUid)
                    .apply()

                // 3. Bootstrap devices/{childToken} so ShieldService can read premium & settings
                val devUrl = java.net.URL("$RTDB_BASE_URL/devices/$childToken.json")
                val conn3 = devUrl.openConnection() as java.net.HttpURLConnection
                conn3.requestMethod = "PATCH"
                conn3.setRequestProperty("Content-Type", "application/json")
                conn3.doOutput = true
                val devPayload = """{"guardianUid":"$guardianUid","deviceModel":"$deviceModel","status":"ACTIVE","isPremium":false,"adultFilterEnabled":true,"updatedAt":$now}"""
                conn3.outputStream.bufferedWriter().use { it.write(devPayload) }
                conn3.disconnect()

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    ShieldToast.showSuccess(
                        context,
                        "গার্ডিয়ান অ্যাকাউন্টের সাথে স্থায়ী সুরক্ষা যুক্ত হয়েছে",
                        title = "গার্ডিয়ান লিংক সম্পন্ন"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to link child to guardian: ${e.message}")
            }
        }
    }
}
