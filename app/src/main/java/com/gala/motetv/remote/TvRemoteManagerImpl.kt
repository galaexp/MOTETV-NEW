package com.gala.motetv.remote

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.protocol.androidtv.AndroidTvTransport
import com.gala.motetv.protocol.androidtv.codec.RemoteProtoCodec
import com.gala.motetv.protocol.androidtv.model.AndroidTvKeyCodes
import com.gala.motetv.protocol.androidtv.model.KeyDirection
import com.gala.motetv.protocol.androidtv.model.RemoteConfigure
import com.gala.motetv.protocol.androidtv.model.RemoteImeKeyInject
import com.gala.motetv.protocol.androidtv.model.RemoteKeyInject
import com.gala.motetv.protocol.androidtv.model.RemoteMessage
import com.gala.motetv.protocol.androidtv.model.RemotePong
import com.gala.motetv.protocol.androidtv.model.RemoteSetActive
import com.gala.motetv.storage.TvCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvRemoteManagerImpl(
    private val credentialStore: TvCredentialStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TvRemoteManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentDevice = MutableStateFlow<TvDevice?>(null)
    override val currentDevice: StateFlow<TvDevice?> = _currentDevice.asStateFlow()

    private var connectionJob: Job? = null
    private var readLoopJob: Job? = null
    private var transport: AndroidTvTransport? = null

    override fun connect(device: TvDevice) {
        connectionJob?.cancel()
        readLoopJob?.cancel()
        _currentDevice.value = device

        connectionJob = scope.launch {
            try {
                TvLogger.i(TvLogger.TAG_REMOTE, "Connecting remote session to ${device.name} @ ${device.host}:${device.remotePort}")
                _connectionState.value = ConnectionState.Connecting(device.host, device.remotePort)

                val identity = credentialStore.getOrCreateIdentity()
                val trans = AndroidTvTransport(
                    host = device.host,
                    port = device.remotePort,
                    identity = identity
                )
                transport = trans

                trans.connect()
                _connectionState.value = ConnectionState.Authenticating

                val writer = trans.frameWriter
                val reader = trans.frameReader
                if (writer == null || reader == null) {
                    throw IllegalStateException("Failed to open remote stream")
                }

                // Send RemoteConfigure
                TvLogger.i(TvLogger.TAG_REMOTE, "Sending RemoteConfigure to port 6466")
                val cfgMsg = RemoteMessage(remoteConfigure = RemoteConfigure(code1 = 622))
                writer.writeFrame(RemoteProtoCodec.encode(cfgMsg))

                // Send RemoteSetActive
                TvLogger.i(TvLogger.TAG_REMOTE, "Sending RemoteSetActive(1)")
                val activeMsg = RemoteMessage(remoteSetActive = RemoteSetActive(active = 1))
                writer.writeFrame(RemoteProtoCodec.encode(activeMsg))

                _connectionState.value = ConnectionState.Ready(device)
                TvLogger.i(TvLogger.TAG_REMOTE, "Remote control session active and READY for key injection!")

                // Start background reader loop for TV pings / keepalive
                startReadLoop(reader, writer, device)

            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_REMOTE, "Remote connection failed: ${e.message}", e)
                cleanup()
                _connectionState.value = ConnectionState.Failed(
                    errorCode = "ERR_REMOTE_CONNECT",
                    userMessage = "Failed to connect to TV Remote port 6466 (${e.message})"
                )
            }
        }
    }

    private fun startReadLoop(
        reader: com.gala.motetv.protocol.androidtv.ProtoFrameReader,
        writer: com.gala.motetv.protocol.androidtv.ProtoFrameWriter,
        device: TvDevice
    ) {
        readLoopJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && transport?.isConnected == true) {
                    val frame = reader.readNextFrame()
                    val msg = RemoteProtoCodec.decode(frame)

                    msg.remotePingRequest?.let { ping ->
                        TvLogger.d(TvLogger.TAG_REMOTE, "Received RemotePingRequest(val1=${ping.val1}), responding with Pong")
                        val pongMsg = RemoteMessage(remotePong = RemotePong(val1 = ping.val1))
                        writer.writeFrame(RemoteProtoCodec.encode(pongMsg))
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "Remote session stream closed: ${e.message}")
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    override fun sendKey(keyCode: Int, direction: KeyDirection) {
        val trans = transport
        if (trans == null || !trans.isConnected) {
            TvLogger.w(TvLogger.TAG_REMOTE, "Cannot send key $keyCode: Remote not connected")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val writer = trans.frameWriter ?: return@launch
                val keyMsg = RemoteMessage(
                    remoteKeyInject = RemoteKeyInject(keyCode = keyCode, direction = direction)
                )
                writer.writeFrame(RemoteProtoCodec.encode(keyMsg))
                TvLogger.i(TvLogger.TAG_REMOTE, "Sent KeyCode $keyCode (${direction.name}) to TV")
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_REMOTE, "Failed to send key $keyCode: ${e.message}", e)
            }
        }
    }

    override fun sendImeText(text: String) {
        val trans = transport
        if (trans == null || !trans.isConnected) return

        scope.launch(Dispatchers.IO) {
            try {
                val writer = trans.frameWriter ?: return@launch
                val imeMsg = RemoteMessage(
                    remoteImeKeyInject = RemoteImeKeyInject(appInfo = 0, text = text)
                )
                writer.writeFrame(RemoteProtoCodec.encode(imeMsg))
                TvLogger.i(TvLogger.TAG_REMOTE, "Sent IME text: '$text' to TV")
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_REMOTE, "Failed to send IME text: ${e.message}", e)
            }
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        readLoopJob?.cancel()
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
        TvLogger.i(TvLogger.TAG_REMOTE, "Disconnected from TV")
    }

    private fun cleanup() {
        try {
            transport?.close()
        } catch (_: Exception) {}
        transport = null
    }
}
