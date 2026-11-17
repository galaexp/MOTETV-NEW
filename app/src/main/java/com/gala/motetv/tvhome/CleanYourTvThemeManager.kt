package com.gala.motetv.tvhome

import com.gala.motetv.adb.AdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Customizes and verifies CleanYourTV Home (`com.gathin.tvhome`) `config.json` and wallpapers
 * in `/sdcard/Android/data/com.gathin.tvhome/files/`, then safely restarts CleanYourTV Home.
 */
class CleanYourTvThemeManager(
    private val adbManager: AdbConnectionManager
) {

    private val _currentConfig = MutableStateFlow(CleanYourTvThemeConfig())
    val currentConfig: StateFlow<CleanYourTvThemeConfig> = _currentConfig.asStateFlow()

    private val _previousAppliedConfig = MutableStateFlow<CleanYourTvThemeConfig?>(null)
    val previousAppliedConfig: StateFlow<CleanYourTvThemeConfig?> = _previousAppliedConfig.asStateFlow()

    private val _themeStatusMessage = MutableStateFlow<String?>(null)
    val themeStatusMessage: StateFlow<String?> = _themeStatusMessage.asStateFlow()

    private var lastAppliedConfigSnapshot: CleanYourTvThemeConfig? = null

    fun selectPreset(preset: CleanYourTvThemePreset) {
        _currentConfig.value = CleanYourTvThemeCatalog.applyPresetToConfig(_currentConfig.value, preset)
        _themeStatusMessage.value = "Selected '${preset.name}' for preview. Tap 'Apply Theme to TV' to deploy and verify."
    }

    fun updateConfig(transform: (CleanYourTvThemeConfig) -> CleanYourTvThemeConfig) {
        _currentConfig.value = transform(_currentConfig.value)
    }

    suspend fun applyThemeToTv(
        wallpaperBytes: ByteArray?,
        wallpaperName: String? = null
    ): Result<String> {
        val assets = if (wallpaperBytes != null && wallpaperBytes.isNotEmpty()) {
            listOf(WallpaperUploadAsset(wallpaperName ?: "wallpaper.jpg", wallpaperBytes))
        } else {
            emptyList()
        }
        return applyThemeToTv(assets)
    }

    suspend fun applyThemeToTv(
        wallpaperAssets: List<WallpaperUploadAsset> = emptyList()
    ): Result<String> {
        // 1. Verify CleanYourTV launcher is actually installed & enabled on TV
        val allPackages = adbManager.listAllPackages().getOrElse { err ->
            val msg = "Unable to verify CleanYourTV installation on TV: ${err.message}"
            _themeStatusMessage.value = msg
            return Result.failure(err)
        }
        val disabled = adbManager.listDisabledPackages().getOrElse { emptySet() }
        if (!allPackages.contains(CleanYourTvConstants.LAUNCHER_PACKAGE) ||
            disabled.contains(CleanYourTvConstants.LAUNCHER_PACKAGE)
        ) {
            val msg = "CleanYourTV Home (${CleanYourTvConstants.LAUNCHER_PACKAGE}) is not installed/enabled on this TV. Install it first before applying themes."
            _themeStatusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        // 2. Ensure base app external files dir exists
        adbManager.ensureRemoteDirectory(CleanYourTvConstants.REMOTE_FILES_DIR).getOrElse { err ->
            val msg = "TV storage directory inaccessible: ${err.message}"
            _themeStatusMessage.value = msg
            return Result.failure(err)
        }

        var activeCfg = CleanYourTvThemeCatalog.sanitizedConfig(_currentConfig.value)
        val nonEmptyAssets = wallpaperAssets.filter { it.bytes.isNotEmpty() }
        val verifiedRemoteWallpapers = mutableListOf<String>()

        // 3. Deploy wallpaper assets according to CleanYourTV rules:
        //    - Static wallpaper -> /sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg
        //    - Rotation (or >1 asset) -> /sdcard/Android/data/com.gathin.tvhome/files/wallpapers/<name> + static fallback if 1st
        if (nonEmptyAssets.isNotEmpty()) {
            val useRotationDir = activeCfg.wallpaperRotationMinutes > 0 || nonEmptyAssets.size > 1
            if (useRotationDir) {
                adbManager.ensureRemoteDirectory(CleanYourTvConstants.REMOTE_WALLPAPERS_DIR).getOrElse { err ->
                    val msg = "Failed to create wallpapers rotation folder on TV: ${err.message}"
                    _themeStatusMessage.value = msg
                    return Result.failure(err)
                }
                for ((idx, asset) in nonEmptyAssets.withIndex()) {
                    val safeName = sanitizeWallpaperFilename(asset.fileName, fallbackIndex = idx + 1)
                    val targetPath = "${CleanYourTvConstants.REMOTE_WALLPAPERS_DIR}/$safeName"
                    adbManager.pushAppConfigFile(targetPath, asset.bytes).getOrElse { err ->
                        val msg = "Failed to push wallpaper '$safeName': ${err.message}"
                        _themeStatusMessage.value = msg
                        return Result.failure(err)
                    }
                    val exists = adbManager.remoteFileExists(targetPath).getOrDefault(false)
                    if (!exists) {
                        val msg = "Wallpaper verification failed: '$targetPath' missing after transfer."
                        _themeStatusMessage.value = msg
                        return Result.failure(IllegalStateException(msg))
                    }
                    verifiedRemoteWallpapers.add(targetPath)
                }
                // Also deploy static wallpaper.jpg from first asset so non-rotation fallback works cleanly
                adbManager.pushAppConfigFile(CleanYourTvConstants.REMOTE_WALLPAPER_PATH, nonEmptyAssets.first().bytes)
                    .getOrElse { err ->
                        val msg = "Failed to push fallback wallpaper.jpg: ${err.message}"
                        _themeStatusMessage.value = msg
                        return Result.failure(err)
                    }
                if (!adbManager.remoteFileExists(CleanYourTvConstants.REMOTE_WALLPAPER_PATH).getOrDefault(false)) {
                    val msg = "Verification failed: '${CleanYourTvConstants.REMOTE_WALLPAPER_PATH}' missing on TV."
                    _themeStatusMessage.value = msg
                    return Result.failure(IllegalStateException(msg))
                }
            } else {
                val single = nonEmptyAssets.first()
                adbManager.pushAppConfigFile(CleanYourTvConstants.REMOTE_WALLPAPER_PATH, single.bytes).getOrElse { err ->
                    val msg = "Failed to push wallpaper.jpg to TV: ${err.message}"
                    _themeStatusMessage.value = msg
                    return Result.failure(err)
                }
                val exists = adbManager.remoteFileExists(CleanYourTvConstants.REMOTE_WALLPAPER_PATH).getOrDefault(false)
                if (!exists) {
                    val msg = "Wallpaper verification failed: '${CleanYourTvConstants.REMOTE_WALLPAPER_PATH}' not found on TV."
                    _themeStatusMessage.value = msg
                    return Result.failure(IllegalStateException(msg))
                }
                verifiedRemoteWallpapers.add(CleanYourTvConstants.REMOTE_WALLPAPER_PATH)
            }
            activeCfg = activeCfg.copy(wallpaperEnabled = true)
            _currentConfig.value = activeCfg
        } else if (activeCfg.wallpaperEnabled) {
            // Confirm wallpaper file already exists on TV before claiming wallpaper=true
            val staticExists = adbManager.remoteFileExists(CleanYourTvConstants.REMOTE_WALLPAPER_PATH).getOrDefault(false)
            if (!staticExists) {
                val msg = "Wallpaper is enabled, but '${CleanYourTvConstants.REMOTE_WALLPAPER_PATH}' does not exist on the TV. Pick a wallpaper image or disable Wallpaper first."
                _themeStatusMessage.value = msg
                return Result.failure(IllegalStateException(msg))
            }
        }

        // 4. Generate and push config.json
        val jsonString = CleanYourTvThemeCatalog.buildConfigJson(activeCfg)
        adbManager.pushAppConfigFile(
            CleanYourTvConstants.REMOTE_CONFIG_PATH,
            jsonString.toByteArray(Charsets.UTF_8)
        ).getOrElse { err ->
            val msg = "Failed to write config.json to TV: ${err.message}"
            _themeStatusMessage.value = msg
            return Result.failure(err)
        }

        // 5. Read back config.json from TV and validate JSON structure, keys, types & values
        val configExists = adbManager.remoteFileExists(CleanYourTvConstants.REMOTE_CONFIG_PATH).getOrDefault(false)
        if (!configExists) {
            val msg = "Theme verification failed: '${CleanYourTvConstants.REMOTE_CONFIG_PATH}' missing on TV after push."
            _themeStatusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val readBackBytes = adbManager.readRemoteFile(CleanYourTvConstants.REMOTE_CONFIG_PATH).getOrElse { err ->
            val msg = "Failed to read back config.json from TV for verification: ${err.message}"
            _themeStatusMessage.value = msg
            return Result.failure(err)
        }
        val readBackText = readBackBytes.toString(Charsets.UTF_8)
        CleanYourTvThemeCatalog.validateDeployedConfig(readBackText, activeCfg).getOrElse { err ->
            val msg = "TV config.json verification failed: ${err.message}"
            _themeStatusMessage.value = msg
            return Result.failure(err)
        }

        // Track previous config for rollback only after full verification succeeded
        if (lastAppliedConfigSnapshot != null && lastAppliedConfigSnapshot != activeCfg) {
            _previousAppliedConfig.value = lastAppliedConfigSnapshot
        } else if (lastAppliedConfigSnapshot == null) {
            _previousAppliedConfig.value = CleanYourTvThemeConfig()
        }
        lastAppliedConfigSnapshot = activeCfg

        // 6. Safely restart CleanYourTV launcher so it reloads the verified config.json
        val restartRes = adbManager.restartCleanYourTvLauncher()
        val devInfo = adbManager.inspectTvDevice().getOrNull()
        val isActiveHome = devInfo?.currentHomePackage == CleanYourTvConstants.LAUNCHER_PACKAGE

        val wpSummary = if (verifiedRemoteWallpapers.isNotEmpty()) {
            " • Verified ${verifiedRemoteWallpapers.size} wallpaper asset(s)"
        } else if (activeCfg.wallpaperEnabled) {
            " • Verified existing wallpaper.jpg"
        } else ""

        val restartNote = when {
            restartRes.isFailure -> " WARNING: Config verified on TV, but automatic CleanYourTV restart failed (${restartRes.exceptionOrNull()?.message}). Press HOME on TV to reload."
            isActiveHome -> " CleanYourTV Home restarted and verified active."
            else -> " CleanYourTV Home relaunched (current default HOME is '${devInfo?.currentHomePackage ?: "stock"}'; tap 'Set as TV Home' to make CleanYourTV permanent)."
        }

        _themeStatusMessage.value =
            "Verified config.json (${activeCfg.selectedPresetId}$wpSummary).$restartNote"
        return Result.success(readBackText)
    }

    suspend fun resetThemeOnTv(): Result<Unit> {
        adbManager.removeRemoteFile(CleanYourTvConstants.REMOTE_CONFIG_PATH).getOrElse { err ->
            _themeStatusMessage.value = "Failed to clear TV theme overrides: ${err.message}"
            return Result.failure(err)
        }
        adbManager.removeRemoteFile(CleanYourTvConstants.REMOTE_WALLPAPER_PATH)

        val stillExists = adbManager.remoteFileExists(CleanYourTvConstants.REMOTE_CONFIG_PATH).getOrDefault(false)
        if (stillExists) {
            val err = IllegalStateException("config.json still exists on TV after removal attempt.")
            _themeStatusMessage.value = err.message
            return Result.failure(err)
        }

        _previousAppliedConfig.value = _currentConfig.value
        _currentConfig.value = CleanYourTvThemeConfig()
        lastAppliedConfigSnapshot = _currentConfig.value

        adbManager.restartCleanYourTvLauncher()
        _themeStatusMessage.value = "Removed config.json overrides and restarted CleanYourTV Home."
        return Result.success(Unit)
    }

    suspend fun rollbackPreviousTheme(): Result<String> {
        val prev = _previousAppliedConfig.value
            ?: return Result.failure(IllegalStateException("No previous verified theme configuration available for rollback."))
        _currentConfig.value = prev
        return applyThemeToTv()
    }

    private fun sanitizeWallpaperFilename(raw: String, fallbackIndex: Int): String {
        val cleaned = raw.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "wallpaper_$fallbackIndex.jpg" }
        return if (cleaned.endsWith(".jpg", true) || cleaned.endsWith(".jpeg", true) || cleaned.endsWith(".png", true)) {
            cleaned
        } else {
            "$cleaned.jpg"
        }
    }
}
