package com.gala.motetv.debloat

import com.gala.motetv.adb.AdbCommandResult
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionMode
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.adb.AdbEndpointConfig
import com.gala.motetv.adb.AdbPairingCapability
import com.gala.motetv.adb.DiscoveredAdbService
import com.gala.motetv.adb.TvDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPackageScannerAndSafetyTest {

    private val dummyAdbManager = object : AdbConnectionManager {
        override val connectionState: StateFlow<AdbConnectionState> = MutableStateFlow(AdbConnectionState.Disconnected)
        override val discoveredServices: StateFlow<List<DiscoveredAdbService>> = MutableStateFlow(emptyList())
        override val isDiscovering: StateFlow<Boolean> = MutableStateFlow(false)
        override val savedEndpoint: StateFlow<AdbEndpointConfig?> = MutableStateFlow(null)
        override val lastDiagnosticDetail: StateFlow<String> = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")
        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) = Result.failure<String>(UnsupportedOperationException())
        override suspend fun inspectTvDevice() = Result.failure<TvDeviceInfo>(IllegalStateException())
        override suspend fun listAllPackages() = Result.success(emptyList<String>())
        override suspend fun listDisabledPackages() = Result.success(emptySet<String>())
        override suspend fun disablePackageForUser(packageName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun enablePackage(packageName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, ""))
        override suspend fun setDefaultHomeActivity(componentName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun installApk(apkBytes: ByteArray) = Result.success(AdbCommandResult(0, ""))
        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray) = Result.success(AdbCommandResult(0, ""))
        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun removeRemoteFile(remotePath: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun checkFilePushSupported() = Result.success(true)
    }

    private fun xiaomiStickDevice() = TvDeviceInfo(
        manufacturer = "Xiaomi",
        model = "MiTV-AESP0",
        deviceName = "aquaman",
        productBoard = "aquaman",
        androidVersion = "9",
        sdkVersion = 28,
        buildId = "PI.3115",
        buildFingerprint = "Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys",
        currentHomePackage = "com.google.android.tvlauncher",
        currentHomeActivity = "com.google.android.tvlauncher/.MainActivity",
        currentHomeLabel = "Android TV Home"
    )

    @Test
    fun xiaomiMiTvStickIssue884AndCorePackagesAreStrictlyBlocked() {
        val scanner = TvPackageScanner(dummyAdbManager, DebloatRepository(null))
        val packages = listOf(
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.tv.remote.service",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.tvlauncher",
            "com.miui.tv.analytics",
            "com.mitv.tvhome.atv",
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.google.android.apps.mediashell",
            "com.google.android.katniss",
            "com.xiaomi.mitv.updateservice",
            "com.vendor.mysterious.service"
        )

        val scan = scanner.buildScanResult(
            deviceInfo = xiaomiStickDevice(),
            installedPackages = packages,
            disabledPackages = setOf("com.mitv.tvhome.atv")
        )
        assertTrue(scan.hasExactModelCatalogMatch)

        val byPkg = scan.packages.associateBy { it.packageName }

        // Issue #884 & critical core services MUST be blocked
        listOf(
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.tv.remote.service",
            "com.android.vending",
            "com.google.android.gms"
        ).forEach { critical ->
            val entry = byPkg.getValue(critical)
            assertTrue("Expected $critical to be blocked from disable", entry.isBlockedFromDisable)
            assertEquals(RiskLevel.CRITICAL_BLOCKED, entry.riskLevel)
            assertEquals(DebloatRecommendation.NEVER_DISABLE, entry.recommendation)
        }

        // Active HOME launcher blocked when it is currently active
        val launcher = byPkg.getValue("com.google.android.tvlauncher")
        assertTrue(launcher.isBlockedFromDisable)
        assertTrue(launcher.isOriginalHomeLauncher)

        // Optional apps require asking user and are never marked SAFE_TO_DISABLE
        listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.google.android.apps.mediashell",
            "com.google.android.katniss",
            "com.xiaomi.mitv.updateservice"
        ).forEach { opt ->
            val entry = byPkg.getValue(opt)
            assertTrue("$opt must require optional user prompt", entry.requiresOptionalPrompt)
            assertEquals(DebloatRecommendation.OPTIONAL_ASK_USER, entry.recommendation)
        }

        // Unknown package grouped cleanly & never SAFE_TO_DISABLE
        val unknown = byPkg.getValue("com.vendor.mysterious.service")
        assertEquals(PackageCategory.UNKNOWN_UNVERIFIED, unknown.category)
        assertTrue(unknown.requiresOptionalPrompt)
        assertFalse(unknown.recommendation == DebloatRecommendation.SAFE_TO_DISABLE)

        // Disabled status preserved accurately
        assertFalse(byPkg.getValue("com.mitv.tvhome.atv").isEnabled)
        assertTrue(byPkg.getValue("com.miui.tv.analytics").isEnabled)
    }

    @Test
    fun unverifiedXiaomiVariantDoesNotAutoApproveGenericList() {
        val unverifiedXiaomi = TvDeviceInfo(
            manufacturer = "Xiaomi",
            model = "MiTV-MOOQ9",
            deviceName = "taro_unverified",
            productBoard = "taro_unverified",
            androidVersion = "11",
            sdkVersion = 30,
            buildId = "RTT0.210",
            buildFingerprint = "Xiaomi/taro/taro:11/RTT0:user/release-keys",
            currentHomePackage = "com.google.android.tvlauncher",
            currentHomeActivity = "com.google.android.tvlauncher/.MainActivity",
            currentHomeLabel = "Android TV Home"
        )
        val scanner = TvPackageScanner(dummyAdbManager, DebloatRepository(null))
        val scan = scanner.buildScanResult(
            deviceInfo = unverifiedXiaomi,
            installedPackages = listOf("com.miui.tv.analytics", "com.mitv.tvhome.atv"),
            disabledPackages = emptySet()
        )
        assertFalse("Unverified Xiaomi variant must NOT claim exact catalog match", scan.hasExactModelCatalogMatch)
        assertTrue(scan.packages.all { it.recommendation != DebloatRecommendation.SAFE_TO_DISABLE })
    }

    @Test
    fun stockLauncherGuardAllowsDisableOnlyAfterCleanYourTvHomeVerifiedActive() {
        val deviceAfterSwitch = xiaomiStickDevice().copy(
            currentHomePackage = "com.gathin.tvhome",
            currentHomeActivity = "com.gathin.tvhome/.MainActivity"
        )

        val blockedBeforeInstall = PackageSafetyGuard.evaluateDisableCandidate(
            packageName = "com.google.android.tvlauncher",
            deviceInfo = xiaomiStickDevice(),
            installedPackages = setOf("com.google.android.tvlauncher"),
            verifiedReplacementHomeInstalled = false,
            verifiedReplacementHomeActive = false
        )
        assertFalse(blockedBeforeInstall.allowed)

        val allowedAfterVerified = PackageSafetyGuard.evaluateDisableCandidate(
            packageName = "com.google.android.tvlauncher",
            deviceInfo = deviceAfterSwitch,
            installedPackages = setOf("com.google.android.tvlauncher", "com.gathin.tvhome"),
            verifiedReplacementHomeInstalled = true,
            verifiedReplacementHomeActive = true
        )
        assertTrue(allowedAfterVerified.allowed)
    }
}
