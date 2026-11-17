package com.gala.motetv.tvhome

import org.json.JSONArray
import org.json.JSONObject

/**
 * CleanYourTV theme catalog & strict `config.json` generator/validator derived from Gathin23/cleanyourtv.
 * Only keys actually consumed by the CleanYourTV launcher are emitted.
 */
object CleanYourTvThemeCatalog {

    val SUPPORTED_CONFIG_KEYS: Set<String> = setOf(
        "background_color",
        "accent_color",
        "columns",
        "dock_row",
        "single_row",
        "dock_band_all",
        "dock_glass",
        "dock_opacity",
        "ambient_glow",
        "glow_snap",
        "sky_intensity",
        "clock_format",
        "show_stats",
        "reduced_motion",
        "greeting_name",
        "wallpaper",
        "wallpaper_rotation_minutes",
        "wallpaper_order",
        "wallpaper_dim",
        "wallpaper_captions",
        "dock_apps",
        "hidden_apps"
    )

    val SUPPORTED_CLOCK_FORMATS = listOf("system", "12", "24")
    val SUPPORTED_WALLPAPER_ORDERS = listOf("sequential", "random")

    val PRESETS: List<CleanYourTvThemePreset> = listOf(
        CleanYourTvThemePreset("default", "Default", "Clean native dark launcher baseline", ThemePresetCategory.MINIMAL_GLASS, "#38BDF8", "#0B111E", "#0F172A", "#38BDF8"),
        CleanYourTvThemePreset("dock-glass", "Dock Glass", "Frosted bottom dock with translucent glass card", ThemePresetCategory.MINIMAL_GLASS, "#38BDF8", "#0A0F1A", "#162032", "#7DD3FC", defaultDockGlass = true, defaultDockOpacity = 64),
        CleanYourTvThemePreset("ambient-glass", "Ambient Glass", "Translucent dock band with soft ambient glow", ThemePresetCategory.MINIMAL_GLASS, "#60A5FA", "#0B1220", "#1E293B", "#93C5FD", defaultDockGlass = true, defaultDockBandAll = true, defaultAmbientGlow = true, defaultDockOpacity = 62),
        CleanYourTvThemePreset("glass", "Glass", "Crisp frosted dock surface", ThemePresetCategory.MINIMAL_GLASS, "#E2E8F0", "#0F172A", "#334155", "#F8FAFC", defaultDockGlass = true, defaultDockOpacity = 58),
        CleanYourTvThemePreset("glass-gallery", "Glass Gallery", "Translucent dock showcase over dark slate", ThemePresetCategory.MINIMAL_GLASS, "#A78BFA", "#111827", "#1F2937", "#C4B5FD", defaultDockGlass = true, defaultDockRow = true, defaultDockOpacity = 60),
        CleanYourTvThemePreset("midnight-glass", "Midnight Glass", "Deep OLED black & indigo glass highlights", ThemePresetCategory.MINIMAL_GLASS, "#818CF8", "#05070E", "#111827", "#6366F1", defaultDockGlass = true, defaultGlowSnap = true, defaultDockOpacity = 52),
        CleanYourTvThemePreset("minimal", "Minimal", "Single-row focus layout without ambient glow", ThemePresetCategory.MINIMAL_GLASS, "#94A3B8", "#090D16", "#1E293B", "#E2E8F0", defaultDockGlass = false, defaultSingleRow = true, defaultAmbientGlow = false, defaultSkyIntensity = 20),
        CleanYourTvThemePreset("onyx", "Onyx", "Pure OLED obsidian contrast", ThemePresetCategory.MINIMAL_GLASS, "#F8FAFC", "#000000", "#111111", "#E5E7EB", defaultSkyIntensity = 15, defaultAmbientGlow = false),
        CleanYourTvThemePreset("six-columns", "Six Columns", "Dense 6-column power-user TV grid", ThemePresetCategory.MINIMAL_GLASS, "#38BDF8", "#0A0F1A", "#162032", "#38BDF8", defaultColumns = 6, defaultDockGlass = true),
        CleanYourTvThemePreset("aurora", "Aurora", "Northern lights emerald & cyan gradient", ThemePresetCategory.SCENIC_ART, "#34D399", "#071A18", "#064E3B", "#6EE7B7", defaultSkyIntensity = 85, defaultAmbientGlow = true),
        CleanYourTvThemePreset("ridgelines", "Ridgelines", "Layered twilight mountain silhouettes", ThemePresetCategory.SCENIC_ART, "#60A5FA", "#0D1526", "#1E3A5F", "#93C5FD", defaultSkyIntensity = 70),
        CleanYourTvThemePreset("ruska", "Ruska", "Nordic autumn amber & birch tones", ThemePresetCategory.SCENIC_ART, "#F59E0B", "#1A110B", "#451A03", "#FBBF24", defaultSkyIntensity = 65),
        CleanYourTvThemePreset("sweden", "Sweden", "Nordic cobalt & warm birch minimalism", ThemePresetCategory.SCENIC_ART, "#FACC15", "#0B1D3A", "#1E3A8A", "#FDE047"),
        CleanYourTvThemePreset("stockholm", "Stockholm", "Scandinavian waterfront dusk (CC0 art)", ThemePresetCategory.SCENIC_ART, "#38BDF8", "#0E1726", "#1E293B", "#BAE6FD", defaultDockGlass = true, attributionNote = "CleanYourTV MIT / CC0 Artwork"),
        CleanYourTvThemePreset("aerial", "Aerial", "High-altitude horizon vista", ThemePresetCategory.SCENIC_ART, "#7DD3FC", "#0A1424", "#172554", "#38BDF8"),
        CleanYourTvThemePreset("twilight", "Twilight", "Dusk violet horizon", ThemePresetCategory.SCENIC_ART, "#C084FC", "#140D24", "#2E1065", "#E879F9", defaultSkyIntensity = 75),
        CleanYourTvThemePreset("nature-calm", "Nature Calm", "Muted forest moss & mist", ThemePresetCategory.SCENIC_ART, "#4ADE80", "#0C1812", "#14532D", "#86EFAC"),
        CleanYourTvThemePreset("storm", "Storm", "Slate thunderhead blue-grey", ThemePresetCategory.SCENIC_ART, "#94A3B8", "#0D131D", "#1E293B", "#CBD5E1"),
        CleanYourTvThemePreset("red-rain", "Red Rain", "Crimson neon over dark asphalt", ThemePresetCategory.SCENIC_ART, "#F87171", "#180A0D", "#450A0A", "#FCA5A5"),
        CleanYourTvThemePreset("istanbul", "Istanbul", "Bosphorus sunset & warm terracotta", ThemePresetCategory.SCENIC_ART, "#FB923C", "#19100C", "#431407", "#FDBA74"),
        CleanYourTvThemePreset("istanbul_v2", "Istanbul v2", "Updated Bosphorus skyline edition", ThemePresetCategory.SCENIC_ART, "#FBBF24", "#121524", "#1E293B", "#FDE68A"),
        CleanYourTvThemePreset("scenes", "Scenes", "Curated photographic mood backdrop", ThemePresetCategory.SCENIC_ART, "#38BDF8", "#0F172A", "#1E293B", "#7DD3FC"),
        CleanYourTvThemePreset("christmas", "Christmas", "Festive pine & warm hearth (CC0)", ThemePresetCategory.SCENIC_ART, "#EF4444", "#0C1A12", "#14532D", "#FCA5A5", attributionNote = "CleanYourTV MIT / CC0 Artwork"),
        CleanYourTvThemePreset("diwali", "Diwali", "Warm diya gold & deep plum", ThemePresetCategory.SCENIC_ART, "#FBBF24", "#1A0B1E", "#581C87", "#FDE047"),
        CleanYourTvThemePreset("cinematic", "Cinematic", "Widescreen theater framing & deep dim", ThemePresetCategory.HIGH_CONTRAST, "#F59E0B", "#08080A", "#18181B", "#FBBF24", defaultWallpaperDim = 45, defaultDockOpacity = 86),
        CleanYourTvThemePreset("family", "Family", "High-visibility 4-column docked layout", ThemePresetCategory.HIGH_CONTRAST, "#34D399", "#0F172A", "#1E293B", "#6EE7B7", defaultColumns = 4, defaultDockRow = true),
        CleanYourTvThemePreset("blocky", "Blocky", "Solid dock without glass translucency", ThemePresetCategory.HIGH_CONTRAST, "#22D3EE", "#090D16", "#1E293B", "#22D3EE", defaultDockGlass = false, defaultDockOpacity = 95, defaultGlowSnap = true),
        CleanYourTvThemePreset("retro", "Retro", "Warm CRT phosphor terminal palette", ThemePresetCategory.HIGH_CONTRAST, "#F97316", "#16110C", "#292524", "#FB923C", defaultDockGlass = false),
        CleanYourTvThemePreset("weeb", "Weeb", "Sakura & midnight cyber accent", ThemePresetCategory.HIGH_CONTRAST, "#F472B6", "#120B1C", "#3B0764", "#F9A8D4", defaultDockGlass = true, defaultAmbientGlow = true)
    )

    private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun normalizeHexColor(input: String, fallback: String): String {
        val trimmed = input.trim().uppercase()
        return if (HEX_COLOR_REGEX.matches(trimmed)) trimmed else fallback
    }

    fun normalizeClockFormat(input: String): String {
        return when (input.trim().lowercase()) {
            "12", "12h" -> "12"
            "24", "24h" -> "24"
            "system" -> "system"
            else -> "system"
        }
    }

    fun normalizeWallpaperOrder(input: String): String {
        return when (input.trim().lowercase()) {
            "random" -> "random"
            else -> "sequential"
        }
    }

    fun sanitizedConfig(config: CleanYourTvThemeConfig): CleanYourTvThemeConfig {
        return config.copy(
            backgroundColorHex = normalizeHexColor(config.backgroundColorHex, "#090D16"),
            accentColorHex = normalizeHexColor(config.accentColorHex, "#38BDF8"),
            columns = config.columns.coerceIn(3, 7),
            dockOpacity = config.dockOpacity.coerceIn(30, 100),
            skyIntensity = config.skyIntensity.coerceIn(0, 100),
            clockFormat = normalizeClockFormat(config.clockFormat),
            greetingName = config.greetingName.trim().take(32),
            wallpaperRotationMinutes = config.wallpaperRotationMinutes.coerceAtLeast(0),
            wallpaperOrder = normalizeWallpaperOrder(config.wallpaperOrder),
            wallpaperDim = config.wallpaperDim.coerceIn(0, 85),
            dockApps = config.dockApps.map { it.trim() }.filter { it.isNotBlank() },
            hiddenApps = config.hiddenApps.map { it.trim() }.filter { it.isNotBlank() }
        )
    }

    fun applyPresetToConfig(current: CleanYourTvThemeConfig, preset: CleanYourTvThemePreset): CleanYourTvThemeConfig {
        return current.copy(
            selectedPresetId = preset.id,
            accentColorHex = preset.defaultAccentHex,
            backgroundColorHex = preset.defaultBackgroundHex,
            columns = preset.defaultColumns,
            dockRow = preset.defaultDockRow,
            singleRow = preset.defaultSingleRow,
            dockBandAll = preset.defaultDockBandAll,
            dockGlass = preset.defaultDockGlass,
            dockOpacity = preset.defaultDockOpacity,
            ambientGlow = preset.defaultAmbientGlow,
            glowSnap = preset.defaultGlowSnap,
            skyIntensity = preset.defaultSkyIntensity,
            wallpaperDim = preset.defaultWallpaperDim
        )
    }

    fun buildConfigJson(config: CleanYourTvThemeConfig): String {
        val safe = sanitizedConfig(config)
        val root = JSONObject().apply {
            put("background_color", safe.backgroundColorHex)
            put("accent_color", safe.accentColorHex)
            put("columns", safe.columns)
            put("dock_row", safe.dockRow)
            put("single_row", safe.singleRow)
            put("dock_band_all", safe.dockBandAll)
            put("dock_glass", safe.dockGlass)
            put("dock_opacity", safe.dockOpacity)
            put("ambient_glow", safe.ambientGlow)
            put("glow_snap", safe.glowSnap)
            put("sky_intensity", safe.skyIntensity)
            put("clock_format", safe.clockFormat)
            put("show_stats", safe.showStats)
            put("reduced_motion", safe.reducedMotion)
            put("greeting_name", safe.greetingName)
            put("wallpaper", safe.wallpaperEnabled)
            put("wallpaper_rotation_minutes", safe.wallpaperRotationMinutes)
            put("wallpaper_order", safe.wallpaperOrder)
            put("wallpaper_dim", safe.wallpaperDim)
            put("wallpaper_captions", safe.wallpaperCaptions)
            put("dock_apps", JSONArray(safe.dockApps))
            put("hidden_apps", JSONArray(safe.hiddenApps))
        }
        return root.toString(2)
    }

    /**
     * Validates that a read-back `config.json` strictly follows the CleanYourTV schema,
     * contains zero unsupported keys, uses proper types (`wallpaper` is Boolean!), and matches [expected].
     */
    fun validateDeployedConfig(jsonString: String, expected: CleanYourTvThemeConfig): Result<Unit> {
        return runCatching {
            val safe = sanitizedConfig(expected)
            val obj = JSONObject(jsonString)

            val keys = mutableListOf<String>()
            obj.keys().forEach { keys.add(it) }
            val unsupported = keys.filterNot { SUPPORTED_CONFIG_KEYS.contains(it) }
            require(unsupported.isEmpty()) {
                "Unsupported CleanYourTV config keys found in config.json: ${unsupported.joinToString()}"
            }

            require(obj.has("wallpaper") && obj.get("wallpaper") is Boolean) {
                "Field 'wallpaper' in config.json must be a Boolean."
            }
            require(obj.getBoolean("wallpaper") == safe.wallpaperEnabled) {
                "Mismatch on 'wallpaper': expected ${safe.wallpaperEnabled}"
            }
            require(obj.getString("background_color") == safe.backgroundColorHex) {
                "Mismatch on 'background_color'"
            }
            require(obj.getString("accent_color") == safe.accentColorHex) {
                "Mismatch on 'accent_color'"
            }
            require(obj.getInt("columns") == safe.columns) {
                "Mismatch on 'columns'"
            }
            require(obj.getBoolean("dock_glass") == safe.dockGlass) {
                "Mismatch on 'dock_glass'"
            }
            require(obj.getInt("dock_opacity") == safe.dockOpacity) {
                "Mismatch on 'dock_opacity'"
            }
            require(obj.getString("clock_format") in SUPPORTED_CLOCK_FORMATS) {
                "Invalid 'clock_format' value"
            }
            require(obj.getString("clock_format") == safe.clockFormat) {
                "Mismatch on 'clock_format'"
            }
        }
    }
}
