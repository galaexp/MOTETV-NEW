package com.gala.motetv.debloat

/**
 * Curated CleanYourTV package classification catalog grounded in Gathin23/cleanyourtv,
 * including Xiaomi Mi TV Stick Issue #884 safeguards and strict per-package manufacturer/model rules.
 */
object CleanYourTvPackageCatalog {

    /**
     * Exact model/board identifiers with verified Mi TV Stick coverage.
     * Any variant outside this set receives strict per-package manual review.
     */
    val VERIFIED_XIAOMI_STICK_IDENTIFIERS: Set<String> = setOf(
        "aquaman",
        "mitv-aesp0",
        "mi tv stick",
        "mitv stick",
        "yoda" // Mi TV Stick 4K
    )

    val STOCK_LAUNCHER_PACKAGES: Set<String> = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.mitv.tvhome"
    )

    val CRITICAL_SYSTEM_EXACT: Map<String, String> = mapOf(
        // CleanYourTV Issue #884 brick-risk packages on original Xiaomi Mi TV Stick
        "com.google.android.permissioncontroller" to "Android Runtime Permission Controller (CleanYourTV Issue #884 bootloop guard)",
        "com.android.permissioncontroller" to "Android Permission Controller (boot critical)",
        "com.google.android.packageinstaller" to "Package Installer (CleanYourTV Issue #884 bootloop guard)",
        "com.android.packageinstaller" to "Android Package Installer (boot critical)",
        "com.google.android.ext.services" to "Android Services Library (CleanYourTV Issue #884 bootloop guard)",
        "com.android.ext.services" to "Android Services Library",
        "com.google.android.ext.shared" to "Android Shared Library (CleanYourTV Issue #884 bootloop guard)",
        "com.android.ext.shared" to "Android Shared Library",
        "com.google.android.modulemetadata" to "Mainline Module Metadata (CleanYourTV Issue #884 bootloop guard)",
        "com.android.modulemetadata" to "Mainline Module Metadata",

        // Core OS, SystemUI, Settings, Networking & Recovery Components
        "android" to "Android OS System Framework",
        "com.android.shell" to "ADB & System Shell Runtime",
        "com.android.systemui" to "System UI & Power/Volume Overlays",
        "com.android.settings" to "System Settings",
        "com.android.tv.settings" to "Android TV Settings",
        "com.xiaomi.mitv.settings" to "Xiaomi TV System Settings",
        "com.mitv.tvhome.settings" to "Xiaomi TV Settings Helper",
        "com.android.providers.settings" to "Settings Storage Provider",
        "com.android.providers.media" to "Media Storage Provider",
        "com.android.providers.downloads" to "Download Manager Provider",
        "com.android.bluetooth" to "Bluetooth Pairing & Remote Control Transport",
        "com.android.networkstack" to "Android Networking Stack",
        "com.google.android.networkstack" to "Google Networking Stack",
        "com.android.tv" to "Live TV Framework",
        "com.android.tv.provision" to "Android TV Setup Provisioning",
        "com.google.android.tungsten.setupwraith" to "Android TV Setup Wizard",
        "com.android.companiondevicemanager" to "Companion Device Manager",

        // Google Play Services & App Store (recovery-critical)
        "com.android.vending" to "Google Play Store (required for app updates & recovery)",
        "com.google.android.gms" to "Google Play Services",
        "com.google.android.gsf" to "Google Services Framework",
        "com.google.android.webview" to "Android System WebView",
        "com.android.webview" to "Android System WebView",

        // Keyboards / Input & Remote-Control Services
        "com.android.inputdevices" to "Hardware Input Device Keymaps",
        "com.google.android.inputmethod.latin" to "Gboard On-Screen TV Keyboard",
        "com.android.inputmethod.latin" to "AOSP Keyboard",
        "com.xiaomi.mitv.ime" to "Xiaomi TV Input Method",
        "com.google.android.tv.remote.service" to "Android TV Remote Service v2 (disabling disconnects MoteTV!)",
        "com.xiaomi.mitv.remotecontroller" to "Xiaomi Remote Control Service",
        "com.xiaomi.ble.remotecontrol" to "Xiaomi BLE Remote Control Driver",

        // Accessibility Services (critical for user accessibility & recovery)
        "com.google.android.marvin.talkback" to "Android Accessibility Suite / TalkBack",
        "com.android.talkback" to "TalkBack Accessibility Service",
        "com.google.android.accessibility.switchaccess" to "Switch Access Accessibility Service",

        // Core TV Customization & CleanYourTV Home Launcher
        "com.google.android.atv.customization" to "Android TV Core Customization",
        "com.gathin.tvhome" to "CleanYourTV Home Launcher"
    )

    val KNOWN_PACKAGES: List<KnownPackageDescriptor> = listOf(
        // 1. Manufacturer Telemetry & Analytics
        KnownPackageDescriptor(
            packageName = "com.miui.tv.analytics",
            appName = "Mi TV Analytics",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Xiaomi usage analytics and telemetry reporter.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.statistic",
            appName = "Xiaomi Statistics Service",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Background telemetry & diagnostic metrics collector.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.mitv.tvmanager",
            appName = "Mi TV Background Manager",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Xiaomi background telemetry/system helper. Review carefully on older Mi TV Sticks.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.sony.dtv.osat.analytics",
            appName = "Sony TV Analytics",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Sony Bravia analytics telemetry service (not a Xiaomi Mi TV Stick component).",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("sony")
        ),
        KnownPackageDescriptor(
            packageName = "com.tcl.telemetry",
            appName = "TCL Telemetry",
            category = PackageCategory.MANUFACTURER_TELEMETRY,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "TCL background analytics reporter (not a Xiaomi Mi TV Stick component).",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("tcl")
        ),

        // 2. Manufacturer Ads & Recommendation Services
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome.atv",
            appName = "Xiaomi PatchWall / Recommendations",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Xiaomi promoted home banner & recommendation feed service.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome.michannel",
            appName = "Mi Channel Ads & Feed",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Xiaomi promotional channel shelf and ad cards.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome.mitvplus",
            appName = "Mi TV+ Sponsored Channels",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Ad-supported Xiaomi FAST channels integration.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xm.webcontent",
            appName = "Xiaomi Web Promo Content",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Loads web-based promotional banners in stock launcher.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.tvrecommendations",
            appName = "Android TV Home Recommendations",
            category = PackageCategory.MANUFACTURER_ADS,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Populates sponsored recommendation rows in Google TV/Android TV Home.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),

        // 3. Manufacturer Content Providers
        KnownPackageDescriptor(
            packageName = "com.xiaomi.floatingframe",
            appName = "Xiaomi Floating Promo Overlay",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Floating promotional frame overlay service.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.mitv.smartshare",
            appName = "Xiaomi SmartShare",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Xiaomi proprietary screen/content sharing service.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.milinkservice",
            appName = "MiLink Media Service",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Xiaomi proprietary casting companion; unnecessary if using Google Cast.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.dream",
            appName = "Xiaomi Gallery Screensaver",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Manufacturer ambient screensaver provider.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.mitv.tvhome",
            appName = "Xiaomi Stock TV Launcher",
            category = PackageCategory.MANUFACTURER_CONTENT,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Stock Xiaomi HOME launcher. Blocked while active; only change after CleanYourTV Home is installed & verified.",
            riskLevel = RiskLevel.HIGH,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),

        // 4. Optional Preinstalled Apps (incl. Firmware Updates & Assistant -> ask user!)
        KnownPackageDescriptor(
            packageName = "com.xiaomi.mitv.updateservice",
            appName = "Xiaomi Firmware Updater",
            category = PackageCategory.OPTIONAL_PREINSTALLED,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Delivers Xiaomi OS security and firmware OTA updates. Ask user before disabling.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "com.xiaomi.amazon.alexa",
            appName = "Amazon Alexa Preload",
            category = PackageCategory.OPTIONAL_PREINSTALLED,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Preinstalled third-party Alexa voice client.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),
        KnownPackageDescriptor(
            packageName = "android.autoinstalls.config.xiaomi.amelie",
            appName = "Xiaomi PAI Auto-Installer Config",
            category = PackageCategory.OPTIONAL_PREINSTALLED,
            recommendation = DebloatRecommendation.SAFE_TO_DISABLE,
            reason = "Play Auto Installs configuration stub for preloaded apps.",
            riskLevel = RiskLevel.LOW,
            verifiedXiaomiStickModels = VERIFIED_XIAOMI_STICK_IDENTIFIERS,
            supportedManufacturers = setOf("xiaomi")
        ),

        // 5. Streaming Apps (ask user — never auto-disable Netflix/YouTube!)
        KnownPackageDescriptor(
            packageName = "com.netflix.ninja",
            appName = "Netflix",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Streaming app — do not disable unless you never use Netflix.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.youtube.tv",
            appName = "YouTube for Android TV",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Primary YouTube app — ask user before changing.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.amazon.amazonvideo.livingroom",
            appName = "Prime Video",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Preinstalled streaming client.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.disney.disneyplus",
            appName = "Disney+",
            category = PackageCategory.STREAMING,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Preinstalled streaming client.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true
        ),

        // 6. Google Services (Chromecast, Voice Assistant, TV Launcher -> ask/keep!)
        KnownPackageDescriptor(
            packageName = "com.google.android.apps.mediashell",
            appName = "Chromecast Built-in",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Powers Google Cast / Chromecast built-in; disabling breaks casting from phones.",
            riskLevel = RiskLevel.HIGH,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.katniss",
            appName = "Google Assistant for TV",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Voice search and Assistant key on remote. Disabling disables microphone search.",
            riskLevel = RiskLevel.MODERATE,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.tvlauncher",
            appName = "Android TV Home (Stock Launcher)",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Stock Android TV launcher. Blocked while active; only change after CleanYourTV Home is installed & verified.",
            riskLevel = RiskLevel.HIGH,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.apps.tv.launcherx",
            appName = "Google TV Home (Stock Launcher)",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Stock Google TV launcher. Blocked while active; only change after CleanYourTV Home is installed & verified.",
            riskLevel = RiskLevel.HIGH,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.backdrop",
            appName = "Ambient Mode Screensaver",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.OPTIONAL_ASK_USER,
            reason = "Google Ambient screensaver & photo frames.",
            riskLevel = RiskLevel.LOW,
            requiresOptionalPrompt = true
        ),
        KnownPackageDescriptor(
            packageName = "com.google.android.partnersetup",
            appName = "Google Partner Setup",
            category = PackageCategory.GOOGLE_SERVICES,
            recommendation = DebloatRecommendation.KEEP,
            reason = "Provisioning & partner customization helper.",
            riskLevel = RiskLevel.HIGH
        )
    )

    val KNOWN_BY_PACKAGE: Map<String, KnownPackageDescriptor> =
        KNOWN_PACKAGES.associateBy { it.packageName }
}
