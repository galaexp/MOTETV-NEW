package com.gala.motetv.tvhome

import android.content.Context
import com.gala.motetv.adb.AdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages CleanYourTV Home (`com.gathin.tvhome`) compatibility checks, APK installation,
 * default HOME switching, verification, and reversible stock HOME restoration.
 * Never disables the original stock launcher during installation or switching.
 */
class CleanYourTvLauncherManager(
    context: Context?,
    private val adbManager: AdbConnectionManager
) {

    companion object {
        private const val PREFS_NAME = "motetv_tvhome_recovery"
        private const val KEY_ORIG_HOME_PKG = "orig_home_pkg"
        private const val KEY_ORIG_HOME_ACT = "orig_home_act"
    }

    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var memoryOrigPkg: String? = null
    private var memoryOrigAct: String? = null

    private val _launcherStatus = MutableStateFlow(CleanYourTvLauncherStatus())
    val launcherStatus: StateFlow<CleanYourTvLauncherStatus> = _launcherStatus.asStateFlow()

    init {
        val (savedPkg, savedAct) = loadOriginalHome()
        if (!savedPkg.isNullOrBlank()) {
            _launcherStatus.value = _launcherStatus.value.copy(
                originalHomePackage = savedPkg,
                originalHomeActivity = savedAct
            )
        }
    }

    fun getPersistedOriginalHome(): Pair<String?, String?> = loadOriginalHome()

    /**
     * Seeds the persisted original HOME record from an external verified backup (e.g. DebloatBackupRecord)
     * if no original HOME record was yet stored in launcher preferences.
     */
    fun seedOriginalHomeIfMissing(pkg: String?, activity: String?) {
        val cleanPkg = pkg?.trim()?.ifBlank { null } ?: return
        if (cleanPkg == CleanYourTvConstants.LAUNCHER_PACKAGE) return
        val (existing, _) = loadOriginalHome()
        if (existing.isNullOrBlank()) {
            saveOriginalHome(cleanPkg, activity?.trim()?.ifBlank { null })
            _launcherStatus.value = _launcherStatus.value.copy(
                originalHomePackage = cleanPkg,
                originalHomeActivity = activity?.trim()?.ifBlank { null }
            )
        }
    }

    suspend fun refreshLauncherStatus(): Result<CleanYourTvLauncherStatus> {
        val devInfo = adbManager.inspectTvDevice().getOrElse { err ->
            val (savedOrigPkg, savedOrigAct) = loadOriginalHome()
            _launcherStatus.value = _launcherStatus.value.copy(
                originalHomePackage = savedOrigPkg,
                originalHomeActivity = savedOrigAct,
                statusDetail = "Unable to inspect TV HOME launcher: ${err.message}"
            )
            return Result.failure(err)
        }
        val allPackages = adbManager.listAllPackages().getOrElse { return Result.failure(it) }
        val disabled = adbManager.listDisabledPackages().getOrElse { emptySet() }
        val filePushOk = adbManager.checkFilePushSupported().getOrDefault(false)

        val currentPkg = devInfo.currentHomePackage
        val currentAct = devInfo.currentHomeActivity
        val cleanInstalled = allPackages.contains(CleanYourTvConstants.LAUNCHER_PACKAGE) &&
            !disabled.contains(CleanYourTvConstants.LAUNCHER_PACKAGE)
        val cleanActive = currentPkg == CleanYourTvConstants.LAUNCHER_PACKAGE

        // Preserve original non-CleanYourTV HOME for safe one-tap recovery
        if (!currentPkg.isNullOrBlank() && currentPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            saveOriginalHome(currentPkg, currentAct)
        }

        val (savedOrigPkg, savedOrigAct) = loadOriginalHome()
        val origAvailable = savedOrigPkg == null || (allPackages.contains(savedOrigPkg) && !disabled.contains(savedOrigPkg))
        val sdkCompatible = devInfo.sdkVersion >= CleanYourTvConstants.MIN_SUPPORTED_SDK || devInfo.sdkVersion == 0

        val status = CleanYourTvLauncherStatus(
            isSdkCompatible = sdkCompatible,
            tvSdkVersion = devInfo.sdkVersion,
            isSideloadingSupported = sdkCompatible,
            isCleanYourTvInstalled = cleanInstalled,
            isCleanYourTvActiveHome = cleanActive,
            currentHomePackage = currentPkg,
            currentHomeActivity = currentAct,
            originalHomePackage = savedOrigPkg,
            originalHomeActivity = savedOrigAct,
            isOriginalHomeAvailable = origAvailable,
            filePushSupported = filePushOk,
            statusDetail = buildStatusSummary(devInfo.sdkVersion, cleanInstalled, cleanActive, currentPkg, filePushOk)
        )
        _launcherStatus.value = status
        return Result.success(status)
    }

    suspend fun installCleanYourTvApk(apkBytes: ByteArray): Result<CleanYourTvLauncherStatus> {
        val current = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        if (!current.isSdkCompatible) {
            return Result.failure(UnsupportedOperationException("TV Android SDK ${current.tvSdkVersion} is below minimum supported SDK ${CleanYourTvConstants.MIN_SUPPORTED_SDK}."))
        }
        val installRes = adbManager.installApk(apkBytes)
        if (installRes.isFailure) {
            return Result.failure(installRes.exceptionOrNull()!!)
        }
        val refreshed = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        return if (refreshed.isCleanYourTvInstalled) {
            Result.success(refreshed)
        } else {
            Result.failure(IllegalStateException("APK install completed, but ${CleanYourTvConstants.LAUNCHER_PACKAGE} was not found in installed packages."))
        }
    }

    suspend fun activateCleanYourTvAsHome(): Result<CleanYourTvLauncherStatus> {
        val preflight = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        if (!preflight.isCleanYourTvInstalled) {
            return Result.failure(IllegalStateException("CleanYourTV Home (${CleanYourTvConstants.LAUNCHER_PACKAGE}) is not installed on the TV yet."))
        }

        // Never disable stock launcher; record original HOME before switching
        if (!preflight.currentHomePackage.isNullOrBlank() && preflight.currentHomePackage != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            saveOriginalHome(preflight.currentHomePackage, preflight.currentHomeActivity)
        }

        val setRes = adbManager.setDefaultHomeActivity(CleanYourTvConstants.LAUNCHER_COMPONENT)
        if (setRes.isFailure) {
            val err = setRes.exceptionOrNull()!!
            _launcherStatus.value = preflight.copy(
                statusDetail = "Firmware blocked programmatic HOME switch (${err.message}). Original HOME kept safely active."
            )
            return Result.failure(err)
        }

        val launchRes = adbManager.launchHomeChooserOrOpenHome()
        val verified = refreshLauncherStatus().getOrElse { return Result.failure(it) }

        return if (verified.isCleanYourTvActiveHome && launchRes.isSuccess) {
            Result.success(verified)
        } else {
            val msg = "Default HOME still resolves to '${verified.currentHomePackage}'. Some OEM firmware requires setting Home manually or confirming chooser on TV."
            _launcherStatus.value = verified.copy(statusDetail = msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    suspend fun restoreOriginalHomeLauncher(): Result<CleanYourTvLauncherStatus> {
        // Refresh current TV launcher state first when needed, while relying on persistent storage for the saved original HOME
        val (persistedPkg, persistedAct) = loadOriginalHome()
        val origPkg: String?
        val origAct: String?
        if (!persistedPkg.isNullOrBlank()) {
            origPkg = persistedPkg
            origAct = persistedAct
        } else {
            // Attempt a refresh in case TV already has a non-CleanYourTV HOME or saved status needs syncing
            refreshLauncherStatus()
            val (reloadedPkg, reloadedAct) = loadOriginalHome()
            origPkg = reloadedPkg
            origAct = reloadedAct
        }

        if (origPkg.isNullOrBlank() || origPkg == CleanYourTvConstants.LAUNCHER_PACKAGE) {
            return Result.failure(IllegalStateException("No valid original HOME launcher record exists in persistent storage."))
        }

        // Verify original launcher is enabled BEFORE attempting to set it as default HOME
        val enableRes = adbManager.enablePackage(origPkg)
        if (enableRes.isFailure) {
            val err = enableRes.exceptionOrNull()!!
            val msg = "Failed to enable original launcher '$origPkg' before setting HOME: ${err.message}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        val enableCmd = enableRes.getOrThrow()
        if (!enableCmd.isSuccess) {
            val detail = enableCmd.stderr.ifBlank { enableCmd.stdout }.ifBlank { "exit code ${enableCmd.exitCode}" }
            val msg = "Failed to enable original launcher '$origPkg' before setting HOME: $detail"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg))
        }

        val installedAfterEnable = adbManager.listAllPackages().getOrElse { err ->
            val msg = "Unable to verify installed state of original launcher '$origPkg': ${err.message}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        if (!installedAfterEnable.contains(origPkg)) {
            val msg = "Original launcher '$origPkg' is not installed on TV; aborted HOME switch."
            _launcherStatus.value = _launcherStatus.value.copy(
                isOriginalHomeAvailable = false,
                statusDetail = msg
            )
            return Result.failure(IllegalStateException(msg))
        }

        val disabledAfterEnable = adbManager.listDisabledPackages().getOrElse { err ->
            val msg = "Unable to verify enabled state of original launcher '$origPkg': ${err.message}"
            _launcherStatus.value = _launcherStatus.value.copy(statusDetail = msg)
            return Result.failure(IllegalStateException(msg, err))
        }
        if (disabledAfterEnable.contains(origPkg)) {
            val msg = "Original launcher '$origPkg' remains disabled on TV; aborted HOME switch."
            _launcherStatus.value = _launcherStatus.value.copy(
                isOriginalHomeAvailable = false,
                statusDetail = msg
            )
            return Result.failure(IllegalStateException(msg))
        }

        val targetComponent = origAct?.takeIf { it.contains('/') } ?: "$origPkg/.MainActivity"
        val setRes = adbManager.setDefaultHomeActivity(targetComponent)
        if (setRes.isFailure) {
            val err = setRes.exceptionOrNull()!!
            _launcherStatus.value = _launcherStatus.value.copy(
                statusDetail = "Failed to restore default HOME to '$origPkg': ${err.message}"
            )
            return Result.failure(err)
        }
        adbManager.launchHomeChooserOrOpenHome()

        val verified = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        return if (verified.currentHomePackage == origPkg) {
            Result.success(verified)
        } else {
            val msg = "Restored command ran, but active HOME still reports '${verified.currentHomePackage}' instead of '$origPkg'."
            _launcherStatus.value = verified.copy(statusDetail = msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    private fun saveOriginalHome(pkg: String, activity: String?) {
        if (pkg.isBlank() || pkg == CleanYourTvConstants.LAUNCHER_PACKAGE) return
        val p = prefs
        if (p != null) {
            val committed = p.edit()
                .putString(KEY_ORIG_HOME_PKG, pkg)
                .putString(KEY_ORIG_HOME_ACT, activity ?: "")
                .commit()
            if (committed) {
                memoryOrigPkg = pkg
                memoryOrigAct = activity
            }
        } else {
            memoryOrigPkg = pkg
            memoryOrigAct = activity
        }
    }

    private fun loadOriginalHome(): Pair<String?, String?> {
        val p = prefs
        if (p != null) {
            val pkg = p.getString(KEY_ORIG_HOME_PKG, null)?.ifBlank { null }
            val act = p.getString(KEY_ORIG_HOME_ACT, null)?.ifBlank { null }
            return pkg to act
        }
        return memoryOrigPkg to memoryOrigAct
    }

    private fun buildStatusSummary(
        sdk: Int,
        installed: Boolean,
        active: Boolean,
        currentHome: String?,
        filePushOk: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts.add("TV SDK $sdk")
        parts.add(if (installed) "CleanYourTV installed" else "CleanYourTV not installed")
        parts.add("Active HOME: ${currentHome ?: "Unknown"}")
        if (active) parts.add("Verified Active")
        if (!filePushOk) parts.add("Scoped storage restriction may limit config push")
        return parts.joinToString(" • ")
    }
}
