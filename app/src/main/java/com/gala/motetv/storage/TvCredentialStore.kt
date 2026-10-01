package com.gala.motetv.storage

import android.content.Context
import android.util.Base64
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.pairing.TvCertificateManager
import com.gala.motetv.pairing.TvIdentity
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class TvCredentialStore(private val context: Context?) {

    companion object {
        private const val PREFS_NAME = "motetv_credentials_store"
        private const val KEY_PRIVATE_KEY_BASE64 = "client_priv_key_pkcs8"
        private const val KEY_CERTIFICATE_BASE64 = "client_cert_x509_der"
        private const val PREFIX_PAIRED_DEVICE = "paired_device_"
    }

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets or creates the persistent client identity.
     */
    @Synchronized
    fun getOrCreateIdentity(commonName: String = "MoteTV"): TvIdentity {
        val p = prefs
        if (p != null) {
            val privKeyB64 = p.getString(KEY_PRIVATE_KEY_BASE64, null)
            val certB64 = p.getString(KEY_CERTIFICATE_BASE64, null)

            if (!privKeyB64.isNullOrBlank() && !certB64.isNullOrBlank()) {
                try {
                    val privBytes = Base64.decode(privKeyB64, Base64.DEFAULT)
                    val certBytes = Base64.decode(certB64, Base64.DEFAULT)

                    val kf = KeyFactory.getInstance("RSA")
                    val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))

                    val certFactory = CertificateFactory.getInstance("X.509")
                    val certificate = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

                    val keyPair = KeyPair(certificate.publicKey, privateKey)
                    TvLogger.i(TvLogger.TAG_TLS, "Loaded existing client identity from persistent storage")
                    return TvIdentity(keyPair, certificate)
                } catch (e: Exception) {
                    TvLogger.w(TvLogger.TAG_TLS, "Could not load stored identity, generating new: ${e.message}")
                }
            }
        }

        val identity = TvCertificateManager.generateIdentity(commonName)
        saveIdentity(identity)
        return identity
    }

    @Synchronized
    fun saveIdentity(identity: TvIdentity) {
        val p = prefs ?: return
        try {
            val privB64 = Base64.encodeToString(identity.keyPair.private.encoded, Base64.NO_WRAP)
            val certB64 = Base64.encodeToString(identity.certificate.encoded, Base64.NO_WRAP)
            p.edit()
                .putString(KEY_PRIVATE_KEY_BASE64, privB64)
                .putString(KEY_CERTIFICATE_BASE64, certB64)
                .apply()
            TvLogger.i(TvLogger.TAG_TLS, "Saved client identity to persistent storage")
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_TLS, "Failed to persist client identity", e)
        }
    }

    fun isDevicePaired(deviceId: String): Boolean {
        val p = prefs ?: return false
        return p.getBoolean(PREFIX_PAIRED_DEVICE + deviceId, false)
    }

    fun setDevicePaired(deviceId: String, paired: Boolean) {
        val p = prefs ?: return
        p.edit().putBoolean(PREFIX_PAIRED_DEVICE + deviceId, paired).apply()
        TvLogger.i(TvLogger.TAG_TLS, "Updated device pairing status: $deviceId -> paired=$paired")
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
