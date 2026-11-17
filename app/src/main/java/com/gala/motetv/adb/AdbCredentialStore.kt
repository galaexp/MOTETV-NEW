package com.gala.motetv.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gala.motetv.core.logging.TvLogger
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores ADB RSA authentication keypairs and saved ADB host endpoints encrypted with
 * an Android Keystore AES-GCM key. Falls back cleanly to in-memory keys in JVM unit tests
 * when [context] is null.
 */
class AdbCredentialStore(private val context: Context?) {

    companion object {
        private const val PREFS_NAME = "motetv_adb_secure_store"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "motetv_adb_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val KEY_PRIV_ENC = "adb_priv_key_enc"
        private const val KEY_PUB_ENC = "adb_pub_key_enc"
        private const val KEY_ENDPOINT_HOST = "adb_endpoint_host"
        private const val KEY_ENDPOINT_PORT = "adb_endpoint_port"
        private const val KEY_ENDPOINT_MODE = "adb_endpoint_mode"
        private const val KEY_ENDPOINT_TS = "adb_endpoint_ts"

        private const val ANDROID_RSA_KEY_NUM_WORDS = 64 // 2048-bit = 64 x 32-bit words
        private val DIGEST_INFO_SHA1 = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var cachedKeyPair: KeyPair? = null
    private var inMemoryEndpoint: AdbEndpointConfig? = null

    @Synchronized
    fun getOrCreateAdbKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }

        val p = prefs
        if (p != null) {
            val privEnc = p.getString(KEY_PRIV_ENC, null)
            val pubEnc = p.getString(KEY_PUB_ENC, null)
            if (!privEnc.isNullOrBlank() && !pubEnc.isNullOrBlank()) {
                try {
                    val privBytes = decryptBytes(Base64.decode(privEnc, Base64.NO_WRAP))
                    val pubBytes = decryptBytes(Base64.decode(pubEnc, Base64.NO_WRAP))
                    val kf = KeyFactory.getInstance("RSA")
                    val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                    val publicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                    return KeyPair(publicKey, privateKey).also { cachedKeyPair = it }
                } catch (e: Exception) {
                    TvLogger.w(TvLogger.TAG_PAIR, "Could not decrypt stored ADB RSA keypair, regenerating: ${e.message}")
                }
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        cachedKeyPair = kp

        if (p != null) {
            try {
                val privEnc = Base64.encodeToString(encryptBytes(kp.private.encoded), Base64.NO_WRAP)
                val pubEnc = Base64.encodeToString(encryptBytes(kp.public.encoded), Base64.NO_WRAP)
                p.edit()
                    .putString(KEY_PRIV_ENC, privEnc)
                    .putString(KEY_PUB_ENC, pubEnc)
                    .apply()
            } catch (e: Exception) {
                TvLogger.w(TvLogger.TAG_PAIR, "Failed to persist encrypted ADB keypair: ${e.message}")
            }
        }

        return kp
    }

    fun getKeyFingerprint(): String {
        val pub = getOrCreateAdbKeyPair().public
        val digest = MessageDigest.getInstance("SHA-256").digest(pub.encoded)
        return digest.take(16).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Signs the 20-byte ADB AUTH token using raw RSA private encryption of the
     * SHA-1 PKCS#1 v1.5 DigestInfo(token) matching Android adbd verification.
     */
    fun signAuthToken(token: ByteArray): ByteArray {
        val kp = getOrCreateAdbKeyPair()
        return signAuthTokenWithKeyPair(token, kp)
    }

    fun signAuthTokenWithKeyPair(token: ByteArray, keyPair: KeyPair): ByteArray {
        // adbd sends a 20-byte SHA-1-sized random token and calls RSA_verify(NID_sha1, token, 20, sig, ...)
        val digestInfo = DIGEST_INFO_SHA1 + token
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(digestInfo)
    }

    /**
     * Encodes the 2048-bit RSA public key in Android ADB's RSAPublicKey struct format,
     * terminated by " motetv@android\u0000".
     */
    fun encodeAdbPublicKeyPayload(userHost: String = "motetv@android"): ByteArray {
        val pub = getOrCreateAdbKeyPair().public as RSAPublicKey
        val structBytes = encodeAndroidRsaPublicKey(pub)
        val b64 = java.util.Base64.getEncoder().encodeToString(structBytes)
        return "$b64 $userHost\u0000".toByteArray(Charsets.US_ASCII)
    }

    fun encodeAndroidRsaPublicKey(publicKey: RSAPublicKey): ByteArray {
        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + (ANDROID_RSA_KEY_NUM_WORDS * 4) * 2 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(ANDROID_RSA_KEY_NUM_WORDS)
        buf.putInt(n0inv.toInt())

        val nWords = bigIntToLittleEndianWords(n, ANDROID_RSA_KEY_NUM_WORDS)
        for (w in nWords) buf.putInt(w)

        val rrWords = bigIntToLittleEndianWords(rr, ANDROID_RSA_KEY_NUM_WORDS)
        for (w in rrWords) buf.putInt(w)

        buf.putInt(publicKey.publicExponent.toInt())
        return buf.array()
    }

    private fun bigIntToLittleEndianWords(value: BigInteger, words: Int): IntArray {
        var cur = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        return IntArray(words) {
            val word = cur.and(mask).toInt()
            cur = cur.shiftRight(32)
            word
        }
    }

    fun saveEndpoint(config: AdbEndpointConfig) {
        inMemoryEndpoint = config
        prefs?.edit()
            ?.putString(KEY_ENDPOINT_HOST, config.host)
            ?.putInt(KEY_ENDPOINT_PORT, config.port)
            ?.putString(KEY_ENDPOINT_MODE, config.mode.name)
            ?.putLong(KEY_ENDPOINT_TS, config.lastConnectedMs)
            ?.apply()
    }

    fun getSavedEndpoint(): AdbEndpointConfig? {
        val p = prefs ?: return inMemoryEndpoint
        val host = p.getString(KEY_ENDPOINT_HOST, null) ?: return inMemoryEndpoint
        if (host.isBlank()) return null
        val port = p.getInt(KEY_ENDPOINT_PORT, 5555)
        val modeName = p.getString(KEY_ENDPOINT_MODE, AdbConnectionMode.NETWORK_LEGACY_5555.name)
        val mode = runCatching { AdbConnectionMode.valueOf(modeName ?: "") }
            .getOrDefault(AdbConnectionMode.NETWORK_LEGACY_5555)
        val ts = p.getLong(KEY_ENDPOINT_TS, 0L)
        return AdbEndpointConfig(host = host, port = port, mode = mode, lastConnectedMs = ts)
    }

    fun clearSavedCredentials() {
        cachedKeyPair = null
        inMemoryEndpoint = null
        prefs?.edit()?.clear()?.apply()
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(iv.size)
            write(iv)
            write(ciphertext)
        }.toByteArray()
    }

    private fun decryptBytes(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ciphertext = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @Suppress("unused")
    internal fun verifySignatureLocally(token: ByteArray, sig: ByteArray): Boolean {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initVerify(getOrCreateAdbKeyPair().public)
        signature.update(DIGEST_INFO_SHA1 + token)
        return signature.verify(sig)
    }
}
