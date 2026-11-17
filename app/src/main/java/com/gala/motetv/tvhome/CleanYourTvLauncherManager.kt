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

    suspend fun refreshLauncherStatus(): Result<CleanYourTvLauncherStatus> {
        val devInfo = adbManager.inspectTvDevice().getOrElse { err ->
            _launcherStatus.value = _launcherStatus.value.copy(
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
        if (currentPkg != null && currentPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
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
        if (preflight.currentHomePackage != null && preflight.currentHomePackage != CleanYourTvConstants.LAUNCHER_PACKAGE) {
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
        val (origPkg, origAct) = loadOriginalHome()
        if (origPkg.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No original HOME launcher record was captured yet."))
        }

        // Ensure original launcher is enabled first (in case user disabled it elsewhere)
        adbManager.enablePackage(origPkg)

        val targetComponent = origAct?.takeIf { it.contains('/') } ?: "$origPkg/.MainActivity"
        val setRes = adbManager.setDefaultHomeActivity(targetComponent)
        if (setRes.isFailure) {
            return Result.failure(setRes.exceptionOrNull()!!)
        }
        adbManager.launchHomeChooserOrOpenHome()

        val verified = refreshLauncherStatus().getOrElse { return Result.failure(it) }
        return if (verified.currentHomePackage == origPkg) {
            Result.success(verified)
        } else {
            Result.failure(IllegalStateException("Restored command ran, but active HOME still reports '${verified.currentHomePackage}' instead of '$origPkg'."))
        }
    }

    private fun saveOriginalHome(pkg: String, activity: String?) {
        memoryOrigPkg = pkg
        memoryOrigAct = activity
        prefs?.edit()
            ?.putString(KEY_ORIG_HOME_PKG, pkg)
            ?.putString(KEY_ORIG_HOME_ACT, activity ?: "")
            ?.apply()
    }

    private fun loadOriginalHome(): Pair<String?, String?> {
        val p = prefs
        val pkg = p?.getString(KEY_ORIG_HOME_PKG, null)?.ifBlank { null } ?: memoryOrigPkg
        val act = p?.getString(KEY_ORIG_HOME_ACT, null)?.ifBlank { null } ?: memoryOrigAct
        return pkg to act
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
