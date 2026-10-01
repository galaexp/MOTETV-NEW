package com.gala.motetv.remote

import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.protocol.androidtv.model.KeyDirection
import kotlinx.coroutines.flow.StateFlow

interface TvRemoteManager {
    val connectionState: StateFlow<ConnectionState>
    val currentDevice: StateFlow<TvDevice?>

    fun connect(device: TvDevice)
    fun disconnect()
    fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.SHORT)
    fun sendImeText(text: String)
}
