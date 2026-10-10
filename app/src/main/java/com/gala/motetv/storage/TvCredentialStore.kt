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

    // --- Saved TV Devices ---
    fun getSavedDevices(): List<com.gala.motetv.core.model.TvDevice> {
        val p = prefs ?: return emptyList()
        val raw = p.getString("saved_devices_records", null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split("\t")
            if (parts.size >= 5) {
                com.gala.motetv.core.model.TvDevice(
                    id = parts[0],
                    name = parts[1],
                    host = parts[2],
                    pairingPort = parts[3].toIntOrNull() ?: 6467,
                    remotePort = parts[4].toIntOrNull() ?: 6466,
                    manufacturer = parts.getOrNull(5) ?: "Generic",
                    model = parts.getOrNull(6) ?: "Android TV",
                    serviceName = parts.getOrNull(7) ?: "androidtv-remote",
                    isPaired = parts.getOrNull(8)?.toBooleanStrictOrNull() ?: isDevicePaired(parts[0]),
                    lastSeen = parts.getOrNull(9)?.toLongOrNull() ?: System.currentTimeMillis()
                )
            } else null
        }
    }

    fun saveDevice(device: com.gala.motetv.core.model.TvDevice) {
        val current = getSavedDevices().toMutableList()
        val index = current.indexOfFirst { it.id == device.id }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        persistSavedDevices(current)
    }

    fun removeDevice(deviceId: String) {
        val current = getSavedDevices().filterNot { it.id == deviceId }
        persistSavedDevices(current)
    }

    fun renameDevice(deviceId: String, newName: String) {
        val current = getSavedDevices().map {
            if (it.id == deviceId) it.copy(name = newName) else it
        }
        persistSavedDevices(current)
    }

    private fun persistSavedDevices(list: List<com.gala.motetv.core.model.TvDevice>) {
        val raw = list.joinToString("\n") { dev ->
            "${dev.id}\t${dev.name}\t${dev.host}\t${dev.pairingPort}\t${dev.remotePort}\t${dev.manufacturer}\t${dev.model}\t${dev.serviceName}\t${dev.isPaired}\t${dev.lastSeen}"
        }
        prefs?.edit()?.putString("saved_devices_records", raw)?.apply()
    }

    // --- Favorite Apps & Custom Apps ---
    fun getFavoriteAppIds(): Set<String> {
        return prefs?.getStringSet("favorite_apps_ids", setOf("youtube", "netflix", "prime_video", "disney_plus", "spotify"))
            ?: setOf("youtube", "netflix", "prime_video", "disney_plus", "spotify")
    }

    fun setFavoriteAppIds(ids: Set<String>) {
        prefs?.edit()?.putStringSet("favorite_apps_ids", ids)?.apply()
    }

    fun toggleFavoriteApp(appId: String): Boolean {
        val current = getFavoriteAppIds().toMutableSet()
        val isNowFav = if (current.contains(appId)) {
            current.remove(appId)
            false
        } else {
            current.add(appId)
            true
        }
        setFavoriteAppIds(current)
        return isNowFav
    }

    fun getCustomApps(): List<com.gala.motetv.launcher.AppDefinition> {
        val raw = prefs?.getString("custom_apps_records", null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split("\t")
            if (parts.size >= 4) {
                com.gala.motetv.launcher.AppDefinition(
                    id = parts[0],
                    name = parts[1],
                    packageId = parts[2],
                    primaryDeepLink = parts[3],
                    alternativeDeepLinks = parts.getOrNull(4)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    accentColorHex = parts.getOrNull(5)?.toLongOrNull() ?: 0xFF5B6CFF,
                    isFavorite = getFavoriteAppIds().contains(parts[0]),
                    category = com.gala.motetv.launcher.AppCategory.CUSTOM,
                    isCustom = true
                )
            } else null
        }
    }

    fun saveCustomApp(app: com.gala.motetv.launcher.AppDefinition) {
        val current = getCustomApps().toMutableList()
        val index = current.indexOfFirst { it.id == app.id }
        if (index >= 0) {
            current[index] = app
        } else {
            current.add(app)
        }
        val raw = current.joinToString("\n") { item ->
            "${item.id}\t${item.name}\t${item.packageId}\t${item.primaryDeepLink}\t${item.alternativeDeepLinks.joinToString(",")}\t${item.accentColorHex}\t${item.category.name}"
        }
        prefs?.edit()?.putString("custom_apps_records", raw)?.apply()
    }

    fun removeCustomApp(appId: String) {
        val current = getCustomApps().filterNot { it.id == appId }
        val raw = current.joinToString("\n") { item ->
            "${item.id}\t${item.name}\t${item.packageId}\t${item.primaryDeepLink}\t${item.alternativeDeepLinks.joinToString(",")}\t${item.accentColorHex}\t${item.category.name}"
        }
        prefs?.edit()?.putString("custom_apps_records", raw)?.apply()
    }

    // --- Preferences ---
    fun isHapticEnabled(): Boolean {
        return prefs?.getBoolean("haptic_feedback_enabled", true) ?: true
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean("haptic_feedback_enabled", enabled)?.apply()
    }

    fun getActiveProfile(): String {
        return prefs?.getString("active_remote_profile", "Default") ?: "Default"
    }

    fun setActiveProfile(profile: String) {
        prefs?.edit()?.putString("active_remote_profile", profile)?.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
