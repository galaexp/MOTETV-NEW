package com.gala.motetv.remote

import androidx.compose.ui.graphics.vector.ImageVector

enum class ShortcutCategory {
    APP, SYSTEM, MEDIA, CUSTOM
}

data class TvShortcut(
    val id: String,
    val title: String,
    val category: ShortcutCategory,
    val iconName: String = "tv",
    val deepLinkOrAction: String = "",
    val isCustom: Boolean = false,
    val isFavorite: Boolean = true,
    val order: Int = 0
)
