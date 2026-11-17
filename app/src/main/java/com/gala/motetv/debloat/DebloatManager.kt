package com.gala.motetv.debloat

import com.gala.motetv.adb.AdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class DebloatManager(
    private val adbManager: AdbConnectionManager,
    private val scanner: TvPackageScanner,
    private val repository: DebloatRepository,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val BATCH_SIZE = 3
    }

    private val _scanResult = MutableStateFlow<TvScanResult?>(null)
    val scanResult: StateFlow<TvScanResult?> = _scanResult.asStateFlow()

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    private val _latestBackup = MutableStateFlow<DebloatBackupRecord?>(repository.getLatestBackup())
    val latestBackup: StateFlow<DebloatBackupRecord?> = _latestBackup.asStateFlow()

    private val _operationHistory = MutableStateFlow<List<DebloatOperationEntry>>(repository.getOperationHistory())
    val operationHistory: StateFlow<List<DebloatOperationEntry>> = _operationHistory.asStateFlow()

    private val _batchProgress = MutableStateFlow(BatchProgress(0, 0, null, false))
    val batchProgress: StateFlow<BatchProgress> = _batchProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    suspend fun scanTv(): Result<TvScanResult> {
        _statusMessage.value = "Scanning TV packages (read-only)..."
        val res = scanner.scanTvPackages()
        res.onSuccess { scanned ->
            _scanResult.value = scanned
            _selectedPackages.value = _selectedPackages.value.filter { pkgName ->
                scanned.packages.any { it.packageName == pkgName && !it.isBlockedFromDisable }
            }.toSet()
            _statusMessage.value = "Scanned ${scanned.packages.size} packages on ${scanned.deviceInfo.model}"
        }.onFailure {
            _statusMessage.value = "Scan failed: ${it.message}"
        }
        return res
    }

    fun togglePackageSelection(packageName: String) {
        val currentScan = _scanResult.value ?: return
        val pkg = currentScan.packages.firstOrNull { it.packageName == packageName } ?: return
        if (pkg.isBlockedFromDisable) {
            _statusMessage.value = pkg.blockReason ?: "Package $packageName is protected."
            return
        }
        val next = _selectedPackages.value.toMutableSet()
        if (next.contains(packageName)) {
            next.remove(packageName)
        } else {
            next.add(packageName)
        }
        _selectedPackages.value = next
    }

    fun selectRecommendedSafePackages() {
        val scan = _scanResult.value ?: return
        // Only auto-select SAFE_TO_DISABLE items when exact model match exists; NEVER auto-select unknown or optional items.
        if (!scan.hasExactModelCatalogMatch) {
            _statusMessage.value = "Exact verified model match unavailable for ${scan.deviceInfo.model}. Select packages individually."
            return
        }
        val safeEnabled = scan.packages.filter {
            !it.isBlockedFromDisable &&
                it.isEnabled &&
                it.recommendation == DebloatRecommendation.SAFE_TO_DISABLE &&
                !it.requiresOptionalPrompt &&
                it.category != PackageCategory.UNKNOWN_UNVERIFIED
        }.map { it.packageName }.toSet()
        _selectedPackages.value = safeEnabled
        _statusMessage.value = "Selected ${safeEnabled.size} verified low-risk packages for review."
    }

    fun clearSelection() {
        _selectedPackages.value = emptySet()
    }

    suspend fun createBackup(intendedTargets: List<String> = _selectedPackages.value.sorted()): Result<DebloatBackupRecord> {
        val devInfo = _scanResult.value?.deviceInfo
            ?: adbManager.inspectTvDevice().getOrElse { return Result.failure(it) }
        val allPkgs = adbManager.listAllPackages().getOrElse { return Result.failure(it) }
        val disabledPkgs = adbManager.listDisabledPackages().getOrElse { return Result.failure(it) }

        val record = DebloatBackupRecord(
            backupId = UUID.randomUUID().toString(),
            createdAtMs = timeProvider(),
            deviceInfo = devInfo,
            allInstalledPackages = allPkgs,
            originallyDisabledPackages = disabledPkgs.sorted(),
            originalHomePackage = devInfo.currentHomePackage,
            originalHomeActivity = devInfo.currentHomeActivity,
            intendedDisablePackages = intendedTargets,
            modifiedByMoteTvPackages = repository.getModifiedPackages().sorted()
        )
        val saved = repository.saveBackup(record)
        saved.onSuccess {
            _latestBackup.value = it
            recordOperation(
                action = DebloatActionType.BACKUP_CREATED,
                packageName = null,
                succeeded = true,
                verifiedState = "${allPkgs.size} pkgs (${disabledPkgs.size} disabled)",
                detail = "Backup created before changes (Original HOME: ${it.originalHomePackage ?: "unknown"})"
            )
            _statusMessage.value = "Backup saved (${allPkgs.size} packages, HOME=${it.originalHomePackage ?: "unknown"})."
        }.onFailure {
            recordOperation(
                action = DebloatActionType.BACKUP_CREATED,
                packageName = null,
                succeeded = false,
                verifiedState = "Backup failed",
                detail = it.message ?: "Could not persist backup"
            )
            _statusMessage.value = "Backup failed — debloat blocked: ${it.message}"
        }
        return saved
    }

    /**
     * Disables [packages] in small batches (BATCH_SIZE = 3) using `pm disable-user --user 0`,
     * verifying each batch via `pm list packages -d`. Refuses to proceed if backup fails or any
     * package violates PackageSafetyGuard.
     */
    suspend fun disablePackagesWithBackup(
        packages: List<String>,
        verifiedReplacementHomeInstalled: Boolean = false,
        verifiedReplacementHomeActive: Boolean = false
    ): Result<List<DebloatOperationEntry>> {
        if (packages.isEmpty()) {
            return Result.failure(IllegalArgumentException("No packages selected to disable."))
        }

        val scan = _scanResult.value ?: scanTv().getOrElse { return Result.failure(it) }
        val installedSet = scan.packages.map { it.packageName }.toSet()

        // 1. Validate every candidate against safety guard
        for (pkg in packages) {
            val decision = PackageSafetyGuard.evaluateDisableCandidate(
                packageName = pkg,
                deviceInfo = scan.deviceInfo,
                installedPackages = installedSet,
                verifiedReplacementHomeInstalled = verifiedReplacementHomeInstalled,
                verifiedReplacementHomeActive = verifiedReplacementHomeActive
            )
            if (!decision.allowed) {
                return Result.failure(SecurityException(decision.reason ?: "Blocked by safety guard: $pkg"))
            }
        }

        // 2. Mandatory backup before any modification
        createBackup(intendedTargets = packages).getOrElse { err ->
            return Result.failure(IllegalStateException("Mandatory backup failed; aborting debloat: ${err.message}", err))
        }

        val batchResults = mutableListOf<DebloatOperationEntry>()
        _batchProgress.value = BatchProgress(packages.size, 0, packages.firstOrNull(), true)

        var processed = 0
        try {
            val batches = packages.chunked(BATCH_SIZE)
            for (batch in batches) {
                for (pkg in batch) {
                    _batchProgress.value = BatchProgress(packages.size, processed, pkg, true)
                    val cmdRes = adbManager.disablePackageForUser(pkg)
                    if (cmdRes.isFailure) {
                        val err = cmdRes.exceptionOrNull()!!
                        val failEntry = recordOperation(
                            action = DebloatActionType.DISABLE_PACKAGE,
                            packageName = pkg,
                            succeeded = false,
                            verifiedState = "COMMAND_FAILED",
                            detail = "Stopped batch immediately: ${err.message}"
                        )
                        batchResults.add(failEntry)
                        _statusMessage.value = "Stopped at $pkg: ${err.message}"
                        return Result.failure(err)
                    }
                    processed++
                }

                // Verify actual disabled state after each small batch
                val verifiedDisabled = adbManager.listDisabledPackages().getOrElse { err ->
                    _statusMessage.value = "Batch verification failed: ${err.message}"
                    return Result.failure(err)
                }

                for (pkg in batch) {
                    val actuallyDisabled = verifiedDisabled.contains(pkg)
                    if (actuallyDisabled) {
                        repository.markPackageModified(pkg)
                        val entry = recordOperation(
                            action = DebloatActionType.DISABLE_PACKAGE,
                            packageName = pkg,
                            succeeded = true,
                            verifiedState = "DISABLED",
                            detail = "Disabled via pm disable-user --user 0 and verified in pm list packages -d"
                        )
                        batchResults.add(entry)
                    } else {
                        val entry = recordOperation(
                            action = DebloatActionType.DISABLE_PACKAGE,
                            packageName = pkg,
                            succeeded = false,
                            verifiedState = "STILL_ENABLED",
                            detail = "Stopped: package was not listed in pm list packages -d after batch"
                        )
                        batchResults.add(entry)
                        _statusMessage.value = "Verification mismatch on $pkg; stopping further batches."
                        return Result.failure(IllegalStateException("Verification failed: $pkg remained enabled"))
                    }
                }
            }

            _selectedPackages.value = emptySet()
            scanTv()
            _statusMessage.value = "Verified disabled ${batchResults.size} package(s). No auto-reboot performed."
            return Result.success(batchResults)
        } finally {
            _batchProgress.value = BatchProgress(packages.size, processed, null, false)
        }
    }

    suspend fun restorePackages(packages: List<String>): Result<List<DebloatOperationEntry>> {
        if (packages.isEmpty()) {
            return Result.failure(IllegalArgumentException("No packages selected to restore."))
        }
        val results = mutableListOf<DebloatOperationEntry>()
        _batchProgress.value = BatchProgress(packages.size, 0, packages.firstOrNull(), true)
        var processed = 0
        try {
            for (pkg in packages) {
                _batchProgress.value = BatchProgress(packages.size, processed, pkg, true)
                val res = adbManager.enablePackage(pkg)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()!!
                    results.add(
                        recordOperation(
                            action = DebloatActionType.RESTORE_PACKAGE,
                            packageName = pkg,
                            succeeded = false,
                            verifiedState = "ENABLE_FAILED",
                            detail = err.message ?: "pm enable failed"
                        )
                    )
                    return Result.failure(err)
                }
                processed++
            }

            val stillDisabled = adbManager.listDisabledPackages().getOrElse { return Result.failure(it) }
            for (pkg in packages) {
                val restored = !stillDisabled.contains(pkg)
                if (restored) {
                    repository.unmarkPackageModified(pkg)
                }
                results.add(
                    recordOperation(
                        action = DebloatActionType.RESTORE_PACKAGE,
                        packageName = pkg,
                        succeeded = restored,
                        verifiedState = if (restored) "ENABLED" else "STILL_DISABLED",
                        detail = if (restored) "Restored via pm enable and verified" else "Package still disabled"
                    )
                )
            }
            scanTv()
            _statusMessage.value = "Restored ${results.count { it.succeeded }}/${packages.size} package(s)."
            return Result.success(results)
        } finally {
            _batchProgress.value = BatchProgress(packages.size, processed, null, false)
        }
    }

    suspend fun restoreAllModifiedByMoteTv(): Result<List<DebloatOperationEntry>> {
        val modified = repository.getModifiedPackages().sorted()
        if (modified.isEmpty()) {
            _statusMessage.value = "No MoteTV-modified packages require restoration."
            return Result.success(emptyList())
        }
        val res = restorePackages(modified)
        res.onSuccess {
            recordOperation(
                action = DebloatActionType.RESTORE_ALL,
                packageName = null,
                succeeded = true,
                verifiedState = "${it.size} restored",
                detail = "Restored all packages modified by MoteTV"
            )
        }
        return res
    }

    fun exportBackupJson(): String? = repository.exportLatestBackupJson()

    fun markBackupExported(targetLabel: String) {
        recordOperation(
            action = DebloatActionType.BACKUP_EXPORTED,
            packageName = null,
            succeeded = true,
            verifiedState = "EXPORTED",
            detail = "Backup exported via Android SAF ($targetLabel)"
        )
    }

    private fun recordOperation(
        action: DebloatActionType,
        packageName: String?,
        succeeded: Boolean,
        verifiedState: String,
        detail: String
    ): DebloatOperationEntry {
        val entry = DebloatOperationEntry(
            id = UUID.randomUUID().toString(),
            timestampMs = timeProvider(),
            action = action,
            packageName = packageName,
            succeeded = succeeded,
            verifiedState = verifiedState,
            detail = detail
        )
        repository.appendHistory(entry)
        _operationHistory.value = repository.getOperationHistory()
        return entry
    }
}
