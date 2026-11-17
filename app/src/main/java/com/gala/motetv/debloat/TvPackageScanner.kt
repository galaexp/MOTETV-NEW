package com.gala.motetv.debloat

import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.TvDeviceInfo

class TvPackageScanner(
    private val adbManager: AdbConnectionManager,
    private val repository: DebloatRepository,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun scanTvPackages(): Result<TvScanResult> {
        val devInfo = adbManager.inspectTvDevice().getOrElse { return Result.failure(it) }
        val allPackages = adbManager.listAllPackages().getOrElse { return Result.failure(it) }
        val disabledSet = adbManager.listDisabledPackages().getOrElse { return Result.failure(it) }
        val modifiedSet = repository.getModifiedPackages()

        val result = buildScanResult(
            deviceInfo = devInfo,
            installedPackages = allPackages,
            disabledPackages = disabledSet,
            modifiedByMoteTv = modifiedSet,
            timestampMs = timeProvider()
        )
        return Result.success(result)
    }

    fun buildScanResult(
        deviceInfo: TvDeviceInfo,
        installedPackages: List<String>,
        disabledPackages: Set<String>,
        modifiedByMoteTv: Set<String> = emptySet(),
        timestampMs: Long = timeProvider()
    ): TvScanResult {
        val exactXiaomiMatch = deviceInfo.isXiaomiDevice && PackageSafetyGuard.matchesVerifiedXiaomiStickModel(deviceInfo)
        val isUnverifiedXiaomiVariant = deviceInfo.isXiaomiDevice && !exactXiaomiMatch

        val notice = when {
            deviceInfo.isXiaomiMiTvStickOriginal || exactXiaomiMatch ->
                "Verified Xiaomi Mi TV Stick (${deviceInfo.model} / ${deviceInfo.deviceName}, SDK ${deviceInfo.sdkVersion}). Issue #884 boot-critical packages are strictly locked."
            isUnverifiedXiaomiVariant ->
                "Unverified Xiaomi variant (${deviceInfo.model} / ${deviceInfo.deviceName}). Generic Xiaomi lists are NOT auto-approved — inspect and select each package individually."
            else ->
                "Detected ${deviceInfo.manufacturer} ${deviceInfo.model} (Android ${deviceInfo.androidVersion}, SDK ${deviceInfo.sdkVersion}). Only verified catalog items are marked Recommended; unknown packages stay untouched."
        }

        val installedSet = installedPackages.toSet()
        val scanned = installedPackages.distinct().map { pkg ->
            classifyPackage(
                packageName = pkg,
                isEnabled = !disabledPackages.contains(pkg),
                deviceInfo = deviceInfo,
                installedSet = installedSet,
                isUnverifiedXiaomiVariant = isUnverifiedXiaomiVariant,
                modified = modifiedByMoteTv.contains(pkg)
            )
        }.sortedWith(
            compareBy<ScannedPackage> { it.category.sortOrder }
                .thenBy { it.appName.lowercase() }
        )

        return TvScanResult(
            scannedAtMs = timestampMs,
            deviceInfo = deviceInfo,
            packages = scanned,
            hasExactModelCatalogMatch = !isUnverifiedXiaomiVariant,
            modelSafetyNotice = notice
        )
    }

    private fun classifyPackage(
        packageName: String,
        isEnabled: Boolean,
        deviceInfo: TvDeviceInfo,
        installedSet: Set<String>,
        isUnverifiedXiaomiVariant: Boolean,
        modified: Boolean
    ): ScannedPackage {
        val (alwaysBlocked, blockMsg) = PackageSafetyGuard.isAlwaysBlockedPackage(packageName)
        val isCurrentHome = packageName == deviceInfo.currentHomePackage

        if (alwaysBlocked) {
            return ScannedPackage(
                packageName = packageName,
                appName = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[packageName]?.appName ?: deriveFriendlyName(packageName),
                category = PackageCategory.SYSTEM_ESSENTIAL,
                isEnabled = isEnabled,
                recommendation = DebloatRecommendation.NEVER_DISABLE,
                reason = blockMsg ?: "Essential Android TV system package.",
                riskLevel = RiskLevel.CRITICAL_BLOCKED,
                requiresOptionalPrompt = false,
                isBlockedFromDisable = true,
                blockReason = blockMsg,
                isOriginalHomeLauncher = isCurrentHome,
                modifiedByMoteTv = modified
            )
        }

        val known = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[packageName]
        if (known != null) {
            // Protect active HOME launcher unless CleanYourTV Home is also installed and verified active
            val activeHomeBlocked = isCurrentHome
            val effectiveRec = when {
                activeHomeBlocked -> DebloatRecommendation.NEVER_DISABLE
                isUnverifiedXiaomiVariant && known.recommendation == DebloatRecommendation.SAFE_TO_DISABLE ->
                    DebloatRecommendation.OPTIONAL_ASK_USER
                else -> known.recommendation
            }
            val effectiveReason = when {
                activeHomeBlocked -> "${known.reason} Currently active HOME launcher — install and verify replacement Home first."
                isUnverifiedXiaomiVariant && known.recommendation == DebloatRecommendation.SAFE_TO_DISABLE ->
                    "${known.reason} (Approve individually for ${deviceInfo.model}.)"
                else -> known.reason
            }

            return ScannedPackage(
                packageName = packageName,
                appName = known.appName,
                category = known.category,
                isEnabled = isEnabled,
                recommendation = effectiveRec,
                reason = effectiveReason,
                riskLevel = if (activeHomeBlocked) RiskLevel.CRITICAL_BLOCKED else known.riskLevel,
                requiresOptionalPrompt = known.requiresOptionalPrompt || isUnverifiedXiaomiVariant,
                isBlockedFromDisable = activeHomeBlocked,
                blockReason = if (activeHomeBlocked) "Active HOME launcher cannot be disabled here." else null,
                isOriginalHomeLauncher = isCurrentHome,
                modifiedByMoteTv = modified
            )
        }

        // Heuristic classification for non-catalog Google/Android/Streaming/Unknown packages
        val lower = packageName.lowercase()
        val inferredCategory = when {
            lower.startsWith("com.android.") || lower == "android" -> PackageCategory.SYSTEM_ESSENTIAL
            lower.startsWith("com.google.android.") -> PackageCategory.GOOGLE_SERVICES
            lower.contains("netflix") || lower.contains("youtube") || lower.contains("hulu") ||
                lower.contains("disney") || lower.contains("spotify") || lower.contains("plex") -> PackageCategory.STREAMING
            else -> PackageCategory.UNKNOWN_UNVERIFIED
        }

        val isProtectedSystemGuess = inferredCategory == PackageCategory.SYSTEM_ESSENTIAL || isCurrentHome
        val rec = if (isProtectedSystemGuess) DebloatRecommendation.NEVER_DISABLE else DebloatRecommendation.OPTIONAL_ASK_USER
        val risk = when (inferredCategory) {
            PackageCategory.SYSTEM_ESSENTIAL -> RiskLevel.CRITICAL_BLOCKED
            PackageCategory.GOOGLE_SERVICES -> RiskLevel.HIGH
            PackageCategory.STREAMING -> RiskLevel.MODERATE
            else -> RiskLevel.MODERATE
        }
        val reason = when {
            isCurrentHome -> "Currently active HOME launcher — keep enabled for recovery."
            inferredCategory == PackageCategory.SYSTEM_ESSENTIAL -> "Android core platform package — protected against blind disabling."
            inferredCategory == PackageCategory.GOOGLE_SERVICES -> "Unverified Google component — inspect before changing."
            inferredCategory == PackageCategory.STREAMING -> "Streaming application — optional."
            else -> "Unverified package not in CleanYourTV catalog. Never auto-disabled."
        }

        return ScannedPackage(
            packageName = packageName,
            appName = deriveFriendlyName(packageName),
            category = inferredCategory,
            isEnabled = isEnabled,
            recommendation = rec,
            reason = reason,
            riskLevel = risk,
            requiresOptionalPrompt = inferredCategory != PackageCategory.SYSTEM_ESSENTIAL,
            isBlockedFromDisable = isProtectedSystemGuess,
            blockReason = if (isProtectedSystemGuess) reason else null,
            isOriginalHomeLauncher = isCurrentHome,
            modifiedByMoteTv = modified
        )
    }

    private fun deriveFriendlyName(packageName: String): String {
        val last = packageName.substringAfterLast('.').ifBlank { packageName }
        return last.replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
