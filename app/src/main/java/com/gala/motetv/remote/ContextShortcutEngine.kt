package com.gala.motetv.remote

import com.gala.motetv.core.model.ConnectionState

enum class RemoteContextMode {
    DISCONNECTED, READY, MEDIA_CASTING, SCREEN_MIRRORING, YOUTUBE_ACTIVE
}

data class ContextualAction(
    val id: String,
    val title: String,
    val description: String,
    val actionType: String,
    val iconName: String
)

class ContextShortcutEngine {

    fun evaluateContext(
        connectionState: ConnectionState,
        isMirroring: Boolean,
        isMediaCasting: Boolean,
        activeAppUrl: String = ""
    ): RemoteContextMode {
        if (connectionState !is ConnectionState.Ready) {
            return RemoteContextMode.DISCONNECTED
        }
        if (isMirroring) {
            return RemoteContextMode.SCREEN_MIRRORING
        }
        if (isMediaCasting) {
            return RemoteContextMode.MEDIA_CASTING
        }
        if (activeAppUrl.contains("youtube", ignoreCase = true)) {
            return RemoteContextMode.YOUTUBE_ACTIVE
        }
        return RemoteContextMode.READY
    }

    fun getRecommendedActions(mode: RemoteContextMode): List<ContextualAction> {
        return when (mode) {
            RemoteContextMode.DISCONNECTED -> listOf(
                ContextualAction("connect", "Connect TV", "Establish Android TV Remote v2 link", "action:connect", "wifi"),
                ContextualAction("diagnostics", "Run Diagnostics", "Test Wi-Fi, IP, and TV endpoint reachability", "action:diagnostics", "build")
            )
            RemoteContextMode.SCREEN_MIRRORING -> listOf(
                ContextualAction("stop_mirror", "Stop Mirroring", "Disconnect live phone screen mirror stream", "action:stop_mirror", "stop"),
                ContextualAction("stream_status", "Stream Info", "View FPS, viewers, and JPEG stream metrics", "action:stream_status", "info")
            )
            RemoteContextMode.MEDIA_CASTING -> listOf(
                ContextualAction("play_pause", "Play / Pause", "Toggle TV video playback", "action:play_pause", "play"),
                ContextualAction("seek_forward", "Seek +10s", "Skip forward in active cast stream", "action:seek_fwd", "fast_forward"),
                ContextualAction("seek_backward", "Seek -10s", "Rewind active cast stream", "action:seek_rew", "fast_rewind")
            )
            RemoteContextMode.YOUTUBE_ACTIVE -> listOf(
                ContextualAction("skip_ad", "Skip YouTube Ad", "One-tap sequence to skip YouTube video ad", "action:skip_ad", "fast_forward"),
                ContextualAction("youtube_home", "YouTube Home", "Return to YouTube main feed", "action:youtube_home", "home")
            )
            RemoteContextMode.READY -> listOf(
                ContextualAction("skip_ad", "Skip TV Ad", "Smart skip for video ads on TV", "action:skip_ad", "fast_forward"),
                ContextualAction("quick_keyboard", "Type Text", "Send search query or text to TV", "action:keyboard", "keyboard")
            )
        }
    }
}
