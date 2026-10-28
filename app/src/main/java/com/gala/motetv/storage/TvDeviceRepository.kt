package com.gala.motetv.storage

import android.content.Context
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.TvDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.regex.Pattern

class TvDeviceRepository(
    private val credentialStore: TvCredentialStore
) {
    private val _profiles = MutableStateFlow<List<TvDeviceProfile>>(emptyList())
    val profiles: StateFlow<List<TvDeviceProfile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    companion object {
        private val IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
        )
    }

    init {
        loadProfilesFromStore()
    }

    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        return IPV4_PATTERN.matcher(ip.trim()).matches()
    }

    @Synchronized
    fun loadProfilesFromStore() {
        val savedDevices = credentialStore.getSavedDevices()
        val defaultId = credentialStore.getActiveProfile()

        val loadedProfiles = if (savedDevices.isEmpty()) {
            emptyList()
        } else {
            savedDevices.map { dev ->
                TvDeviceProfile(
                    id = dev.id,
                    name = dev.name,
                    host = dev.host,
                    pairingPort = dev.pairingPort,
                    remotePort = dev.remotePort,
                    serviceName = dev.serviceName,
                    roomLabel = "Main Room",
                    isDefault = (dev.id == defaultId),
                    isPaired = dev.isPaired,
                    lastConnectedTimestamp = dev.lastSeen,
                    manufacturer = dev.manufacturer,
                    model = dev.model
                )
            }
        }

        _profiles.value = loadedProfiles
        _selectedProfileId.value = loadedProfiles.firstOrNull { it.isDefault }?.id ?: loadedProfiles.firstOrNull()?.id
    }

    fun saveProfile(
        name: String,
        host: String,
        roomLabel: String = "Main Room",
        id: String? = null
    ): Result<TvDeviceProfile> {
        val cleanIp = host.trim()
        if (!isValidIpAddress(cleanIp)) {
            return Result.failure(IllegalArgumentException("Invalid IPv4 address format: '$host'"))
        }

        val currentList = _profiles.value.toMutableList()
        val targetId = id ?: UUID.randomUUID().toString()

        // Check duplicate IP for distinct profiles
        val existingWithIp = currentList.firstOrNull { it.host == cleanIp && it.id != targetId }
        if (existingWithIp != null) {
            return Result.failure(IllegalArgumentException("A TV profile for IP '$cleanIp' already exists (${existingWithIp.name})"))
        }

        val cleanName = name.trim().ifEmpty { "Android TV" }
        val isFirst = currentList.isEmpty()

        val updatedProfile = TvDeviceProfile(
            id = targetId,
            name = cleanName,
            host = cleanIp,
            roomLabel = roomLabel.trim().ifEmpty { "Main Room" },
            isDefault = isFirst || currentList.firstOrNull { it.id == targetId }?.isDefault == true,
            isPaired = credentialStore.isDevicePaired(targetId),
            lastConnectedTimestamp = System.currentTimeMillis()
        )

        val idx = currentList.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            currentList[idx] = updatedProfile
        } else {
            currentList.add(updatedProfile)
        }

        _profiles.value = currentList
        if (_selectedProfileId.value == null || isFirst) {
            _selectedProfileId.value = targetId
        }

        syncToCredentialStore(currentList)
        TvLogger.i(TvLogger.TAG_GENERAL, "Saved TV Device Profile: $cleanName ($cleanIp)")
        return Result.success(updatedProfile)
    }

    fun deleteProfile(profileId: String) {
        val current = _profiles.value.filterNot { it.id == profileId }
        _profiles.value = current

        if (_selectedProfileId.value == profileId) {
            _selectedProfileId.value = current.firstOrNull()?.id
        }

        credentialStore.removeDevice(profileId)
        syncToCredentialStore(current)
        TvLogger.i(TvLogger.TAG_GENERAL, "Deleted TV Device Profile: $profileId")
    }

    fun renameProfile(profileId: String, newName: String) {
        val current = _profiles.value.map {
            if (it.id == profileId) it.copy(name = newName.trim()) else it
        }
        _profiles.value = current
        credentialStore.renameDevice(profileId, newName.trim())
        syncToCredentialStore(current)
    }

    fun setDefaultProfile(profileId: String) {
        val current = _profiles.value.map {
            it.copy(isDefault = (it.id == profileId))
        }
        _profiles.value = current
        _selectedProfileId.value = profileId
        credentialStore.setActiveProfile(profileId)
        syncToCredentialStore(current)
    }

    fun selectProfile(profileId: String) {
        if (_profiles.value.any { it.id == profileId }) {
            _selectedProfileId.value = profileId
            credentialStore.setActiveProfile(profileId)
        }
    }

    fun updateLastConnected(profileId: String) {
        val now = System.currentTimeMillis()
        val current = _profiles.value.map {
            if (it.id == profileId) it.copy(lastConnectedTimestamp = now, isPaired = true) else it
        }
        _profiles.value = current
        syncToCredentialStore(current)
    }

    fun getSelectedProfile(): TvDeviceProfile? {
        val id = _selectedProfileId.value ?: return _profiles.value.firstOrNull()
        return _profiles.value.firstOrNull { it.id == id } ?: _profiles.value.firstOrNull()
    }

    fun toTvDevice(profile: TvDeviceProfile): TvDevice {
        return TvDevice(
            id = profile.id,
            name = profile.name,
            host = profile.host,
            pairingPort = profile.pairingPort,
            remotePort = profile.remotePort,
            manufacturer = profile.manufacturer,
            model = profile.model,
            serviceName = profile.serviceName,
            isPaired = profile.isPaired,
            lastSeen = profile.lastConnectedTimestamp
        )
    }

    private fun syncToCredentialStore(list: List<TvDeviceProfile>) {
        list.forEach { p ->
            credentialStore.saveDevice(toTvDevice(p))
        }
    }
}
