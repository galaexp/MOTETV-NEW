package com.gala.motetv.debloat

import com.gala.motetv.tvhome.CleanYourTvConstants
import com.gala.motetv.tvhome.CleanYourTvLauncherManager

data class TvRecoveryReport(
    val trackedPackagesCount: Int,
    val restoredPackagesCount: Int,
    val failedPackages: List<String>,
    val packageRestoreError: String?,
    val homeRestoreAttempted: Boolean,
    val originalHomePackage: String?,
    val homeRestored: Boolean,
    val verifiedActiveHomePackage: String?,
    val homeRestoreError: String?
) {
    val isCompleteSuccess: Boolean
        get() = failedPackages.isEmpty() &&
            packageRestoreError == null &&
            (!homeRestoreAttempted || homeRestored) &&
            homeRestoreError == null

    fun formatSummary(): String {
        val pkgSummary = when {
            trackedPackagesCount == 0 && failedPackages.isEmpty() && packageRestoreError == null ->
                "Packages: none tracked for restoration"
            failedPackages.isEmpty() && packageRestoreError == null ->
                "Packages: restored $restoredPackagesCount/$trackedPackagesCount"
            else ->
                "Packages: restored $restoredPackagesCount/$trackedPackagesCount; failed=${failedPackages.joinToString().ifBlank { packageRestoreError ?: "error" }}"
        }
        val homeSummary = when {
            homeRestored ->
                "HOME: verified '$verifiedActiveHomePackage' active"
            homeRestoreAttempted && homeRestoreError != null ->
                "HOME restore FAILED (${originalHomePackage ?: "unknown"}): $homeRestoreError"
            homeRestoreAttempted ->
                "HOME restore incomplete (active='${verifiedActiveHomePackage ?: "unknown"}')"
            originalHomePackage == null && verifiedActiveHomePackage == CleanYourTvConstants.LAUNCHER_PACKAGE ->
                "HOME: no saved original HOME record exists while CleanYourTV is active"
            else ->
                "HOME: no saved HOME restoration needed"
        }
        return if (isCompleteSuccess) {
            "Recovery complete — $pkgSummary. $homeSummary."
        } else {
            "Incomplete TV recovery — $pkgSummary. $homeSummary."
        }
    }
}

class TvRecoveryException(
    val report: TvRecoveryReport,
    message: String = report.formatSummary(),
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Coordinates durable TV recovery across app restarts by reading persisted package-modification
 * records and persisted original HOME launcher metadata, preserving partial recovery progress,
 * enabling and verifying original HOME restoration, and never masking partial or HOME failures.
 */
class TvRecoveryCoordinator(
    private val debloatManager: DebloatManager,
    private val launcherManager: CleanYourTvLauncherManager
) {

    fun resolvePersistedOriginalHomePackage(): String? {
        val (launcherPkg, _) = launcherManager.getPersistedOriginalHome()
        if (!launcherPkg.isNullOrBlank() && launcherPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            return launcherPkg
        }
        val backupPkg = debloatManager.latestBackup.value?.originalHomePackage
        if (!backupPkg.isNullOrBlank() && backupPkg != CleanYourTvConstants.LAUNCHER_PACKAGE) {
            return backupPkg
        }
        return null
    }

    suspend fun recoverTv(): Result<TvRecoveryReport> {
        val trackedBefore = debloatManager.modifiedPackages.value
        val trackedCount = trackedBefore.size

        // 1. Restore all MoteTV-modified packages (DebloatManager attempts all & persists partial progress)
        val pkgRes = debloatManager.restoreAllModifiedByMoteTv()
        val remainingAfter = debloatManager.modifiedPackages.value
        val failedPkgs = remainingAfter.intersect(trackedBefore).sorted()
        val restoredCount = (trackedCount - failedPkgs.size).coerceAtLeast(0)
        val pkgError = pkgRes.exceptionOrNull()?.message

        // 2. Retrieve persisted original HOME record from CleanYourTvLauncherManager prefs or verified Debloat backup
        val (savedLauncherPkg, _) = launcherManager.getPersistedOriginalHome()
        val backup = debloatManager.latestBackup.value
        val validPersistedPkg = when {
            !savedLauncherPkg.isNullOrBlank() && savedLauncherPkg != CleanYourTvConstants.LAUNCHER_PACKAGE -> savedLauncherPkg
            !backup?.originalHomePackage.isNullOrBlank() && backup?.originalHomePackage != CleanYourTvConstants.LAUNCHER_PACKAGE -> {
                launcherManager.seedOriginalHomeIfMissing(backup?.originalHomePackage, backup?.originalHomeActivity)
                backup?.originalHomePackage
            }
            else -> null
        }

        // Refresh launcher status from TV before deciding HOME recovery steps
        val refreshedStatus = launcherManager.refreshLauncherStatus().getOrNull()
        val activeHomeBeforeRestore = refreshedStatus?.currentHomePackage
            ?: launcherManager.launcherStatus.value.currentHomePackage

        // Restore original HOME ONLY when a valid persisted original launcher record existed
        var homeAttempted = false
        var homeRestored = false
        var verifiedHomePkg = activeHomeBeforeRestore
        var homeError: String? = null

        if (!validPersistedPkg.isNullOrBlank()) {
            homeAttempted = true
            val homeRes = launcherManager.restoreOriginalHomeLauncher()
            homeRes.onSuccess { verifiedStatus ->
                homeRestored = verifiedStatus.currentHomePackage == validPersistedPkg
                verifiedHomePkg = verifiedStatus.currentHomePackage
                if (!homeRestored) {
                    homeError = "Expected active HOME '$validPersistedPkg', got '${verifiedStatus.currentHomePackage}'"
                }
            }.onFailure { err ->
                homeRestored = false
                verifiedHomePkg = launcherManager.launcherStatus.value.currentHomePackage ?: verifiedHomePkg
                homeError = err.message ?: "HOME restoration failed"
            }
        } else if (activeHomeBeforeRestore == CleanYourTvConstants.LAUNCHER_PACKAGE) {
            homeError = "No saved original HOME record exists in persistent storage."
        }

        val report = TvRecoveryReport(
            trackedPackagesCount = trackedCount,
            restoredPackagesCount = restoredCount,
            failedPackages = failedPkgs,
            packageRestoreError = pkgError,
            homeRestoreAttempted = homeAttempted,
            originalHomePackage = validPersistedPkg,
            homeRestored = homeRestored,
            verifiedActiveHomePackage = verifiedHomePkg,
            homeRestoreError = homeError
        )

        return if (report.isCompleteSuccess) {
            Result.success(report)
        } else {
            val cause = pkgRes.exceptionOrNull()
            Result.failure(TvRecoveryException(report, report.formatSummary(), cause))
        }
    }
}
