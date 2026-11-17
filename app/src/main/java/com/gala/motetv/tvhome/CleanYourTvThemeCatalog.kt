package com.gala.motetv.tvhome

import org.json.JSONArray
import org.json.JSONObject

/**
 * Official CleanYourTV theme catalog & config.json generator derived from Gathin23/cleanyourtv.
 */
object CleanYourTvThemeCatalog {

    val PRESETS: List<CleanYourTvThemePreset> = listOf(
        CleanYourTvThemePreset("default", "Default", "Clean native dark launcher baseline", ThemePresetCategory.MINIMAL_GLASS, "#38BDF8", "#0B111E", "#0F172A", "#38BDF8"),
        CleanYourTvThemePreset("dock-glass", "Dock Glass", "Frosted bottom dock with subtle cyan border", ThemePresetCategory.MINIMAL_GLASS, "#38BDF8", "#0A0F1A", "#162032", "#7DD3FC"),
        CleanYourTvThemePreset("ambient-glass", "Ambient Glass", "Soft translucent ambient backdrop tiles", ThemePresetCategory.MINIMAL_GLASS, "#60A5FA", "#0B1220", "#1E293B", "#93C5FD"),
        CleanYourTvThemePreset("glass", "Glass", "Crisp monochrome frosted cards", ThemePresetCategory.MINIMAL_GLASS, "#E2E8F0", "#0F172A", "#334155", "#F8FAFC"),
        CleanYourTvThemePreset("glass-gallery", "Glass Gallery", "Gallery showcase tiles with translucent dock", ThemePresetCategory.MINIMAL_GLASS, "#A78BFA", "#111827", "#1F2937", "#C4B5FD"),
        CleanYourTvThemePreset("midnight-glass", "Midnight Glass", "Deep OLED black & indigo glass highlights", ThemePresetCategory.MINIMAL_GLASS, "#818CF8", "#05070E", "#111827", "#6366F1"),
        CleanYourTvThemePreset("minimal", "Minimal", "Zero clutter, focus-first typography", ThemePresetCategory.MINIMAL_GLASS, "#94A3B8", "#090D16", "#1E293B", "#E2E8F0", defaultClockStyle = "minimal", defaultDockBlur = false),
        CleanYourTvThemePreset("onyx", "Onyx", "Pure OLED obsidian contrast", ThemePresetCategory.MINIMAL_GLASS, "#F8FAFC", "#000000", "#111111", "#E5E7EB"),
        CleanYourTvThemePreset("six-columns", "Six Columns", "Dense 6-column power-user TV grid", ThemePresetCategory.MINIMAL_GLASS, "#38BDF8", "#0A0F1A", "#162032", "#38BDF8", defaultColumns = 6, defaultDockSlots = 6),
        CleanYourTvThemePreset("aurora", "Aurora", "Northern lights emerald & cyan gradient", ThemePresetCategory.SCENIC_ART, "#34D399", "#071A18", "#064E3B", "#6EE7B7"),
        CleanYourTvThemePreset("ridgelines", "Ridgelines", "Layered twilight mountain silhouettes", ThemePresetCategory.SCENIC_ART, "#60A5FA", "#0D1526", "#1E3A5F", "#93C5FD"),
        CleanYourTvThemePreset("ruska", "Ruska", "Nordic autumn amber & birch tones", ThemePresetCategory.SCENIC_ART, "#F59E0B", "#1A110B", "#451A03", "#FBBF24"),
        CleanYourTvThemePreset("sweden", "Sweden", "Nordic cobalt & warm birch minimalism", ThemePresetCategory.SCENIC_ART, "#FACC15", "#0B1D3A", "#1E3A8A", "#FDE047"),
        CleanYourTvThemePreset("stockholm", "Stockholm", "Scandinavian waterfront dusk (CC0 art)", ThemePresetCategory.SCENIC_ART, "#38BDF8", "#0E1726", "#1E293B", "#BAE6FD", attributionNote = "CleanYourTV MIT / CC0 Artwork"),
        CleanYourTvThemePreset("aerial", "Aerial", "High-altitude horizon vista", ThemePresetCategory.SCENIC_ART, "#7DD3FC", "#0A1424", "#172554", "#38BDF8"),
        CleanYourTvThemePreset("twilight", "Twilight", "Dusk violet horizon", ThemePresetCategory.SCENIC_ART, "#C084FC", "#140D24", "#2E1065", "#E879F9"),
        CleanYourTvThemePreset("nature-calm", "Nature Calm", "Muted forest moss & mist", ThemePresetCategory.SCENIC_ART, "#4ADE80", "#0C1812", "#14532D", "#86EFAC"),
        CleanYourTvThemePreset("storm", "Storm", "Slate thunderhead blue-grey", ThemePresetCategory.SCENIC_ART, "#94A3B8", "#0D131D", "#1E293B", "#CBD5E1"),
        CleanYourTvThemePreset("red-rain", "Red Rain", "Crimson neon over dark asphalt", ThemePresetCategory.SCENIC_ART, "#F87171", "#180A0D", "#450A0A", "#FCA5A5"),
        CleanYourTvThemePreset("istanbul", "Istanbul", "Bosphorus sunset & warm terracotta", ThemePresetCategory.SCENIC_ART, "#FB923C", "#19100C", "#431407", "#FDBA74"),
        CleanYourTvThemePreset("istanbul_v2", "Istanbul v2", "Updated Bosphorus skyline edition", ThemePresetCategory.SCENIC_ART, "#FBBF24", "#121524", "#1E293B", "#FDE68A"),
        CleanYourTvThemePreset("scenes", "Scenes", "Curated photographic mood backdrop", ThemePresetCategory.SCENIC_ART, "#38BDF8", "#0F172A", "#1E293B", "#7DD3FC"),
        CleanYourTvThemePreset("christmas", "Christmas", "Festive pine & warm hearth (CC0)", ThemePresetCategory.SCENIC_ART, "#EF4444", "#0C1A12", "#14532D", "#FCA5A5", attributionNote = "CleanYourTV MIT / CC0 Artwork"),
        CleanYourTvThemePreset("diwali", "Diwali", "Warm diya gold & deep plum", ThemePresetCategory.SCENIC_ART, "#FBBF24", "#1A0B1E", "#581C87", "#FDE047"),
        CleanYourTvThemePreset("cinematic", "Cinematic", "Widescreen theater framing & bold clock", ThemePresetCategory.HIGH_CONTRAST, "#F59E0B", "#08080A", "#18181B", "#FBBF24", defaultClockStyle = "bold"),
        CleanYourTvThemePreset("family", "Family", "Friendly high-visibility large app tiles", ThemePresetCategory.HIGH_CONTRAST, "#34D399", "#0F172A", "#1E293B", "#6EE7B7", defaultColumns = 4, defaultClockStyle = "bold"),
        CleanYourTvThemePreset("blocky", "Blocky", "Crisp geometric tiles without blur", ThemePresetCategory.HIGH_CONTRAST, "#22D3EE", "#090D16", "#1E293B", "#22D3EE", defaultDockBlur = false),
        CleanYourTvThemePreset("retro", "Retro", "Warm CRT phosphor terminal palette", ThemePresetCategory.HIGH_CONTRAST, "#F97316", "#16110C", "#292524", "#FB923C", defaultClockStyle = "bold"),
        CleanYourTvThemePreset("weeb", "Weeb", "Sakura & midnight cyber accent", ThemePresetCategory.HIGH_CONTRAST, "#F472B6", "#120B1C", "#3B0764", "#F9A8D4")
    )

    private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun normalizeHexColor(input: String, fallback: String): String {
        val trimmed = input.trim().uppercase()
        return if (HEX_COLOR_REGEX.matches(trimmed)) trimmed else fallback
    }

    fun buildConfigJson(config: CleanYourTvThemeConfig): String {
        val safeAccent = normalizeHexColor(config.accentColorHex, "#38BDF8")
        val safeBg = normalizeHexColor(config.backgroundColorHex, "#090D16")
        val safeCols = config.columns.coerceIn(3, 7)
        val safeDock = config.dockSlots.coerceIn(3, 8)
        val safeFormat = if (config.clockFormat == "12h") "12h" else "24h"
        val safeClockStyle = when (config.clockStyle) {
            "minimal", "bold", "hidden" -> config.clockStyle
            else -> "minimal"
        }

        val root = JSONObject().apply {
            put("theme_id", config.themeId)
            put("accent_color", safeAccent)
            put("background_color", safeBg)
            put("columns", safeCols)
            put("dock_slots", safeDock)
            put("dock_blur", config.dockBlur)
            put("clock_format", safeFormat)
            put("clock_style", safeClockStyle)
            put("show_stats", config.showStats)
            put("reduced_motion", config.reducedMotion)
            if (!config.wallpaperFileName.isNullOrBlank()) {
                put("wallpaper", config.wallpaperFileName)
            }
            put("dock_apps", JSONArray(config.dockApps.filter { it.isNotBlank() }))
            put("hidden_apps", JSONArray(config.hiddenApps.filter { it.isNotBlank() }))
        }
        return root.toString(2)
    }
}
