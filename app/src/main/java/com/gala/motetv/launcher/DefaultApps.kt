package com.gala.motetv.launcher

object DefaultApps {

    val YOUTUBE = AppDefinition(
        id = "youtube",
        name = "YouTube",
        packageId = "com.google.android.youtube.tv",
        primaryDeepLink = "https://www.youtube.com",
        alternativeDeepLinks = listOf(
            "vnd.youtube.launch://",
            "vnd.youtube://",
            "market://launch?id=com.google.android.youtube.tv"
        ),
        accentColorHex = 0xFFFF0000,
        isFavorite = true,
        category = AppCategory.VIDEO
    )

    val NETFLIX = AppDefinition(
        id = "netflix",
        name = "Netflix",
        packageId = "com.netflix.ninja",
        primaryDeepLink = "https://www.netflix.com/title",
        alternativeDeepLinks = listOf(
            "netflix://",
            "market://launch?id=com.netflix.ninja"
        ),
        accentColorHex = 0xFFE50914,
        isFavorite = true,
        category = AppCategory.STREAMING
    )

    val PRIME_VIDEO = AppDefinition(
        id = "prime_video",
        name = "Prime Video",
        packageId = "com.amazon.amazonvideo.livingroom",
        primaryDeepLink = "https://app.primevideo.com",
        alternativeDeepLinks = listOf(
            "primevideo://",
            "market://launch?id=com.amazon.amazonvideo.livingroom"
        ),
        accentColorHex = 0xFF00A8E1,
        isFavorite = true,
        category = AppCategory.STREAMING
    )

    val DISNEY_PLUS = AppDefinition(
        id = "disney_plus",
        name = "Disney+",
        packageId = "com.disney.disneyplus",
        primaryDeepLink = "https://www.disneyplus.com",
        alternativeDeepLinks = listOf(
            "disneyplus://",
            "market://launch?id=com.disney.disneyplus"
        ),
        accentColorHex = 0xFF113CCF,
        isFavorite = true,
        category = AppCategory.STREAMING
    )

    val PLEX = AppDefinition(
        id = "plex",
        name = "Plex",
        packageId = "com.plexapp.android",
        primaryDeepLink = "plex://",
        alternativeDeepLinks = listOf(
            "market://launch?id=com.plexapp.android"
        ),
        accentColorHex = 0xFFE5A00D,
        isFavorite = false,
        category = AppCategory.VIDEO
    )

    val TWITCH = AppDefinition(
        id = "twitch",
        name = "Twitch",
        packageId = "tv.twitch.android.app",
        primaryDeepLink = "https://www.twitch.tv",
        alternativeDeepLinks = listOf(
            "twitch://",
            "market://launch?id=tv.twitch.android.app"
        ),
        accentColorHex = 0xFF9146FF,
        isFavorite = false,
        category = AppCategory.STREAMING
    )

    val SPOTIFY = AppDefinition(
        id = "spotify",
        name = "Spotify",
        packageId = "com.spotify.tv.android",
        primaryDeepLink = "spotify://",
        alternativeDeepLinks = listOf(
            "https://open.spotify.com",
            "market://launch?id=com.spotify.tv.android"
        ),
        accentColorHex = 0xFF1DB954,
        isFavorite = true,
        category = AppCategory.MUSIC
    )

    val KODI = AppDefinition(
        id = "kodi",
        name = "Kodi",
        packageId = "org.xbmc.kodi",
        primaryDeepLink = "market://launch?id=org.xbmc.kodi",
        alternativeDeepLinks = emptyList(),
        accentColorHex = 0xFF17B2E7,
        isFavorite = false,
        category = AppCategory.UTILITY
    )

    val VLC = AppDefinition(
        id = "vlc",
        name = "VLC",
        packageId = "org.videolan.vlc",
        primaryDeepLink = "vlc://",
        alternativeDeepLinks = listOf(
            "market://launch?id=org.videolan.vlc",
            "market://details?id=org.videolan.vlc"
        ),
        accentColorHex = 0xFFFF8800,
        isFavorite = true,
        category = AppCategory.VIDEO
    )

    val TV_BRO = AppDefinition(
        id = "tv_bro",
        name = "TV Bro Browser",
        packageId = "com.phlox.tvwebbrowser",
        primaryDeepLink = "market://launch?id=com.phlox.tvwebbrowser",
        alternativeDeepLinks = listOf(
            "https://google.com",
            "market://details?id=com.phlox.tvwebbrowser"
        ),
        accentColorHex = 0xFF4CAF50,
        isFavorite = false,
        category = AppCategory.UTILITY
    )

    val ALL_DEFAULT_APPS = listOf(
        YOUTUBE,
        NETFLIX,
        PRIME_VIDEO,
        DISNEY_PLUS,
        VLC,
        SPOTIFY,
        PLEX,
        TWITCH,
        KODI,
        TV_BRO
    )
}
