package com.gala.motetv.debloat

import com.gala.motetv.adb.TvDeviceInfo

enum class PackageCategory(val title: String, val sortOrder: Int) {
    MANUFACTURER_TELEMETRY("Manufacturer Telemetry & Analytics", 1),
    MANUFACTURER_ADS("Manufacturer Ads & Recommendations", 2),
    MANUFACTURER_CONTENT("Manufacturer Content Providers", 3),
    OPTIONAL_PREINSTALLED("Optional Preinstalled Apps", 4),
    STREAMING("Streaming Apps", 5),
    GOOGLE_SERVICES("Google Services", 6),
    SYSTEM_ESSENTIAL("Android System & Essential TV", 7),
    UNKNOWN_UNVERIFIED("Unknown / Unverified Packages", 8)
}

enum class DebloatRecommendation(val label: String) {
    SAFE_TO_DISABLE("Recommended Disable"),
    OPTIONAL_ASK_USER("Optional (Ask User)"),
    KEEP("Keep"),
    NEVER_DISABLE("Protected — Never Disable")
}

enum class RiskLevel(val label: String) {
    LOW("Low Risk"),
    MODERATE("Moderate Risk"),
    HIGH("High Risk"),
    CRITICAL_BLOCKED("Critical System — Blocked")
}

data class KnownPackageDescriptor(
    val packageName: String,
    val appName: String,
    val category: PackageCategory,
    val recommendation: DebloatRecommendation,
    val reason: String,
    val riskLevel: RiskLevel,
    val requiresOptionalPrompt: Boolean = false,
    val verifiedXiaomiStickModels: Set<String> = emptySet()
)

data class ScannedPackage(
    val packageName: String,
    val appName: String,
    val category: PackageCategory,
    val isEnabled: Boolean,
    val recommendation: DebloatRecommendation,
    val reason: String,
    val riskLevel: RiskLevel,
    val requiresOptionalPrompt: Boolean = false,
    val isBlockedFromDisable: Boolean = false,
    val blockReason: String? = null,
    val isOriginalHomeLauncher: Boolean = false,
    val modifiedByMoteTv: Boolean = false
)

data class TvScanResult(
    val scannedAtMs: Long,
    val deviceInfo: TvDeviceInfo,
    val packages: List<ScannedPackage>,
    val hasExactModelCatalogMatch: Boolean,
    val modelSafetyNotice: String
)

data class DebloatBackupRecord(
    val backupId: String,
    val createdAtMs: Long,
    val deviceInfo: TvDeviceInfo,
    val allInstalledPackages: List<String>,
    val originallyDisabledPackages: List<String>,
    val originalHomePackage: String?,
    val originalHomeActivity: String?,
    val intendedDisablePackages: List<String> = emptyList(),
    val modifiedByMoteTvPackages: List<String> = emptyList()
)

enum class DebloatActionType {
    BACKUP_CREATED,
    DISABLE_PACKAGE,
    RESTORE_PACKAGE,
    RESTORE_ALL,
    BACKUP_EXPORTED
}

data class DebloatOperationEntry(
    val id: String,
    val timestampMs: Long,
    val action: DebloatActionType,
    val packageName: String?,
    val succeeded: Boolean,
    val verifiedState: String,
    val detail: String
)

data class BatchProgress(
    val totalCount: Int,
    val processedCount: Int,
    val currentPackage: String?,
    val isRunning: Boolean
)
