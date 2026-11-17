package com.gala.motetv.tvhome

object CleanYourTvConstants {
    const val LAUNCHER_PACKAGE = "com.gathin.tvhome"
    const val LAUNCHER_COMPONENT = "com.gathin.tvhome/.MainActivity"
    const val REMOTE_FILES_DIR = "/sdcard/Android/data/com.gathin.tvhome/files"
    const val REMOTE_CONFIG_PATH = "/sdcard/Android/data/com.gathin.tvhome/files/config.json"
    const val REMOTE_WALLPAPER_PATH = "/sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg"
    const val REMOTE_WALLPAPERS_DIR = "/sdcard/Android/data/com.gathin.tvhome/files/wallpapers"
    const val MIN_SUPPORTED_SDK = 21
}

enum class ThemePresetCategory(val label: String) {
    MINIMAL_GLASS("Minimal & Glass"),
    SCENIC_ART("Scenic & Regional"),
    HIGH_CONTRAST("Retro & Bold")
}

data class CleanYourTvThemePreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: ThemePresetCategory,
    val defaultAccentHex: String,
    val defaultBackgroundHex: String,
    val previewPrimaryHex: String,
    val previewSecondaryHex: String,
    val defaultColumns: Int = 5,
    val defaultDockRow: Boolean = true,
    val defaultSingleRow: Boolean = false,
    val defaultDockBandAll: Boolean = false,
    val defaultDockGlass: Boolean = false,
    val defaultDockOpacity: Int = 78,
    val defaultAmbientGlow: Boolean = true,
    val defaultGlowSnap: Boolean = false,
    val defaultSkyIntensity: Int = 60,
    val defaultWallpaperEnabled: Boolean = false,
    val defaultWallpaperDim: Int = 25,
    val attributionNote: String = "CleanYourTV (MIT License)"
)

/**
 * Typed model matching the official Gathin23/cleanyourtv launcher `config.json` schema.
 * [selectedPresetId] is local MoteTV UI metadata only and is never written into `config.json`.
 */
data class CleanYourTvThemeConfig(
    val selectedPresetId: String = "default",
    val backgroundColorHex: String = "#090D16",
    val accentColorHex: String = "#38BDF8",
    val columns: Int = 5,
    val dockRow: Boolean = true,
    val singleRow: Boolean = false,
    val dockBandAll: Boolean = false,
    val dockGlass: Boolean = false,
    val dockOpacity: Int = 78,
    val ambientGlow: Boolean = true,
    val glowSnap: Boolean = false,
    val skyIntensity: Int = 60,
    val clockFormat: String = "system",
    val showStats: Boolean = false,
    val reducedMotion: Boolean = false,
    val greetingName: String = "",
    val wallpaperEnabled: Boolean = false,
    val wallpaperRotationMinutes: Int = 0,
    val wallpaperOrder: String = "sequential",
    val wallpaperDim: Int = 25,
    val wallpaperCaptions: Boolean = false,
    val dockApps: List<String> = emptyList(),
    val hiddenApps: List<String> = emptyList()
)

data class WallpaperUploadAsset(
    val fileName: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WallpaperUploadAsset) return false
        return fileName == other.fileName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}

data class CleanYourTvLauncherStatus(
    val isSdkCompatible: Boolean = false,
    val tvSdkVersion: Int = 0,
    val isSideloadingSupported: Boolean = true,
    val isCleanYourTvInstalled: Boolean = false,
    val isCleanYourTvActiveHome: Boolean = false,
    val currentHomePackage: String? = null,
    val currentHomeActivity: String? = null,
    val originalHomePackage: String? = null,
    val originalHomeActivity: String? = null,
    val isOriginalHomeAvailable: Boolean = true,
    val filePushSupported: Boolean = true,
    val statusDetail: String = "Connect TV via ADB to inspect HOME launcher & CleanYourTV status."
)
