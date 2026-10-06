package com.gala.motetv.core.model

enum class RemoteButton(val keyCode: Int, val codeName: String) {
    UP(AndroidTvKeyCodes.KEYCODE_DPAD_UP, "KEYCODE_DPAD_UP"),
    DOWN(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, "KEYCODE_DPAD_DOWN"),
    LEFT(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT, "KEYCODE_DPAD_LEFT"),
    RIGHT(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT, "KEYCODE_DPAD_RIGHT"),
    CENTER(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, "KEYCODE_DPAD_CENTER"),
    BACK(AndroidTvKeyCodes.KEYCODE_BACK, "KEYCODE_BACK"),
    HOME(AndroidTvKeyCodes.KEYCODE_HOME, "KEYCODE_HOME"),
    SEARCH(AndroidTvKeyCodes.KEYCODE_SEARCH, "KEYCODE_SEARCH"),
    VOLUME_UP(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, "KEYCODE_VOLUME_UP"),
    VOLUME_DOWN(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, "KEYCODE_VOLUME_DOWN"),
    POWER(AndroidTvKeyCodes.KEYCODE_POWER, "KEYCODE_POWER"),
    MENU(AndroidTvKeyCodes.KEYCODE_MENU, "KEYCODE_MENU"),
    ENTER(AndroidTvKeyCodes.KEYCODE_ENTER, "KEYCODE_ENTER"),
    DELETE(AndroidTvKeyCodes.KEYCODE_DEL, "KEYCODE_DEL"),
    SPACE(AndroidTvKeyCodes.KEYCODE_SPACE, "KEYCODE_SPACE"),
    PLAY_PAUSE(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, "KEYCODE_MEDIA_PLAY_PAUSE");

    companion object {
        fun fromKeyCode(keyCode: Int): RemoteButton? = entries.find { it.keyCode == keyCode }
    }
}
