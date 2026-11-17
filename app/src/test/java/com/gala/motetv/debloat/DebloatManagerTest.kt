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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DebloatManagerTest {

    private fun stickDevice() = TvDeviceInfo(
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
    fun backupFailureAbortsDebloatBeforeAnyDisableCommandRuns() = runTest {
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = listOf("com.miui.tv.analytics", "com.mitv.tvhome.atv", "com.google.android.tvlauncher")
        )
        val failingRepo = DebloatRepository(context = null, simulateBackupFailureForTest = true)
        val scanner = TvPackageScanner(adb, failingRepo)
        val manager = DebloatManager(adb, scanner, failingRepo)

        assertTrue(manager.scanTv().isSuccess)
        val result = manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics"))
        assertTrue("Disable must fail if mandatory backup fails", result.isFailure)
        assertTrue("No pm disable command may run if backup fails", adb.disableCalls.isEmpty())
    }

    @Test
    fun disablesInSmallVerifiedBatchesExcludesPreDisabledFromMoteTvTrackingAndRestoresAll() = runTest {
        val pkgs = listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.mitv.tvhome.atv",
            "com.mitv.tvhome.michannel",
            "com.mitv.tvhome.mitvplus",
            "com.xm.webcontent", // pre-disabled before MoteTV!
            "com.google.android.tvlauncher",
            "com.google.android.permissioncontroller"
        )
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = pkgs,
            initialDisabled = setOf("com.xm.webcontent")
        )
        val repo = DebloatRepository(context = null)
        val scanner = TvPackageScanner(adb, repo)
        val manager = DebloatManager(adb, scanner, repo, timeProvider = { 1_700_000_000_000L })

        val scan = manager.scanTv().getOrThrow()
        assertTrue(scan.hasExactModelCatalogMatch)

        manager.togglePackageSelection("com.google.android.permissioncontroller")
        assertFalse(manager.selectedPackages.value.contains("com.google.android.permissioncontroller"))

        manager.selectRecommendedSafePackages()
        val selected = manager.selectedPackages.value
        assertEquals(5, selected.size)

        // Even if someone passes a pre-disabled package explicitly, it must NEVER be claimed as MoteTV-modified
        val batchRes = manager.disablePackagesWithBackup((selected + "com.xm.webcontent").sorted()).getOrThrow()
        assertEquals(6, batchRes.size)
        assertEquals(5, repo.getModifiedPackages().size)
        assertFalse("Pre-disabled package must NOT be tracked as modified by MoteTV", repo.getModifiedPackages().contains("com.xm.webcontent"))
        assertEquals(5, manager.modifiedPackages.value.size)

        val backup = manager.latestBackup.value
        assertNotNull(backup)
        assertEquals("com.google.android.tvlauncher", backup?.originalHomePackage)
        assertEquals(8, backup?.allInstalledPackages?.size)
        assertTrue(backup!!.originallyDisabledPackages.contains("com.xm.webcontent"))

        val exportJson = manager.exportBackupJson()!!
        assertTrue(exportJson.contains("com.google.android.tvlauncher"))
        assertTrue(exportJson.contains("operationHistory"))
        manager.markBackupExported("motetv_backup.json")

        // Restore all modified packages — pre-disabled com.xm.webcontent stays untouched!
        val restoreRes = manager.restoreAllModifiedByMoteTv().getOrThrow()
        assertEquals(5, restoreRes.size)
        assertTrue(restoreRes.all { it.succeeded })
        assertTrue(repo.getModifiedPackages().isEmpty())
        assertEquals(0, manager.modifiedPackages.value.size)
        assertFalse(adb.enableCalls.contains("com.xm.webcontent"))
    }

    @Test
    fun partialRestoreAttemptsAllPackagesAndReportsFailureHonestly() = runTest {
        val pkgs = listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.mitv.tvhome.atv",
            "com.google.android.tvlauncher"
        )
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = pkgs,
            failEnableOnPackages = setOf("com.miui.tv.analytics")
        )
        val repo = DebloatRepository(context = null)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

        assertTrue(manager.scanTv().isSuccess)
        manager.disablePackagesWithBackup(listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.mitv.tvhome.atv")).getOrThrow()
        assertEquals(3, repo.getModifiedPackages().size)

        // Restore all: analytics fails to re-enable, but statistic and atv MUST still be attempted & restored!
        val res = manager.restoreAllModifiedByMoteTv()
        assertTrue("Partial restore failure must return Result.failure", res.isFailure)
        assertEquals(3, adb.enableCalls.size)
        // Verified enabled packages are unmarked; failed package stays tracked for retry!
        assertEquals(setOf("com.miui.tv.analytics"), repo.getModifiedPackages())
        assertEquals(setOf("com.miui.tv.analytics"), manager.modifiedPackages.value)
    }

    @Test
    fun stopsImmediatelyWhenBatchCommandOrVerificationFails() = runTest {
        val pkgs = listOf(
            "com.miui.tv.analytics",
            "com.xiaomi.statistic",
            "com.mitv.tvhome.atv",
            "com.mitv.tvhome.michannel"
        )
        val adb = FakeDebloatAdbManager(
            deviceInfo = stickDevice(),
            allPackages = pkgs,
            ignoreDisableOnPackages = setOf("com.xiaomi.statistic")
        )
        val repo = DebloatRepository(context = null)
        val manager = DebloatManager(adb, TvPackageScanner(adb, repo), repo)

        assertTrue(manager.scanTv().isSuccess)
        val res = manager.disablePackagesWithBackup(pkgs)
        assertTrue("Verification mismatch on batch 1 must fail and stop batch 2", res.isFailure)
        assertFalse(
            "Batch 2 package must not be attempted after batch 1 verification failure",
            adb.disableCalls.contains("com.mitv.tvhome.michannel")
        )
    }

    private class FakeDebloatAdbManager(
        private val deviceInfo: TvDeviceInfo,
        private val allPackages: List<String>,
        initialDisabled: Set<String> = emptySet(),
        private val ignoreDisableOnPackages: Set<String> = emptySet(),
        private val failEnableOnPackages: Set<String> = emptySet()
    ) : AdbConnectionManager {
        override val connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
        override val pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
        override val diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
        override val discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
        override val isDiscovering = MutableStateFlow(false)
        override val savedEndpoint = MutableStateFlow<AdbEndpointConfig?>(null)
        override val lastDiagnosticDetail = MutableStateFlow("")
        override val pairingCapability = AdbPairingCapability(false, "")

        val disableCalls = mutableListOf<String>()
        val enableCalls = mutableListOf<String>()
        private val disabledSet = linkedSetOf<String>().apply { addAll(initialDisabled) }

        override fun startWirelessDiscovery() {}
        override fun stopWirelessDiscovery() {}
        override fun connect(host: String, port: Int, mode: AdbConnectionMode) {}
        override fun disconnect() {}
        override suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String) =
            Result.failure<String>(UnsupportedOperationException())
        override suspend fun inspectTvDevice() = Result.success(deviceInfo)
        override suspend fun listAllPackages() = Result.success(allPackages)
        override suspend fun listDisabledPackages() = Result.success(disabledSet.toSet())
        override suspend fun disablePackageForUser(packageName: String): Result<AdbCommandResult> {
            disableCalls.add(packageName)
            if (!ignoreDisableOnPackages.contains(packageName)) {
                disabledSet.add(packageName)
            }
            return Result.success(AdbCommandResult(0, "Package $packageName new state: disabled-user"))
        }

        override suspend fun enablePackage(packageName: String): Result<AdbCommandResult> {
            enableCalls.add(packageName)
            if (failEnableOnPackages.contains(packageName)) {
                return Result.failure(IOException("SecurityException: unable to enable $packageName"))
            }
            disabledSet.remove(packageName)
            return Result.success(AdbCommandResult(0, "Package $packageName new state: enabled"))
        }

        override suspend fun launchHomeChooserOrOpenHome() = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun setDefaultHomeActivity(componentName: String) = Result.success(AdbCommandResult(0, "Success"))
        override suspend fun restartCleanYourTvLauncher() = Result.success(AdbCommandResult(0, "Restarted"))
        override suspend fun installApk(apkBytes: ByteArray) = Result.success(AdbCommandResult(0, "Success"))
        override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun readRemoteFile(remotePath: String) = Result.failure<ByteArray>(IOException())
        override suspend fun remoteFileExists(remotePath: String) = Result.success(false)
        override suspend fun ensureRemoteDirectory(remoteDir: String) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun removeRemoteFile(remotePath: String) = Result.success(AdbCommandResult(0, "OK"))
        override suspend fun checkFilePushSupported() = Result.success(true)
    }
}
