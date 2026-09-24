package com.muslimguide.shield.adb

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * Handles cryptographic operations for ADB authentication, including
 * RSA key generation, public key conversion to ADB format, and token signing.
 */
class AdbCrypto private constructor(
    val keyPair: KeyPair
) {
    /**
     * Signs the auth challenge token received from the device using RSA/ECB/PKCS1Padding.
     */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(token)
    }

    /**
     * Formats the RSA public key according to Android's custom ADB key format.
     */
    fun getAdbPublicKey(userAndHost: String = "muslimguide@localhost"): ByteArray {
        val pubKey = keyPair.public as RSAPublicKey
        val n = pubKey.modulus
        val r32 = BigInteger.valueOf(2).pow(32)
        val r = BigInteger.valueOf(2).pow(2048)
        val rr = r.multiply(r).mod(n)
        val rem = n.mod(r32)
        val n0inv = rem.modInverse(r32).negate()

        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(64) // RSANUMWORDS (2048 / 32)
        buffer.putInt(n0inv.toInt())

        // Modulus words
        val nBytes = n.toByteArray()
        val nWords = IntArray(64)
        for (i in 0 until 64) {
            var word = 0
            for (j in 0 until 4) {
                val byteIndex = nBytes.size - 1 - (i * 4 + j)
                if (byteIndex >= 0) {
                    word = word or ((nBytes[byteIndex].toInt() and 0xFF) shl (j * 8))
                }
            }
            nWords[i] = word
            buffer.putInt(word)
        }

        // R^2 words
        val rrBytes = rr.toByteArray()
        for (i in 0 until 64) {
            var word = 0
            for (j in 0 until 4) {
                val byteIndex = rrBytes.size - 1 - (i * 4 + j)
                if (byteIndex >= 0) {
                    word = word or ((rrBytes[byteIndex].toInt() and 0xFF) shl (j * 8))
                }
            }
            buffer.putInt(word)
        }

        buffer.putInt(pubKey.publicExponent.toInt())

        val base64Key = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        val result = "$base64Key $userAndHost\u0000"
        return result.toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val PREFS_NAME = "adb_crypto_prefs"
        private const val KEY_PRIVATE = "adb_private_key"

        /**
         * Loads existing RSA key or generates and stores a new 2048-bit RSA keypair.
         */
        @Synchronized
        fun loadOrGenerate(context: Context): AdbCrypto {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val privateKeyStr = prefs.getString(KEY_PRIVATE, null)

            if (privateKeyStr != null) {
                try {
                    val privateKeyBytes = Base64.decode(privateKeyStr, Base64.DEFAULT)
                    val kf = KeyFactory.getInstance("RSA")
                    val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as RSAPrivateCrtKey
                    val publicKeySpec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
                    val publicKey = kf.generatePublic(publicKeySpec)
                    return AdbCrypto(KeyPair(publicKey, privateKey))
                } catch (e: Exception) {
                    // Fallback to regenerate
                }
            }

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair = kpg.generateKeyPair()
            val encodedPrivate = Base64.encodeToString(keyPair.private.encoded, Base64.DEFAULT)
            prefs.edit().putString(KEY_PRIVATE, encodedPrivate).apply()

            return AdbCrypto(keyPair)
        }
    }
}
