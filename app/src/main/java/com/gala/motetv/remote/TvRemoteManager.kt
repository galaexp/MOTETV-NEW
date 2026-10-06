package com.gala.motetv.remote

import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.RemoteButton
import com.gala.motetv.core.model.TvDevice
import com.google.android.apps.tv.remote.protocol.Direction
import kotlinx.coroutines.flow.StateFlow

interface TvRemoteManager {
    val connectionState: StateFlow<ConnectionState>
    val remoteState: StateFlow<RemoteState>
    val currentDevice: StateFlow<TvDevice?>

    fun connect(device: TvDevice)
    fun disconnect()
    fun sendKey(keyCode: Int, direction: Direction = Direction.SHORT)
    fun sendButton(button: RemoteButton, direction: Direction = Direction.SHORT) {
        sendKey(button.keyCode, direction)
    }
    fun sendImeText(text: String)
}
