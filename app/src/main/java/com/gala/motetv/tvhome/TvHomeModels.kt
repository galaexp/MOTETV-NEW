package com.gala.motetv.tvhome

object CleanYourTvConstants {
    const val LAUNCHER_PACKAGE = "com.gathin.tvhome"
    const val LAUNCHER_COMPONENT = "com.gathin.tvhome/.MainActivity"
    const val REMOTE_FILES_DIR = "/sdcard/Android/data/com.gathin.tvhome/files"
    const val REMOTE_CONFIG_PATH = "/sdcard/Android/data/com.gathin.tvhome/files/config.json"
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
    val defaultDockSlots: Int = 5,
    val defaultClockStyle: String = "minimal",
    val defaultDockBlur: Boolean = true,
    val attributionNote: String = "CleanYourTV (MIT License)"
)

data class CleanYourTvThemeConfig(
    val themeId: String = "default",
    val accentColorHex: String = "#38BDF8",
    val backgroundColorHex: String = "#090D16",
    val columns: Int = 5,
    val dockSlots: Int = 5,
    val dockApps: List<String> = emptyList(),
    val hiddenApps: List<String> = emptyList(),
    val dockBlur: Boolean = true,
    val clockFormat: String = "24h",
    val clockStyle: String = "minimal",
    val showStats: Boolean = false,
    val reducedMotion: Boolean = false,
    val wallpaperFileName: String? = null
)

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
