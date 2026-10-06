package com.gala.motetv.storage

import android.content.Context
import android.util.Base64
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.pairing.TvCertificateManager
import com.gala.motetv.pairing.TvIdentity
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

class TvCredentialStore(private val context: Context?) {

    companion object {
        private const val PREFS_NAME = "motetv_credentials_store"
        private const val KEY_GLOBAL_PRIV_KEY = "global_client_priv_key"
        private const val KEY_GLOBAL_CERT = "global_client_cert"
        private const val PREFIX_DEV_PRIV_KEY = "dev_priv_key_"
        private const val PREFIX_DEV_CERT = "dev_cert_"
        private const val PREFIX_DEV_PAIRED = "dev_paired_"
        private const val PREFIX_DEV_SERVER_FINGERPRINT = "dev_server_fp_"
        private const val PREFIX_DEV_PAIRED_AT = "dev_paired_at_"
    }

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets or creates client identity for a specific TV device.
     */
    @Synchronized
    fun getOrCreateIdentity(deviceId: String = "default"): TvIdentity {
        val p = prefs
        if (p != null) {
            val privKeyB64 = p.getString(PREFIX_DEV_PRIV_KEY + deviceId, null)
                ?: p.getString(KEY_GLOBAL_PRIV_KEY, null)
            val certB64 = p.getString(PREFIX_DEV_CERT + deviceId, null)
                ?: p.getString(KEY_GLOBAL_CERT, null)

            if (!privKeyB64.isNullOrBlank() && !certB64.isNullOrBlank()) {
                try {
                    val privBytes = Base64.decode(privKeyB64, Base64.DEFAULT)
                    val certBytes = Base64.decode(certB64, Base64.DEFAULT)

                    val kf = KeyFactory.getInstance("RSA")
                    val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))

                    val certFactory = CertificateFactory.getInstance("X.509")
                    val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

                    val keyPair = KeyPair(certificate.publicKey, privateKey)
                    TvLogger.i(TvLogger.TAG_TLS, "Loaded client identity for device: $deviceId")
                    return TvIdentity(keyPair, certificate)
                } catch (e: Exception) {
                    TvLogger.w(TvLogger.TAG_TLS, "Could not load stored identity for $deviceId: ${e.message}")
                }
            }
        }

        val identity = TvCertificateManager.generateIdentity("MoteTV")
        saveIdentity(deviceId, identity)
        return identity
    }

    @Synchronized
    fun saveIdentity(deviceId: String, identity: TvIdentity) {
        val p = prefs ?: return
        try {
            val privB64 = Base64.encodeToString(identity.keyPair.private.encoded, Base64.NO_WRAP)
            val certB64 = Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP)
            p.edit()
                .putString(PREFIX_DEV_PRIV_KEY + deviceId, privB64)
                .putString(PREFIX_DEV_CERT + deviceId, certB64)
                .putString(KEY_GLOBAL_PRIV_KEY, privB64)
                .putString(KEY_GLOBAL_CERT, certB64)
                .apply()
            TvLogger.i(TvLogger.TAG_TLS, "Saved client identity for device: $deviceId")
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_TLS, "Failed to persist client identity", e)
        }
    }

    fun isDevicePaired(deviceId: String): Boolean {
        val p = prefs ?: return false
        return p.getBoolean(PREFIX_DEV_PAIRED + deviceId, false)
    }

    fun setDevicePaired(deviceId: String, serverCert: X509Certificate? = null, paired: Boolean = true) {
        val p = prefs ?: return
        val editor = p.edit().putBoolean(PREFIX_DEV_PAIRED + deviceId, paired)
        if (paired) {
            editor.putLong(PREFIX_DEV_PAIRED_AT + deviceId, System.currentTimeMillis())
            if (serverCert != null) {
                val fp = computeFingerprint(serverCert)
                editor.putString(PREFIX_DEV_SERVER_FINGERPRINT + deviceId, fp)
            }
        }
        editor.apply()
        TvLogger.i(TvLogger.TAG_PAIR, "Device $deviceId paired status: $paired")
    }

    fun getServerFingerprint(deviceId: String): String? {
        return prefs?.getString(PREFIX_DEV_SERVER_FINGERPRINT + deviceId, null)
    }

    fun computeFingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
