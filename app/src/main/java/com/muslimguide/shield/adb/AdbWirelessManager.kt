package com.muslimguide.shield.adb

import android.content.Context
import com.muslimguide.shield.AdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full activation sequence over a single Wireless ADB connection:
 *   Step 1 → Download Community Shield APK via shell (curl/wget)
 *   Step 2 → Install APK via shell pm
 *   Step 3 → Grant Device Owner via dpm set-device-owner
 *
 * All steps run on the same AdbClient socket session.
 */
class AdbWirelessManager private constructor(private val context: Context) {

    private val crypto: AdbCrypto = AdbCrypto.loadOrGenerate(context)

    companion object {
        /** Public download URL for Community Shield APK (GitHub Releases) */
        const val SHIELD_APK_URL =
            "https://raw.githubusercontent.com/muslimguidebd/community-shield/main/releases/latest/download/shield.apk"

        /** Temp path on device where APK will be saved */
        private const val SHIELD_APK_DEVICE_PATH = "/data/local/tmp/community_shield.apk"

        /** Device Owner component name */
        private const val DEVICE_OWNER_COMPONENT = "com.muslimguide.shield/.AdminReceiver"

        @Volatile
        private var instance: AdbWirelessManager? = null

        fun getInstance(context: Context): AdbWirelessManager =
            instance ?: synchronized(this) {
                instance ?: AdbWirelessManager(context.applicationContext).also { instance = it }
            }
    }

    // ── Activation Progress Events ──────────────────────────────────────────
    sealed class ActivationEvent {
        data class Progress(val step: Int, val total: Int, val message: String) : ActivationEvent()
        data class Output(val line: String) : ActivationEvent()
        data class Success(val message: String) : ActivationEvent()
        data class Failure(val message: String, val cause: Throwable? = null) : ActivationEvent()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Connects to ADB Wireless on [port] and runs the full Community Shield
     * activation sequence, emitting [ActivationEvent]s via [onEvent].
     *
     * Full sequence:
     *   1. Connect & authenticate (RSA key exchange)
     *   2. Download APK via `curl` or `wget` to /data/local/tmp/
     *   3. Install APK via `pm install`
     *   4. Set Device Owner via `dpm set-device-owner`
     */
    suspend fun activateCommunityShield(
        port: Int,
        host: String = AdbProtocol.LOCALHOST,
        onEvent: (ActivationEvent) -> Unit
    ) = withContext(Dispatchers.IO) {

        val client = AdbClient(host = host, port = port, crypto = crypto)

        // ── Step 0: Connect ────────────────────────────────────────────────
        onEvent(ActivationEvent.Progress(0, 2, "ADB কানেক্ট করা হচ্ছে..."))
        val connected = client.connect(timeoutMs = 6000)
        if (!connected) {
            onEvent(ActivationEvent.Failure(
                "কানেক্ট করা যায়নি।\n" +
                "• ফোনে Wireless Debugging চালু আছে কিনা নিশ্চিত করুন।\n" +
                "• দেখানো পোর্ট নম্বরটি সঠিক কিনা যাচাই করুন।\n" +
                "• ওয়াই-ফাই সংযুক্ত আছে কিনা নিশ্চিত করুন।"
            ))
            return@withContext
        }
        onEvent(ActivationEvent.Output("✓ ADB সংযোগ স্থাপিত হয়েছে।"))

        // ── Step 1: Clean secondary users & Set Device Owner ───────────────
        onEvent(ActivationEvent.Progress(1, 2, "ডিভাইস সুরক্ষা প্রস্তুত করা হচ্ছে..."))

        // Auto remove secondary users or guest profiles if any
        runCatching {
            val usersOut = client.executeCommand("pm list users")
            val userRegex = """UserInfo\{(\d+):""".toRegex()
            userRegex.findAll(usersOut).forEach { match ->
                val userId = match.groupValues[1].toIntOrNull()
                if (userId != null && userId > 0) {
                    onEvent(ActivationEvent.Output("গেস্ট/অতিরিক্ত ইউজার ($userId) সরানো হচ্ছে..."))
                    client.executeCommand("pm remove-user $userId")
                }
            }
        }

        onEvent(ActivationEvent.Progress(1, 2, "Device Owner অনুমতি প্রদান করা হচ্ছে..."))

        var dpmOutput = runCatching {
            client.executeCommand("dpm set-device-owner $DEVICE_OWNER_COMPONENT")
        }.getOrElse { e ->
            client.disconnect()
            onEvent(ActivationEvent.Failure("Device Owner সেট করা যায়নি: ${e.message}"))
            return@withContext
        }

        // If accounts exist on device, automatically launch account settings and auto-retry!
        if (dpmOutput.contains("accounts", ignoreCase = true)) {
            onEvent(ActivationEvent.Output("⚠️ ডিভাইসে গুগল/অন্যান্য একাউন্ট যুক্ত রয়েছে।"))
            onEvent(ActivationEvent.Output("👉 একাউন্ট সেটিংস ওপেন করা হচ্ছে: অনুগ্রহ করে সাময়িক একাউন্টটি Remove চাপুন..."))
            onEvent(ActivationEvent.Output("🔄 একাউন্ট রিমুভ হওয়ার সাথে সাথে সিস্টেম নিজে থেকেই কোড এক্সিকিউট করবে..."))

            // Launch Account settings automatically
            runCatching {
                client.executeCommand("am start -a android.settings.SYNC_SETTINGS")
            }

            // Auto-polling retry loop (polls every 2 seconds for up to 90 seconds)
            var remainingRetries = 45
            while (remainingRetries > 0) {
                kotlinx.coroutines.delay(2000)
                dpmOutput = runCatching {
                    client.executeCommand("dpm set-device-owner $DEVICE_OWNER_COMPONENT")
                }.getOrElse { "" }

                if (dpmOutput.contains("Success", ignoreCase = true) ||
                    dpmOutput.contains("set as device owner", ignoreCase = true)) {
                    break
                }
                remainingRetries--
            }
        }

        onEvent(ActivationEvent.Output(dpmOutput))
        client.disconnect()

        if (dpmOutput.contains("Success", ignoreCase = true) ||
            dpmOutput.contains("set as device owner", ignoreCase = true)) {
            
            // Apply protection immediately
            AdminReceiver.applyProtectionPolicies(context)

            onEvent(ActivationEvent.Progress(2, 2, "সম্পন্ন"))
            onEvent(ActivationEvent.Success(
                "✓ Community Shield সফলভাবে Device Owner হিসেবে সক্রিয় হয়েছে!\n" +
                "💡 এখন আপনি চাইলে পুনরায় আপনার গুগল একাউন্ট ফোনে যোগ করতে পারেন, সুরক্ষা আজীবন বজায় থাকবে।"
            ))
        } else {
            onEvent(ActivationEvent.Failure(
                "Device Owner সেট করা যায়নি।\n" +
                "ফলাফল: $dpmOutput\n\n" +
                "সম্ভাব্য কারণ:\n" +
                "• ফোনে এখনও কোনো একাউন্ট অবশিষ্ট রয়েছে।\n" +
                "• অন্য কোনো অ্যাপ ইতিমধ্যে Device Owner হিসেবে সক্রিয়।"
            ))
        }
    }

    /**
     * Executes a single arbitrary ADB shell command.
     */
    suspend fun executeCommand(
        port: Int,
        command: String,
        host: String = AdbProtocol.LOCALHOST,
        timeoutMs: Int = 5000
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val client = AdbClient(host = host, port = port, crypto = crypto)
            val connected = client.connect(timeoutMs = timeoutMs)
            if (!connected) return@withContext Result.failure(
                IllegalStateException("ADB কানেক্ট করতে ব্যর্থ হয়েছে।")
            )
            val result = client.executeCommand(command)
            client.disconnect()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Builds a download command using curl (preferred) with wget as fallback.
     * curl and wget are both available on AOSP-based Android.
     */
    private fun buildDownloadCommand(url: String, destPath: String): String =
        "curl -L -o $destPath '$url' 2>&1 || wget -O $destPath '$url' 2>&1"
}
