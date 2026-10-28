package com.gala.motetv.remote

import com.gala.motetv.storage.TvCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TvShortcutRepository(
    private val credentialStore: TvCredentialStore
) {
    private val _shortcuts = MutableStateFlow<List<TvShortcut>>(emptyList())
    val shortcuts: StateFlow<List<TvShortcut>> = _shortcuts.asStateFlow()

    companion object {
        val DEFAULT_SHORTCUTS = listOf(
            TvShortcut("youtube", "YouTube", ShortcutCategory.APP, "youtube", "https://www.youtube.com", isFavorite = true, order = 0),
            TvShortcut("skip_ad", "Skip Ad", ShortcutCategory.MEDIA, "fast_forward", "action:skip_ad", isFavorite = true, order = 1),
            TvShortcut("netflix", "Netflix", ShortcutCategory.APP, "movie", "https://www.netflix.com", isFavorite = true, order = 2),
            TvShortcut("prime_video", "Prime", ShortcutCategory.APP, "video", "https://app.primevideo.com", isFavorite = true, order = 3),
            TvShortcut("spotify", "Spotify", ShortcutCategory.APP, "music", "spotify:", isFavorite = true, order = 4),
            TvShortcut("home", "Home", ShortcutCategory.SYSTEM, "home", "action:home", isFavorite = true, order = 5),
            TvShortcut("diagnostics", "Diagnostics", ShortcutCategory.SYSTEM, "build", "action:diagnostics", isFavorite = true, order = 6)
        )
    }

    init {
        loadShortcuts()
    }

    fun loadShortcuts() {
        val favoriteIds = credentialStore.getFavoriteAppIds()
        val customApps = credentialStore.getCustomApps()

        val loadedCustom = customApps.mapIndexed { idx, app ->
            TvShortcut(
                id = app.id,
                title = app.name,
                category = ShortcutCategory.CUSTOM,
                iconName = "extension",
                deepLinkOrAction = app.primaryDeepLink,
                isCustom = true,
                isFavorite = favoriteIds.contains(app.id),
                order = 10 + idx
            )
        }

        val allList = DEFAULT_SHORTCUTS.map { sc ->
            sc.copy(isFavorite = favoriteIds.contains(sc.id))
        } + loadedCustom

        _shortcuts.value = allList.sortedBy { it.order }
    }

    fun toggleFavorite(shortcutId: String) {
        val isNowFav = credentialStore.toggleFavoriteApp(shortcutId)
        _shortcuts.value = _shortcuts.value.map {
            if (it.id == shortcutId) it.copy(isFavorite = isNowFav) else it
        }
    }

    fun addCustomShortcut(title: String, deepLink: String) {
        val id = "custom_" + System.currentTimeMillis()
        val customApp = com.gala.motetv.launcher.AppDefinition(
            id = id,
            name = title,
            packageId = "custom.package",
            primaryDeepLink = deepLink,
            category = com.gala.motetv.launcher.AppCategory.CUSTOM,
            isCustom = true,
            isFavorite = true
        )
        credentialStore.saveCustomApp(customApp)
        credentialStore.toggleFavoriteApp(id)
        loadShortcuts()
    }

    fun removeCustomShortcut(shortcutId: String) {
        credentialStore.removeCustomApp(shortcutId)
        loadShortcuts()
    }
}
