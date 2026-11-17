package com.gala.motetv.tvhome

import com.gala.motetv.adb.AdbCommandResult
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionMode
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.adb.AdbEndpointConfig
import com.gala.motetv.adb.AdbPairingCapability
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
    fun themeCatalogGeneratesValidConfigJsonAndManagerSupportsApplyRollbackAndReset() = runTest {
        val adb = FakeHomeAdbManager(sdkVersion = 28)
        val themeManager = CleanYourTvThemeManager(adb)

        assertTrue(CleanYourTvThemeCatalog.PRESETS.size >= 29)
        val aurora = CleanYourTvThemeCatalog.PRESETS.first { it.id == "aurora" }
        themeManager.selectPreset(aurora)
        themeManager.updateConfig {
            it.copy(
                columns = 6,
                dockSlots = 7,
                clockFormat = "12h",
                showStats = true,
                reducedMotion = true,
                accentColorHex = "INVALID" // should sanitize cleanly
            )
        }

        val jsonText = themeManager.applyThemeToTv(
            wallpaperBytes = byteArrayOf(1, 2, 3),
            wallpaperName = "aurora backdrop.png"
        ).getOrThrow()

        val parsed = JSONObject(jsonText)
        assertEquals("aurora", parsed.getString("theme_id"))
        assertEquals("#38BDF8", parsed.getString("accent_color"))
        assertEquals(6, parsed.getInt("columns"))
        assertEquals(7, parsed.getInt("dock_slots"))
        assertEquals("12h", parsed.getString("clock_format"))
        assertTrue(parsed.getBoolean("show_stats"))
        assertTrue(parsed.getBoolean("reduced_motion"))
        assertEquals("aurora_backdrop.png", parsed.getString("wallpaper"))

        assertTrue(adb.pushedFiles.containsKey("${CleanYourTvConstants.REMOTE_FILES_DIR}/aurora_backdrop.png"))
        assertTrue(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_CONFIG_PATH))

        // Switch to another theme then rollback
        themeManager.selectPreset(CleanYourTvThemeCatalog.PRESETS.first { it.id == "onyx" })
        themeManager.applyThemeToTv().getOrThrow()
        assertEquals("onyx", JSONObject(adb.pushedFiles[CleanYourTvConstants.REMOTE_CONFIG_PATH]!!.decodeToString()).getString("theme_id"))

        val rolledBackJson = themeManager.rollbackPreviousTheme().getOrThrow()
        assertEquals("aurora", JSONObject(rolledBackJson).getString("theme_id"))

        // Reset removes config.json & custom wallpaper
        themeManager.resetThemeOnTv().getOrThrow()
        assertFalse(adb.pushedFiles.containsKey(CleanYourTvConstants.REMOTE_CONFIG_PATH))
        assertFalse(adb.pushedFiles.containsKey("${CleanYourTvConstants.REMOTE_FILES_DIR}/aurora_backdrop.png"))
    }

    private class FakeHomeAdbManager(
        private val sdkVersion: Int,
        private val failSetHomeActivity: Boolean = false
    ) : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
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

        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) = Result.failure<String>(UnsupportedOperationException())

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

        override suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult> {
            installedPackages.add(CleanYourTvConstants.LAUNCHER_PACKAGE)
            return Result.success(AdbCommandResult(0, "Success"))
        }

        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray): Result<AdbCommandResult> {
            pushedFiles[remotePath] = content
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, "OK"))

        override suspend fun removeRemoteFile(remotePath: String): Result<AdbCommandResult> {
            pushedFiles.remove(remotePath)
            return Result.success(AdbCommandResult(0, "OK"))
        }

        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
