package com.muslimguide.shield.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ShieldNativeCore {

    init {
        try {
            System.loadLibrary("shield_core")
        } catch (_: UnsatisfiedLinkError) {
            // Fallback for environments where NDK is compiling
        }
    }

    // Native functions
    external fun getNativeSecretKey(): ByteArray
    external fun verifyCallerSignature(callerCertBytes: ByteArray): Boolean
    external fun getCleanBrowsingDns(): String

    // Fallback key if native library loading fails in special test environments
    private val FALLBACK_KEY = byteArrayOf(
        0x57, 0x5E, 0x4F, 0x21, 0x37, 0x0A, 0x37, 0xF4.toByte(),
        0xFB.toByte(), 0xC7.toByte(), 0xD1.toByte(), 0x96.toByte(),
        0xBE.toByte(), 0x86.toByte(), 0x97.toByte(), 0x65,
        0x76, 0x67, 0x33, 0x1B, 0xE8.toByte(), 0xDF.toByte(), 0xAC.toByte(), 0xBB.toByte(),
        0x74, 0x5B, 0x01, 0x74, 0x67, 0x54, 0x55, 0xAC.toByte()
    )

    fun getSecretKey(): ByteArray {
        return try {
            getNativeSecretKey()
        } catch (_: Throwable) {
            FALLBACK_KEY
        }
    }

    fun getAdultDns(): String {
        return try {
            getCleanBrowsingDns()
        } catch (_: Throwable) {
            "adult-filter-dns.cleanbrowsing.org"
        }
    }

    /**
     * Decrypts an incoming AES-GCM payload from MuslimGuideBD.
     * Payload format: Base64( IV[12 bytes] + CipherText + Tag[16 bytes] )
     */
    fun decryptPayload(encryptedBase64: String): String? {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size < 28) return null

            val iv = ByteArray(12)
            System.arraycopy(combined, 0, iv, 0, 12)

            val cipherTextSize = combined.size - 12
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(combined, 12, cipherText, 0, cipherTextSize)

            val secretKey = SecretKeySpec(getSecretKey(), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encrypts outgoing payload to MuslimGuideBD.
     */
    fun encryptPayload(plainText: String): String {
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)

        val secretKey = SecretKeySpec(getSecretKey(), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Verifies that the caller application belongs to the authorized Muslim Guide project.
     */
    fun isAuthorizedCaller(context: Context, callerPackage: String): Boolean {
        if (callerPackage != "com.muslimguide.bd") return false
        return try {
            val pm = context.packageManager
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(callerPackage, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(callerPackage, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            } ?: return false

            val md = MessageDigest.getInstance("SHA-1")
            val sha1Bytes = md.digest(sigBytes)

            try {
                verifyCallerSignature(sha1Bytes)
            } catch (_: Throwable) {
                // Fallback direct check
                sha1Bytes.isNotEmpty() && (sha1Bytes[0] == 0xAD.toByte() && sha1Bytes[1] == 0xD0.toByte())
            }
        } catch (_: Exception) {
            false
        }
    }
}
