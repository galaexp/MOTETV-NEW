package com.gala.motetv.core.model

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
    const val KEYCODE_SPACE = 62
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_MENU = 82
    const val KEYCODE_SEARCH = 84
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_REWIND = 89
    const val KEYCODE_MEDIA_FAST_FORWARD = 90
    const val KEYCODE_MUTE = 91
    const val KEYCODE_VOLUME_MUTE = 164
    const val KEYCODE_GUIDE = 172
    const val KEYCODE_SETTINGS = 176
    const val KEYCODE_TV_INPUT = 178
    const val KEYCODE_ASSIST = 219

    fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KEYCODE_HOME -> "KEYCODE_HOME"
            KEYCODE_BACK -> "KEYCODE_BACK"
            KEYCODE_DPAD_UP -> "KEYCODE_DPAD_UP"
            KEYCODE_DPAD_DOWN -> "KEYCODE_DPAD_DOWN"
            KEYCODE_DPAD_LEFT -> "KEYCODE_DPAD_LEFT"
            KEYCODE_DPAD_RIGHT -> "KEYCODE_DPAD_RIGHT"
            KEYCODE_DPAD_CENTER -> "KEYCODE_DPAD_CENTER"
            KEYCODE_VOLUME_UP -> "KEYCODE_VOLUME_UP"
            KEYCODE_VOLUME_DOWN -> "KEYCODE_VOLUME_DOWN"
            KEYCODE_POWER -> "KEYCODE_POWER"
            KEYCODE_SPACE -> "KEYCODE_SPACE"
            KEYCODE_ENTER -> "KEYCODE_ENTER"
            KEYCODE_DEL -> "KEYCODE_DEL"
            KEYCODE_MENU -> "KEYCODE_MENU"
            KEYCODE_SEARCH -> "KEYCODE_SEARCH"
            KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
            KEYCODE_ASSIST -> "KEYCODE_ASSIST"
            else -> "KEYCODE_$keyCode"
        }
    }
}
