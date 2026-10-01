package com.gala.motetv.protocol.androidtv.model

enum class KeyDirection(val value: Int) {
    UNKNOWN(0),
    SHORT(1),
    START_LONG(2),
    END_LONG(3);

    companion object {
        fun fromValue(v: Int): KeyDirection = entries.find { it.value == v } ?: SHORT
    }
}

object AndroidTvKeyCodes {
    const val KEYCODE_UNKNOWN = 0
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_POWER = 26
    const val KEYCODE_MENU = 82
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_REWIND = 89
    const val KEYCODE_MEDIA_FAST_FORWARD = 90
    const val KEYCODE_MUTE = 91
    const val KEYCODE_VOLUME_MUTE = 164
    const val KEYCODE_SETTINGS = 176
    const val KEYCODE_TV_INPUT = 178
    const val KEYCODE_GUIDE = 172
    const val KEYCODE_ASSIST = 219
}

data class RemoteConfigure(
    val code1: Int = 622,
    val model: String = "MoteTV Phone",
    val vendor: String = "Gala"
)

data class RemoteSetActive(
    val active: Int = 1
)

data class RemotePingRequest(
    val val1: Int = 0,
    val val2: Int = 0
)

data class RemotePong(
    val val1: Int = 0
)

data class RemoteKeyInject(
    val keyCode: Int,
    val direction: KeyDirection = KeyDirection.SHORT
)

data class RemoteImeKeyInject(
    val appInfo: Int = 0,
    val text: String = ""
)

data class RemoteMessage(
    val remoteConfigure: RemoteConfigure? = null,
    val remoteSetActive: RemoteSetActive? = null,
    val remotePingRequest: RemotePingRequest? = null,
    val remotePong: RemotePong? = null,
    val remoteKeyInject: RemoteKeyInject? = null,
    val remoteImeKeyInject: RemoteImeKeyInject? = null
)
