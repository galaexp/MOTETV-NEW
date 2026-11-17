package com.gala.motetv.tvhome

import com.gala.motetv.adb.AdbCommandResult
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionMode
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.adb.AdbDiagnosticCategory
import com.gala.motetv.adb.AdbEndpointConfig
import com.gala.motetv.adb.AdbPairingCapability
import com.gala.motetv.adb.AdbPairingState
import com.gala.motetv.adb.DiscoveredAdbService
import com.gala.motetv.adb.TvDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CleanYourTvLauncherAndThemeTest {

    @Test
    fun launcherLifecycleInstallsSwitchesVerifiesAndRestoresOriginalHome() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        val launcherManager = CleanYourTvLauncherManager(context = null, adbManager = adb)

        val initial = launcherManager.refreshLauncherStatus().getOrThrow()
        assertTrue(initial.isSdkCompatible)
        assertFalse(initial.isCleanYourTvInstalled)
        assertEquals("com.google.android.tvlauncher", initial.currentHomePackage)
        assertEquals("com.google.android.tvlauncher", initial.originalHomePackage)

        // Cannot activate before APK installed
        assertTrue(launcherManager.activateCleanYourTvAsHome().isFailure)

        // Install CleanYourTV Home APK -> verifies package appears without touching stock launcher
        val installed = launcherManager.installCleanYourTvApk(byteArrayOf(0x50, 0x4B, 0x03, 0x04)).getOrThrow()
        assertTrue(installed.isCleanYourTvInstalled)
        assertTrue(adb.installedPackages.contains("com.google.android.tvlauncher"))

        // Switch default HOME to CleanYourTV Home & verify active HOME resolved to com.gathin.tvhome
        val switched = launcherManager.activateCleanYourTvAsHome().getOrThrow()
        assertTrue(switched.isCleanYourTvActiveHome)
        assertEquals(CleanYourTvConstants.LAUNCHER_PACKAGE, switched.currentHomePackage)
        assertEquals("com.google.android.tvlauncher", switched.originalHomePackage)

        // Restore original stock HOME & verify
        val restored = launcherManager.restoreOriginalHomeLauncher().getOrThrow()
        assertFalse(restored.isCleanYourTvActiveHome)
        assertEquals("com.google.android.tvlauncher", restored.currentHomePackage)
        assertTrue(adb.enabledPackages.contains("com.google.android.tvlauncher"))
    }

    @Test
    fun handlesFirmwareRefusalToSetHomeSafelyWithoutDisablingOriginalLauncher() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 29, failSetHomeActivity = true)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val launcherManager = CleanYourTvLauncherManager(context = null, adbManager = adb)

        val activateRes = launcherManager.activateCleanYourTvAsHome()
        assertTrue(activateRes.isFailure)
        assertEquals("com.google.android.tvlauncher", launcherManager.launcherStatus.value.currentHomePackage)
        assertTrue(launcherManager.launcherStatus.value.statusDetail.contains("Original HOME kept safely active"))
    }

    @Test
    fun themeCatalogMatchesRealCleanYourTvSchemaWithoutUnsupportedKeys() = runTest {
        assertTrue(CleanYourTvThemeCatalog.PRESETS.size >= 29)

        val config = CleanYourTvThemeConfig(
            selectedPresetId = "dock-glass",
            backgroundColorHex = "#0a0f1a",
            accentColorHex = "INVALID",
            columns = 6,
            dockRow = true,
            singleRow = false,
            dockBandAll = true,
            dockGlass = true,
            dockOpacity = 65,
            ambientGlow = true,
            glowSnap = false,
            skyIntensity = 80,
            clockFormat = "12h", // normalizes to "12"
            showStats = true,
            reducedMotion = true,
            greetingName = "Living Room",
            wallpaperEnabled = true,
            wallpaperRotationMinutes = 30,
            wallpaperOrder = "random",
            wallpaperDim = 35,
            wallpaperCaptions = true,
            dockApps = listOf("com.netflix.ninja"),
            hiddenApps = listOf("com.example.bloat")
        )

        val jsonText = CleanYourTvThemeCatalog.buildConfigJson(config)
        val parsed = JSONObject(jsonText)

        // Every emitted key MUST belong to real CleanYourTV supported schema
        val keys = mutableSetOf<String>()
        parsed.keys().forEach { keys.add(it) }
        assertEquals(CleanYourTvThemeCatalog.SUPPORTED_CONFIG_KEYS, keys)

        // Hallucinated / legacy wrong keys MUST NOT exist
        assertFalse(parsed.has("theme_id"))
        assertFalse(parsed.has("dock_slots"))
        assertFalse(parsed.has("clock_style"))
        assertFalse(parsed.has("dock_blur"))

        // Critical field types & CleanYourTV values
        assertTrue("wallpaper MUST be Boolean", parsed.get("wallpaper") is Boolean)
        assertEquals(true, parsed.getBoolean("wallpaper"))
        assertEquals("#0A0F1A", parsed.getString("background_color"))
        assertEquals("#38BDF8", parsed.getString("accent_color"))
        assertEquals(6, parsed.getInt("columns"))
        assertEquals(true, parsed.getBoolean("dock_glass"))
        assertEquals(65, parsed.getInt("dock_opacity"))
        assertEquals("12", parsed.getString("clock_format"))
        assertEquals(30, parsed.getInt("wallpaper_rotation_minutes"))
        assertEquals("random", parsed.getString("wallpaper_order"))
        assertEquals(35, parsed.getInt("wallpaper_dim"))
        assertEquals(true, parsed.getBoolean("wallpaper_captions"))
        assertEquals(80, parsed.getInt("sky_intensity"))
        assertEquals("Living Room", parsed.getString("greeting_name"))

        assertTrue(CleanYourTvThemeCatalog.validateDeployedConfig(jsonText, config).isSuccess)
    }

    @Test
    fun applyThemeDeploysWallpaperVerifiesReadBackRestartsLauncherAndSupportsRollbackReset() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        adb.installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val themeManager = CleanYourTvThemeManager(adb)

        val aurora = CleanYourTvThemeCatalog.PRESETS.first { it.id == "aurora" }
        themeManager.selectPreset(aurora)
        themeManager.updateConfig {
            it.copy(
                columns = 6,
                dockGlass = true,
                dockOpacity = 70,
                clockFormat = "12",
                showStats = true,
                reducedMotion = true
            )
        }

        // Single static wallpaper pushes directly to /files/wallpaper.jpg and verifies boolean wallpaper=true
        val jsonText = themeManager.applyThemeToTv(
            wallpaperBytes = byteArrayOf(1, 2, 3, 4),
            wallpaperName = "aurora.jpg"
        ).getOrThrow()

        val parsed = JSONObject(jsonText)
        assertEquals(true, parsed.getBoolean("wallpaper"))
        assertEquals("#34D399", parsed.getString("accent_color"))
        assertTrue(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_WALLPAPER_PATH))
        assertTrue(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_CONFIG_PATH))
        assertEquals(1, adb.restartCalls)

        // Multi-wallpaper with rotation deploys into /files/wallpapers/
        themeManager.updateConfig { it.copy(wallpaperRotationMinutes = 60, wallpaperOrder = "random") }
        themeManager.applyThemeToTv(
            listOf(
                WallpaperUploadAsset("sunset 1.jpg", byteArrayOf(9, 8, 7)),
                WallpaperUploadAsset("forest 2.jpg", byteArrayOf(6, 5, 4))
            )
        ).getOrThrow()
        assertTrue(adb.pushedFiles.containsKey("${CleanYourTvConstants.REMOTE_WALLPAPERS_DIR}/sunset_1.jpg"))
        assertTrue(adb.pushedFiles.containsKey("${CleanYourTvConstants.REMOTE_WALLPAPERS_DIR}/forest_2.jpg"))
        assertEquals(2, adb.restartCalls)

        // Switch to Onyx, then rollback to previous theme
        themeManager.selectPreset(CleanYourTvThemeCatalog.PRESETS.first { it.id == "onyx" })
        themeManager.updateConfig { it.copy(wallpaperEnabled = false, wallpaperRotationMinutes = 0) }
        themeManager.applyThemeToTv().getOrThrow()
        assertEquals("#000000", JSONObject(adb.pushedFiles[CleanYourTvConstants.REMOTE_CONFIG_PATH]!!.decodeToString()).getString("background_color"))

        val rolledBack = themeManager.rollbackPreviousTheme().getOrThrow()
        assertEquals("#071A18", JSONObject(rolledBack).getString("background_color"))

        // Reset removes config.json + wallpaper.jpg & safely restarts CleanYourTV
        themeManager.resetThemeOnTv().getOrThrow()
        assertFalse(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_CONFIG_PATH))
        assertFalse(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_WALLPAPER_PATH))
    }

    @Test
    fun applyThemeFailsHonestlyWhenLauncherMissingOrReadBackCorrupted() = runTest {
        // Case 1: CleanYourTV not installed -> fails loudly without pushing config
        val adbMissing = FakeHomeAdbManager(sdkVersion = 28)
        val mgrMissing = CleanYourTvThemeManager(adbMissing)
        assertTrue(mgrMissing.applyThemeToTv().isFailure)
        assertTrue(adbMissing.pushedFiles.isEmpty())

        // Case 2: Readback from TV returns corrupted/mismatched config -> fails verification!
        val adbCorrupt = FakeHomeAdbManager(sdkVersion = 28, corruptReadBack = true).apply {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
        }
        val mgrCorrupt = CleanYourTvThemeManager(adbCorrupt)
        val res = mgrCorrupt.applyThemeToTv()
        assertTrue("Corrupted read-back must reject fake success", res.isFailure)
        assertEquals(0, adbCorrupt.restartCalls)
    }

    private class FakeHomeAdbManager(
        private val sdkVersion: Int,
        private val failSetHomeActivity: Boolean = false,
        private val corruptReadBack: Boolean = false
    ) : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        override val pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
        override val diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
        override val isDiscovering = MutableStateFlow(false)
        override val savedEndpoint = MutableStateFlow<AdbEndpointConfig?>(null)
        override val lastDiagnosticDetail = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")

        val installedPackages = linkedSetOf("com.google.android.tvlauncher", "com.android.settings")
        val enabledPackages = linkedSetOf("com.google.android.tvlauncher")
        var currentHomePackage = "com.google.android.tvlauncher"
        var currentHomeActivity = "com.google.android.tvlauncher/.MainActivity"
        val pushedFiles = linkedMapOf<String, ByteArray>()
        var restartCalls = 0

        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) =
            Result.failure<String>(UnsupportedOperationException())

        override suspend fun inspectTvDevice(): Result<TvDeviceInfo> = Result.success(
            TvDeviceInfo(
                manufacturer = "Xiaomi",
                model = "MiTV-AESP0",
                deviceName = "aquaman",
                productBoard = "aquaman",
                androidVersion = "9",
                sdkVersion = sdkVersion,
                buildId = "PI.3115",
                buildFingerprint = "Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys",
                currentHomePackage = currentHomePackage,
                currentHomeActivity = currentHomeActivity,
                currentHomeLabel = currentHomePackage
            )
        )

        override suspend fun listAllPackages() = Result.success(installedPackages.toList())
        override suspend fun listDisabledPackages() = Result.success(emptySet<String>())
        override suspend fun disablePackageForUser(packageName: String) = Result.success(AdbCommandResult(0, "disabled"))
        override suspend fun enablePackage(packageName: String): Result<AdbCommandResult> {
            enabledPackages.add(packageName)
            return Result.success(AdbCommandResult(0, "enabled"))
        }

        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, "Launched HOME"))

        override suspend fun setDefaultHomeActivity(componentName: String): Result<AdbCommandResult> {
            if (failSetHomeActivity) {
                return Result.failure(IOException("SecurityException: Permission denied by OEM firmware"))
            }
            currentHomeActivity = componentName
            currentHomePackage = componentName.substringBefore('/')
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun restartCleanYourTvLauncher(): Result<AdbCommandResult> {
            restartCalls++
            return Result.success(AdbCommandResult(0, "Restarted com.gathin.tvhome"))
        }

        override suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult> {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray): Result<AdbCommandResult> {
            pushedFiles[remotePath] = content
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun readRemoteFile(remotePath: String): Result<ByteArray> {
            if (corruptReadBack) {
                return Result.success("""{"wallpaper":"wrong_string_type","theme_id":"bad"}""".toByteArray())
            }
            val bytes = pushedFiles[remotePath] ?: return Result.failure(IOException("Missing $remotePath"))
            return Result.success(bytes)
        }

        override suspend fun remoteFileExists(remotePath: String): Result<Boolean> {
            return Result.success(pushedFiles.containsKey(remotePath))
        }

        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, "OK"))

        override suspend fun removeRemoteFile(remotePath: String): Result<AdbCommandResult> {
            pushedFiles.remove(remotePath)
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
