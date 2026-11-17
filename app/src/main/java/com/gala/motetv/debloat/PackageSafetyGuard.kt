package com.gala.motetv.debloat

import com.gala.motetv.adb.TvDeviceInfo

/**
 * Enforces CleanYourTV safety invariants before scanning recommendations or executing ADB changes.
 * Specifically guards against the Xiaomi Mi TV Stick Issue #884 brick scenario and prevents disabling
 * core OS, TV input/HDMI, Bluetooth, remote v2 services, keyboards, Play services, or active HOME.
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
        "tv.remote.service"
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
            return true to "Essential Android TV / overlay / input system component — disabling risks bootloop or broken hardware."
        }
        if (BLOCKED_SUBSTRINGS.any { lower.contains(it) }) {
            return true to "Protected core permission, HDMI/TV input, or remote control dependency."
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
                packageName != "com.gathin.tvhome"
            if (!safeToDisableOldHome) {
                return SafetyDecision(
                    allowed = false,
                    requiresExplicitOptionalPrompt = false,
                    reason = "Active HOME launcher ($packageName) cannot be disabled until CleanYourTV Home is installed, set default, and verified working."
                )
            }
        }

        // Prevent disabling the only launcher if CleanYourTV Home isn't installed
        if ((packageName == "com.google.android.tvlauncher" || packageName == "com.google.android.apps.tv.launcherx") &&
            (!verifiedReplacementHomeInstalled || !"com.gathin.tvhome".let { installedPackages.contains(it) })
        ) {
            return SafetyDecision(
                allowed = false,
                requiresExplicitOptionalPrompt = false,
                reason = "Cannot disable stock Android TV launcher without verified replacement launcher installed."
            )
        }

        val known = CleanYourTvPackageCatalog.KNOWN_BY_PACKAGE[packageName]
        if (known != null && known.requiresOptionalPrompt) {
            return SafetyDecision(
                allowed = true,
                requiresExplicitOptionalPrompt = true,
                reason = known.reason
            )
        }

        return SafetyDecision(allowed = true, requiresExplicitOptionalPrompt = false)
    }

    fun matchesVerifiedXiaomiStickModel(deviceInfo: TvDeviceInfo): Boolean {
        val id = "${deviceInfo.model} ${deviceInfo.deviceName} ${deviceInfo.productBoard}".lowercase()
        return CleanYourTvPackageCatalog.VERIFIED_XIAOMI_STICK_IDENTIFIERS.any { id.contains(it) }
    }
}
