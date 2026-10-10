package com.gala.motetv.core.model

object AndroidTvKeyCodes {
    const val KEYCODE_UNKNOWN = 0
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_0 = 7
    const val KEYCODE_1 = 8
    const val KEYCODE_2 = 9
    const val KEYCODE_3 = 10
    const val KEYCODE_4 = 11
    const val KEYCODE_5 = 12
    const val KEYCODE_6 = 13
    const val KEYCODE_7 = 14
    const val KEYCODE_8 = 15
    const val KEYCODE_9 = 16
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_POWER = 26
    const val KEYCODE_A = 29
    const val KEYCODE_B = 30
    const val KEYCODE_C = 31
    const val KEYCODE_D = 32
    const val KEYCODE_E = 33
    const val KEYCODE_F = 34
    const val KEYCODE_G = 35
    const val KEYCODE_H = 36
    const val KEYCODE_I = 37
    const val KEYCODE_J = 38
    const val KEYCODE_K = 39
    const val KEYCODE_L = 40
    const val KEYCODE_M = 41
    const val KEYCODE_N = 42
    const val KEYCODE_O = 43
    const val KEYCODE_P = 44
    const val KEYCODE_Q = 45
    const val KEYCODE_R = 46
    const val KEYCODE_S = 47
    const val KEYCODE_T = 48
    const val KEYCODE_U = 49
    const val KEYCODE_V = 50
    const val KEYCODE_W = 51
    const val KEYCODE_X = 52
    const val KEYCODE_Y = 53
    const val KEYCODE_Z = 54
    const val KEYCODE_COMMA = 55
    const val KEYCODE_PERIOD = 56
    const val KEYCODE_TAB = 61
    const val KEYCODE_SPACE = 62
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_MINUS = 69
    const val KEYCODE_EQUALS = 70
    const val KEYCODE_SLASH = 76
    const val KEYCODE_AT = 77
    const val KEYCODE_MENU = 82
    const val KEYCODE_SEARCH = 84
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_STOP = 86
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88
    const val KEYCODE_MEDIA_REWIND = 89
    const val KEYCODE_MEDIA_FAST_FORWARD = 90
    const val KEYCODE_MUTE = 91
    const val KEYCODE_PAGE_UP = 92
    const val KEYCODE_PAGE_DOWN = 93
    const val KEYCODE_FORWARD = 125
    const val KEYCODE_VOLUME_MUTE = 164
    const val KEYCODE_INFO = 165
    const val KEYCODE_REFRESH = 168
    const val KEYCODE_GUIDE = 172
    const val KEYCODE_SETTINGS = 176
    const val KEYCODE_TV_INPUT = 178
    const val KEYCODE_ASSIST = 219
    const val KEYCODE_ZOOM_IN = 242
    const val KEYCODE_ZOOM_OUT = 243

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
            KEYCODE_TAB -> "KEYCODE_TAB"
            KEYCODE_PAGE_UP -> "KEYCODE_PAGE_UP"
            KEYCODE_PAGE_DOWN -> "KEYCODE_PAGE_DOWN"
            KEYCODE_MENU -> "KEYCODE_MENU"
            KEYCODE_SEARCH -> "KEYCODE_SEARCH"
            KEYCODE_REFRESH -> "KEYCODE_REFRESH"
            KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
            KEYCODE_ASSIST -> "KEYCODE_ASSIST"
            in KEYCODE_0..KEYCODE_9 -> "KEYCODE_${keyCode - KEYCODE_0}"
            in KEYCODE_A..KEYCODE_Z -> "KEYCODE_${('A' + (keyCode - KEYCODE_A))}"
            else -> "KEYCODE_$keyCode"
        }
    }

    /**
     * Maps a character to its Android TV Keycode for direct typing / fallback typing.
     */
    fun getKeyCodeForChar(c: Char): Int? {
        return when (c.lowercaseChar()) {
            in 'a'..'z' -> KEYCODE_A + (c.lowercaseChar() - 'a')
            in '0'..'9' -> KEYCODE_0 + (c - '0')
            ' ' -> KEYCODE_SPACE
            '\n' -> KEYCODE_ENTER
            '\b' -> KEYCODE_DEL
            '\t' -> KEYCODE_TAB
            '.' -> KEYCODE_PERIOD
            ',' -> KEYCODE_COMMA
            '-', '_' -> KEYCODE_MINUS
            '=', '+' -> KEYCODE_EQUALS
            '/' -> KEYCODE_SLASH
            '@' -> KEYCODE_AT
            else -> null
        }
    }
}
