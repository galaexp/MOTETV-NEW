package com.gala.motetv.debloat

import com.gala.motetv.adb.TvDeviceInfo
import com.gala.motetv.tvhome.CleanYourTvConstants

/**
 * Enforces CleanYourTV safety invariants before scanning recommendations or executing ADB changes.
 * Specifically guards against the Xiaomi Mi TV Stick Issue #884 brick scenario, prevents cross-OEM
 * catalog bleed, and blocks disabling OS, SystemUI, Accessibility, IME, Remote, Play, Network, or active HOME.
 */
object PackageSafetyGuard {

    private val BLOCKED_PREFIXES = listOf(
        "com.google.android.overlay.modules.",
        "com.android.overlay.",
        "com.android.internal.",
        "com.android.providers.",
        "com.android.server.",
        "com.android.networkstack",
        "com.android.wifi",
        "com.google.android.networkstack",
        "com.google.android.wifi",
        "com.android.inputmethod.",
        "com.google.android.inputmethod.",
        "com.android.tv.tuner",
        "com.droidlogic."
    )

    private val BLOCKED_SUBSTRINGS = listOf(
        "permissioncontroller",
        "packageinstaller",
        "ext.services",
        "ext.shared",
        "modulemetadata",
        "tvinput",
        "hdmi",
        "hdmicec",
        "keyguard",
        "remotecontrol",
        "tv.remote.service",
        "talkback",
        "switchaccess",
        "setupwraith"
    )

    data class SafetyDecision(
        val allowed: Boolean,
        val requiresExplicitOptionalPrompt: Boolean,
        val reason: String? = null
    )

    fun isAlwaysBlockedPackage(packageName: String): Pair<Boolean, String?> {
        val exactReason = CleanYourTvPackageCatalog.CRITICAL_SYSTEM_EXACT[packageName]
        if (exactReason != null) {
            return true to exactReason
        }

        val lower = packageName.lowercase()
        if (BLOCKED_PREFIXES.any { lower.startsWith(it) }) {
            return true to "Essential Android TV / overlay / input / network component — disabling risks bootloop or lost recovery."
        }
        if (BLOCKED_SUBSTRINGS.any { lower.contains(it) }) {
            return true to "Protected core permission, accessibility, setup, HDMI/TV input, or remote control dependency."
        }
        return false to null
    }

    fun evaluateDisableCandidate(
        packageName: String,
        deviceInfo: TvDeviceInfo,
        installedPackages: Set<String>,
        verifiedReplacementHomeInstalled: Boolean,
        verifiedReplacementHomeActive: Boolean
    ): SafetyDecision {
        val (alwaysBlocked, blockReason) = isAlwaysBlockedPackage(packageName)
        if (alwaysBlocked) {
            return SafetyDecision(allowed = false, requiresExplicitOptionalPrompt = false, reason = blockReason)
        }

        // Active HOME protection: never disable active launcher unless verified replacement is installed & active
        if (packageName == deviceInfo.currentHomePackage) {
            val safeToDisableOldHome = verifiedReplacementHomeInstalled &&
                verifiedReplacementHomeActive &&
                packageName != CleanYourTvConstants.LAUNCHER_PACKAGE
            if (!safeToDisableOldHome) {
                return SafetyDecision(
                    allowed = false,
                    requiresExplicitOptionalPrompt = false,
                    reason = "Active HOME launcher ($packageName) cannot be disabled until CleanYourTV Home is installed, set default, and verified working."
                )
            }
        }

        // Prevent disabling stock launchers if CleanYourTV Home isn't verified installed & active
        if (CleanYourTvPackageCatalog.STOCK_LAUNCHER_PACKAGES.contains(packageName) &&
            (!verifiedReplacementHomeInstalled ||
                !verifiedReplacementHomeActive ||
                !installedPackages.contains(CleanYourTvConstants.LAUNCHER_PACKAGE))
        ) {
            return SafetyDecision(
                allowed = false,
                requiresExplicitOptionalPrompt = false,
                reason = "Cannot disable stock Android TV launcher without verified active replacement launcher installed."
            )
        }

        val known = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[packageName]
        if (known != null && (known.requiresOptionalPrompt || !matchesPackageDeviceCatalog(known, deviceInfo))) {
            return SafetyDecision(
                allowed = true,
                requiresExplicitOptionalPrompt = true,
                reason = known.reason
            )
        }

        return SafetyDecision(allowed = true, requiresExplicitOptionalPrompt = false)
    }

    fun matchesVerifiedXiaomiStickModel(deviceInfo: TvDeviceInfo): Boolean {
        if (!deviceInfo.isXiaomiDevice) return false
        val id = "${deviceInfo.model} ${deviceInfo.deviceName} ${deviceInfo.productBoard}".lowercase()
        return CleanYourTvPackageCatalog.VERIFIED_XIAOMI_STICK_IDENTIFIERS.any { id.contains(it) }
    }

    /**
     * Returns true ONLY if [known] is explicitly verified for [deviceInfo]'s detected manufacturer
     * AND exact model metadata. Cross-manufacturer packages (e.g., Sony/TCL on Xiaomi) and unknown
     * models return false so they default cleanly to manual review.
     */
    fun matchesPackageDeviceCatalog(known: KnownPackageDescriptor, deviceInfo: TvDeviceInfo): Boolean {
        val detectedMfg = detectedManufacturerKey(deviceInfo)

        if (known.supportedManufacturers.isNotEmpty()) {
            val mfgMatched = known.supportedManufacturers.any { expectedMfg ->
                when (expectedMfg.lowercase()) {
                    "xiaomi" -> deviceInfo.isXiaomiDevice || detectedMfg == "xiaomi"
                    else -> detectedMfg == expectedMfg.lowercase()
                }
            }
            if (!mfgMatched) return false
        }

        if (deviceInfo.isXiaomiDevice) {
            if (known.verifiedXiaomiStickModels.isEmpty()) return false
            val id = "${deviceInfo.model} ${deviceInfo.deviceName} ${deviceInfo.productBoard}".lowercase()
            return known.verifiedXiaomiStickModels.any { id.contains(it.lowercase()) }
        }

        // Non-Xiaomi or unknown manufacturer: require explicit manufacturer verification on the package
        return known.supportedManufacturers.isNotEmpty() && detectedMfg.isNotBlank() && detectedMfg != "unknown"
    }

    private fun detectedManufacturerKey(deviceInfo: TvDeviceInfo): String {
        if (deviceInfo.isXiaomiDevice) return "xiaomi"
        return deviceInfo.manufacturer.trim().lowercase()
    }
}
