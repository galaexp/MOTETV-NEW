package com.gala.motetv.tvhome

import com.gala.motetv.adb.AdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Customizes and deploys CleanYourTV Home (`com.gathin.tvhome`) theme configuration JSON and
 * optional custom wallpaper directly to the TV's external files directory over ADB sync.
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
        _currentConfig.value = _currentConfig.value.copy(
            themeId = preset.id,
            accentColorHex = preset.defaultAccentHex,
            backgroundColorHex = preset.defaultBackgroundHex,
            columns = preset.defaultColumns,
            dockSlots = preset.defaultDockSlots,
            clockStyle = preset.defaultClockStyle,
            dockBlur = preset.defaultDockBlur
        )
        _themeStatusMessage.value = "Selected preset '${preset.name}' for TV Home preview. Tap 'Apply to TV' to deploy."
    }

    fun updateConfig(transform: (CleanYourTvThemeConfig) -> CleanYourTvThemeConfig) {
        _currentConfig.value = transform(_currentConfig.value)
    }

    suspend fun applyThemeToTv(
        wallpaperBytes: ByteArray? = null,
        wallpaperName: String? = null
    ): Result<String> {
        val dirRes = adbManager.ensureRemoteDirectory(CleanYourTvConstants.REMOTE_FILES_DIR)
        if (dirRes.isFailure) {
            val err = dirRes.exceptionOrNull()!!
            _themeStatusMessage.value = "TV storage directory inaccessible: ${err.message}"
            return Result.failure(err)
        }

        var activeCfg = _currentConfig.value
        if (wallpaperBytes != null && wallpaperBytes.isNotEmpty()) {
            val cleanName = (wallpaperName ?: "custom_wallpaper.jpg")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val remoteWallpaper = "${CleanYourTvConstants.REMOTE_FILES_DIR}/$cleanName"
            val wpRes = adbManager.pushAppConfigFile(remoteWallpaper, wallpaperBytes)
            if (wpRes.isFailure) {
                val err = wpRes.exceptionOrNull()!!
                _themeStatusMessage.value = "Failed to push custom wallpaper to TV: ${err.message}"
                return Result.failure(err)
            }
            activeCfg = activeCfg.copy(wallpaperFileName = cleanName)
            _currentConfig.value = activeCfg
        }

        val jsonString = CleanYourTvThemeCatalog.buildConfigJson(activeCfg)
        val pushRes = adbManager.pushAppConfigFile(
            CleanYourTvConstants.REMOTE_CONFIG_PATH,
            jsonString.toByteArray(Charsets.UTF_8)
        )
        if (pushRes.isFailure) {
            val err = pushRes.exceptionOrNull()!!
            _themeStatusMessage.value = "Failed to deploy theme config to TV: ${err.message}"
            return Result.failure(err)
        }

        if (lastAppliedConfigSnapshot != null && lastAppliedConfigSnapshot != activeCfg) {
            _previousAppliedConfig.value = lastAppliedConfigSnapshot
        } else if (lastAppliedConfigSnapshot == null) {
            _previousAppliedConfig.value = CleanYourTvThemeConfig()
        }
        lastAppliedConfigSnapshot = activeCfg

        // Re-trigger HOME so CleanYourTV picks up config.json overrides cleanly
        adbManager.launchHomeChooserOrOpenHome()

        _themeStatusMessage.value = "Applied '${activeCfg.themeId}' theme to TV Home (${CleanYourTvConstants.REMOTE_CONFIG_PATH})."
        return Result.success(jsonString)
    }

    suspend fun resetThemeOnTv(): Result<Unit> {
        val prev = _currentConfig.value
        val rmRes = adbManager.removeRemoteFile(CleanYourTvConstants.REMOTE_CONFIG_PATH)
        if (rmRes.isFailure) {
            val err = rmRes.exceptionOrNull()!!
            _themeStatusMessage.value = "Failed to clear TV theme overrides: ${err.message}"
            return Result.failure(err)
        }
        prev.wallpaperFileName?.takeIf { it.isNotBlank() }?.let { wp ->
            adbManager.removeRemoteFile("${CleanYourTvConstants.REMOTE_FILES_DIR}/$wp")
        }
        _previousAppliedConfig.value = prev
        _currentConfig.value = CleanYourTvThemeConfig()
        lastAppliedConfigSnapshot = _currentConfig.value
        adbManager.launchHomeChooserOrOpenHome()
        _themeStatusMessage.value = "Reset CleanYourTV Home theme overrides on TV to Default."
        return Result.success(Unit)
    }

    suspend fun rollbackPreviousTheme(): Result<String> {
        val prev = _previousAppliedConfig.value
            ?: return Result.failure(IllegalStateException("No previous theme configuration available for rollback."))
        _currentConfig.value = prev
        return applyThemeToTv()
    }
}
