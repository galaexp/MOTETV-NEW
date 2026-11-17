package com.gala.motetv.debloat

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TvPackageScannerAndSafetyTest {

    private val dummyAdbManager = object : AdbConnectionManager {
        override val connectionState: StateFlow<AdbConnectionState> = MutableStateFlow(AdbConnectionState.Disconnected)
        override val pairingState: StateFlow<AdbPairingState> = MutableStateFlow(AdbPairingState.Idle)
        override val diagnosticCategory: StateFlow<AdbDiagnosticCategory> = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices: StateFlow<List<DiscoveredAdbService>> = MutableStateFlow(emptyList())
        override val isDiscovering: StateFlow<Boolean> = MutableStateFlow(false)
        override val savedEndpoint: StateFlow<AdbEndpointConfig?> = MutableStateFlow(null)
        override val lastDiagnosticDetail: StateFlow<String> = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")
        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) =
            Result.failure<String>(UnsupportedOperationException())
        override suspend fun inspectTvDevice() = Result.failure<TvDeviceInfo>(IllegalStateException())
        override suspend fun listAllPackages() = Result.success(emptyList<String>())
        override suspend fun listDisabledPackages() = Result.success(emptySet<String>())
        override suspend fun disablePackageForUser(packageName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun enablePackage(packageName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, ""))
        override suspend fun setDefaultHomeActivity(componentName: String) = Result.success(AdbCommandResult(0, ""))
        override suspend fun restartCleanYourTvLauncher() = Result.success(AdbCommandResult(0, ""))
        override suspend fun installApk(apkBytes: ByteArray) = Result.success(AdbCommandResult(0, ""))
        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray) = Result.success(AdbCommandResult(0, ""))
        override suspend fun readRemoteFile(remotePath: String) = Result.failure<ByteArray>(IOException())
        override suspend fun remoteFileExists(remotePath: String) = Result.success(false)
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
    fun xiaomiMiTvStickIssue884AccessibilityInputRemoteAndCoreAreStrictlyBlocked() {
        val scanner = TvPackageScanner(dummyAdbManager, DebloatRepository(null))
        val packages = listOf(
            "android",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.tv.remote.service",
            "com.google.android.marvin.talkback",
            "com.google.android.inputmethod.latin",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.networkstack",
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

        // Mandatory critical recovery, Issue #884, Accessibility, Input, Remote, Network & Play Services blocked
        listOf(
            "android",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.modulemetadata",
            "com.google.android.overlay.modules.permissioncontroller",
            "com.google.android.tv.remote.service",
            "com.google.android.marvin.talkback",
            "com.google.android.inputmethod.latin",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.networkstack"
        ).forEach { critical ->
            val entry = byPkg.getValue(critical)
            assertTrue("Expected $critical to be blocked from disable", entry.isBlockedFromDisable)
            assertEquals(RiskLevel.CRITICAL_BLOCKED, entry.riskLevel)
            assertEquals(DebloatRecommendation.NEVER_DISABLE, entry.recommendation)
        }

        val launcher = byPkg.getValue("com.google.android.tvlauncher")
        assertTrue(launcher.isBlockedFromDisable)
        assertTrue(launcher.isOriginalHomeLauncher)

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

        val unknown = byPkg.getValue("com.vendor.mysterious.service")
        assertEquals(PackageCategory.UNKNOWN_UNVERIFIED, unknown.category)
        assertTrue(unknown.requiresOptionalPrompt)
        assertFalse(unknown.recommendation == DebloatRecommendation.SAFE_TO_DISABLE)

        assertFalse(byPkg.getValue("com.mitv.tvhome.atv").isEnabled)
        assertTrue(byPkg.getValue("com.miui.tv.analytics").isEnabled)
    }

    @Test
    fun otherManufacturerPackagesDoNotReceiveAutoRecommendationOnXiaomiStickAndUnknownModelsDefaultToManualReview() {
        val scanner = TvPackageScanner(dummyAdbManager, DebloatRepository(null))

        // On verified Xiaomi Mi TV Stick, Sony & TCL packages MUST require manual review (not SAFE_TO_DISABLE)
        val xiaomiScan = scanner.buildScanResult(
            deviceInfo = xiaomiStickDevice(),
            installedPackages = listOf(
                "com.miui.tv.analytics",
                "com.sony.dtv.osat.analytics",
                "com.tcl.telemetry"
            ),
            disabledPackages = emptySet()
        ).packages.associateBy { it.packageName }

        assertEquals(DebloatRecommendation.SAFE_TO_DISABLE, xiaomiScan.getValue("com.miui.tv.analytics").recommendation)
        assertEquals(DebloatRecommendation.OPTIONAL_ASK_USER, xiaomiScan.getValue("com.sony.dtv.osat.analytics").recommendation)
        assertTrue(xiaomiScan.getValue("com.sony.dtv.osat.analytics").requiresOptionalPrompt)
        assertEquals(DebloatRecommendation.OPTIONAL_ASK_USER, xiaomiScan.getValue("com.tcl.telemetry").recommendation)
        assertTrue(xiaomiScan.getValue("com.tcl.telemetry").requiresOptionalPrompt)

        // Unknown / non-Xiaomi model MUST have hasExactModelCatalogMatch == false and NO SAFE_TO_DISABLE items!
        val unknownTv = TvDeviceInfo(
            manufacturer = "UnknownBrand",
            model = "GenericTV-9000",
            deviceName = "generic_tv",
            productBoard = "unknown",
            androidVersion = "11",
            sdkVersion = 30,
            buildId = "RTT0",
            buildFingerprint = "UnknownBrand/generic/generic:11:user/release-keys",
            currentHomePackage = "com.google.android.tvlauncher",
            currentHomeActivity = "com.google.android.tvlauncher/.MainActivity",
            currentHomeLabel = "Android TV Home"
        )
        val unknownScan = scanner.buildScanResult(
            deviceInfo = unknownTv,
            installedPackages = listOf(
                "com.miui.tv.analytics",
                "com.mitv.tvhome.atv",
                "com.sony.dtv.osat.analytics",
                "com.vendor.unknown.app"
            ),
            disabledPackages = emptySet()
        )
        assertFalse("Unknown non-Xiaomi device MUST NOT claim exact catalog match", unknownScan.hasExactModelCatalogMatch)
        assertTrue(
            "Unknown device model must default all packages to manual review or protected",
            unknownScan.packages.all { it.recommendation != DebloatRecommendation.SAFE_TO_DISABLE }
        )
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
