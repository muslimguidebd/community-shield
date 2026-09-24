package com.muslimguide.shield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ShieldService : Service() {

    companion object {
        private const val TAG = "ShieldService"
        const val CHANNEL_ID = "community_shield_service_channel"
        const val NOTIFICATION_ID = 5001
        private const val RTDB_BASE_URL = "https://muslimguide-bd-default-rtdb.firebaseio.com"

        // SharedPrefs keys
        private const val PREFS_NAME = "shield_prefs"
        private const val PREF_IS_PREMIUM = "is_premium"
        private const val PREF_ADULT_FILTER = "adult_filter_enabled"

        // Poll intervals
        private const val POLL_APPROVAL_MS = 15_000L   // auto-delete check: every 15 s
        private const val POLL_PREMIUM_MS  = 60_000L   // premium sync: every 60 s (less urgent)

        fun start(context: Context) {
            val intent = Intent(context, ShieldService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShieldService::class.java)
            context.stopService(intent)
        }
    }

    private var isMonitoringApproval = false
    private var isMonitoringPremium  = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Restore DNS state from last known cache before cloud sync arrives
        restoreDnsFromPrefs()

        // Apply full device-owner policies (uninstall block, factory reset block, etc.)
        AdminReceiver.applyProtectionPolicies(this)

        // Start both monitoring threads
        startAutoDeleteMonitor()
        startPremiumSyncMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restoreDnsFromPrefs()
        AdminReceiver.applyProtectionPolicies(this)
        if (!isMonitoringApproval) startAutoDeleteMonitor()
        if (!isMonitoringPremium)  startPremiumSyncMonitor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun getDeviceToken(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE001"
        val hash = (Math.abs(androidId.hashCode().toLong()) % 90000000 + 10000000).toString()
        return "MG-${hash.substring(0, 4)}-${hash.substring(4, 8)}"
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * On service start / reboot, restore the Private DNS that was last set so there
     * is no gap before the cloud sync arrives.
     */
    private fun restoreDnsFromPrefs() {
        val adultFilterEnabled = getPrefs().getBoolean(PREF_ADULT_FILTER, true)
        setAdultFilterDns(adultFilterEnabled)
    }

    /**
     * Applies or removes CleanBrowsing adult-filter DNS via DevicePolicyManager.
     * Only takes effect if we are the device owner.
     */
    private fun setAdultFilterDns(enable: Boolean) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val admin = AdminReceiver.getComponentName(this)
            if (enable) {
                dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
                dpm.setGlobalSetting(admin, "private_dns_specifier", "adult-filter-dns.cleanbrowsing.org")
                Log.d(TAG, "Adult-filter DNS activated")
            } else {
                dpm.setGlobalSetting(admin, "private_dns_mode", "off")
                Log.d(TAG, "Adult-filter DNS deactivated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS update failed: ${e.message}")
        }
    }

    // ── Thread 1: Auto-Delete Approval Monitor ───────────────────────────────

    /**
     * Polls shield_approved/{deviceToken} every 15 s.
     * If admin sets approved=true, mode=AUTO → plugin self-destructs.
     */
    private fun startAutoDeleteMonitor() {
        if (isMonitoringApproval) return
        isMonitoringApproval = true

        thread(name = "ShieldApprovalThread") {
            val deviceToken = getDeviceToken()
            Log.d(TAG, "Approval monitor started for token: $deviceToken")

            while (isMonitoringApproval) {
                try {
                    val url = "$RTDB_BASE_URL/shield_approved/$deviceToken.json"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    if (conn.responseCode == 200) {
                        val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        if (!text.isNullOrBlank() && text != "null") {
                            val json    = JSONObject(text)
                            val approved = json.optBoolean("approved", false)
                            val mode     = json.optString("mode", "")

                            if (approved && mode.equals("AUTO", ignoreCase = true)) {
                                Log.d(TAG, "AUTO self-destruct approved — relinquishing device owner")
                                isMonitoringApproval = false
                                isMonitoringPremium  = false

                                mainHandler.post {
                                    AdminReceiver.unregisterDeviceFromFirebase(this@ShieldService)
                                    AdminReceiver.relinquishDeviceOwner(this@ShieldService)
                                    try {
                                        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                                            data = Uri.parse("package:$packageName")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        startActivity(uninstallIntent)
                                    } catch (_: Exception) {}
                                    stopSelf()
                                }
                                break
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Approval check error: ${e.message}")
                }

                try { Thread.sleep(POLL_APPROVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    // ── Thread 2: Premium / DNS Sync Monitor ────────────────────────────────

    /**
     * Polls devices/{deviceToken} every 60 s.
     * Reads isPremium and adultFilterEnabled and applies Private DNS accordingly.
     * Also caches the result in SharedPreferences for reboot resilience.
     */
    private fun startPremiumSyncMonitor() {
        if (isMonitoringPremium) return
        isMonitoringPremium = true

        thread(name = "ShieldPremiumSyncThread") {
            val deviceToken = getDeviceToken()
            Log.d(TAG, "Premium sync monitor started for token: $deviceToken")

            while (isMonitoringPremium) {
                try {
                    val url = "$RTDB_BASE_URL/devices/$deviceToken.json"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    if (conn.responseCode == 200) {
                        val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        if (!text.isNullOrBlank() && text != "null") {
                            val json              = JSONObject(text)
                            val isPremium         = json.optBoolean("isPremium", false)
                            val adultFilterEnabled = json.optBoolean("adultFilterEnabled", true)

                            // Cache locally so reboot restores same DNS state
                            getPrefs().edit()
                                .putBoolean(PREF_IS_PREMIUM, isPremium)
                                .putBoolean(PREF_ADULT_FILTER, adultFilterEnabled)
                                .apply()

                            // Apply DNS — only activate 18+ filter if user has active premium!
                            // If unpaid or subscription lapses, service is paused (DNS off) but Shield remains
                            // locked, hidden, and paired as Device Owner so no re-setup is needed when renewed.
                            val shouldFilter = isPremium && adultFilterEnabled
                            mainHandler.post { setAdultFilterDns(shouldFilter) }

                            Log.d(TAG, "Premium sync: isPremium=$isPremium, adultFilter=$adultFilterEnabled, active=$shouldFilter")
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Premium sync error: ${e.message}")
                }

                try { Thread.sleep(POLL_PREMIUM_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        isMonitoringApproval = false
        isMonitoringPremium  = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Community Shield স্থায়ী সুরক্ষা",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ডিভাইস ও ১৮+ কন্টেন্ট সুরক্ষা সার্ভিস সক্রিয় রয়েছে"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Community Shield সক্রিয়")
            .setContentText("ডিভাইস ও কমিউনিটি সুরক্ষা নিশ্চিত করা হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
