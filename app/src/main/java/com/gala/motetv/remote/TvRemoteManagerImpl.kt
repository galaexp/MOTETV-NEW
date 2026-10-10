package com.gala.motetv.remote

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.protocol.androidtv.AndroidTvTransport
import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.storage.TvCredentialStore
import com.google.android.apps.tv.remote.protocol.DeviceInfo
import com.google.android.apps.tv.remote.protocol.Direction
import com.google.android.apps.tv.remote.protocol.RemoteAppLinkLaunchRequest
import com.google.android.apps.tv.remote.protocol.RemoteConfigure
import com.google.android.apps.tv.remote.protocol.RemoteImeKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteMessage
import com.google.android.apps.tv.remote.protocol.RemotePingResponse
import com.google.android.apps.tv.remote.protocol.RemoteSetActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException

class TvRemoteManagerImpl(
    private val credentialStore: TvCredentialStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TvRemoteManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _remoteState = MutableStateFlow(RemoteState.DISCONNECTED)
    override val remoteState: StateFlow<RemoteState> = _remoteState.asStateFlow()

    private val _currentDevice = MutableStateFlow<TvDevice?>(null)
    override val currentDevice: StateFlow<TvDevice?> = _currentDevice.asStateFlow()

    private val _imeActive = MutableStateFlow(false)
    override val imeActive: StateFlow<Boolean> = _imeActive.asStateFlow()

    private val _lastImeText = MutableStateFlow("")
    override val lastImeText: StateFlow<String> = _lastImeText.asStateFlow()

    private var connectionJob: Job? = null
    private var readLoopJob: Job? = null
    private var reconnectJob: Job? = null
    private var transport: AndroidTvTransport? = null

    private val writeMutex = Mutex()

    private var configureReceivedDeferred: CompletableDeferred<Unit>? = null
    private var setActiveReceivedDeferred: CompletableDeferred<Unit>? = null

    override fun connect(device: TvDevice) {
        reconnectJob?.cancel()
        connectionJob?.cancel()
        readLoopJob?.cancel()

        _currentDevice.value = device

        connectionJob = scope.launch {
            connectInternal(device, retryCount = 0)
        }
    }

    private suspend fun connectInternal(device: TvDevice, retryCount: Int) {
        try {
            updateRemoteState(RemoteState.CONNECTING)
            _connectionState.value = ConnectionState.Connecting(device.host, device.remotePort)
            TvLogger.i(TvLogger.TAG_REMOTE, "Connecting remote session to ${device.name} @ ${device.host}:${device.remotePort}")

            val identity = credentialStore.getOrCreateIdentity(device.id)
            val trans = AndroidTvTransport(
                host = device.host,
                port = device.remotePort,
                identity = identity
            )
            transport = trans

            trans.connect()
            updateRemoteState(RemoteState.TLS_CONNECTED)
            _connectionState.value = ConnectionState.Authenticating

            val reader = trans.frameReader ?: throw IOException("Failed to open remote input stream")
            if (trans.frameWriter == null) throw IOException("Failed to open remote output stream")

            configureReceivedDeferred = CompletableDeferred()
            setActiveReceivedDeferred = CompletableDeferred()

            // Step 1: Start single reader coroutine
            startReaderLoop(reader, device)

            // Step 2: TX RemoteConfigure
            TvLogger.i(TvLogger.TAG_REMOTE, "Sending RemoteConfigure (code1=622)")
            val configureMsg = RemoteMessage(
                remote_configure = RemoteConfigure(
                    code1 = 622,
                    device_info = DeviceInfo(
                        model = "MoteTV",
                        vendor = "Gala",
                        unknown1 = 1,
                        unknown2 = "1",
                        package_name = "com.gala.motetv",
                        app_version = "1.0.0"
                    )
                )
            )
            sendRemoteMessage(configureMsg)
            updateRemoteState(RemoteState.CONFIGURE_SENT)

            // Step 3: WAIT FOR RX RemoteConfigure
            TvLogger.i(TvLogger.TAG_REMOTE, "Waiting for RemoteConfigure from TV...")
            withTimeout(10000) {
                configureReceivedDeferred?.await()
            }
            updateRemoteState(RemoteState.CONFIGURED)

            // Step 4: TX RemoteSetActive
            TvLogger.i(TvLogger.TAG_REMOTE, "Sending RemoteSetActive (active=622)")
            val setActiveMsg = RemoteMessage(
                remote_set_active = RemoteSetActive(active = 622)
            )
            sendRemoteMessage(setActiveMsg)
            updateRemoteState(RemoteState.SET_ACTIVE_SENT)

            // Step 5: WAIT FOR RX RemoteSetActive
            TvLogger.i(TvLogger.TAG_REMOTE, "Waiting for RemoteSetActive from TV...")
            withTimeout(10000) {
                setActiveReceivedDeferred?.await()
            }

            // Step 6: REMOTE READY
            updateRemoteState(RemoteState.READY)
            _connectionState.value = ConnectionState.Ready(device)
            TvLogger.i(TvLogger.TAG_REMOTE, "Remote session READY! Device control active.")

        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_REMOTE, "Remote connection attempt failed: ${e.message}", e)
            cleanup()
            updateRemoteState(RemoteState.ERROR)

            if (retryCount < 3 && _currentDevice.value != null) {
                scheduleReconnect(device, retryCount + 1)
            } else {
                _connectionState.value = ConnectionState.Failed(
                    errorCode = "ERR_REMOTE_CONNECT",
                    userMessage = "Paired — reconnecting to TV...",
                    technicalDetail = e.message
                )
            }
        }
    }

    private fun startReaderLoop(reader: ProtoFrameReader, device: TvDevice) {
        readLoopJob?.cancel()
        readLoopJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && transport?.isConnected == true) {
                    val frameBytes = reader.readNextFrame()
                    val firstBytesHex = frameBytes.take(8).joinToString("") { "%02X".format(it) }
                    TvLogger.d(TvLogger.TAG_REMOTE, "[REMOTE_READER] FRAME RX: length=${frameBytes.size} firstBytes=[$firstBytesHex]")

                    val msg = RemoteMessage.ADAPTER.decode(frameBytes)
                    handleIncomingMessage(msg)
                }
            } catch (e: Exception) {
                if (isActive) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "Remote reader loop terminated: ${e.message}")
                    handleDisconnection(device)
                }
            }
        }
    }

    private suspend fun handleIncomingMessage(msg: RemoteMessage) {
        when {
            msg.remote_configure != null -> {
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE RX:\ntype=REMOTE_CONFIGURE")
                configureReceivedDeferred?.complete(Unit)
            }

            msg.remote_set_active != null -> {
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE RX:\ntype=REMOTE_SET_ACTIVE")
                setActiveReceivedDeferred?.complete(Unit)
            }

            msg.remote_start != null -> {
                val started = msg.remote_start.started ?: false
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE:\nRemoteStart received\nstarted=$started")
            }

            msg.remote_ping_request != null -> {
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE RX:\ntype=REMOTE_PING_REQUEST")
                val pongMsg = RemoteMessage(
                    remote_ping_response = RemotePingResponse(val1 = 1)
                )
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE TX:\ntype=REMOTE_PING_RESPONSE")
                sendRemoteMessage(pongMsg)
            }

            msg.remote_ping_response != null -> {
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE RX:\ntype=REMOTE_PING_RESPONSE")
            }

            msg.remote_error != null -> {
                val err = msg.remote_error
                TvLogger.e(TvLogger.TAG_REMOTE, "REMOTE ERROR:\nvalue=${err.value}\nmessageType=${err.message}")
                updateRemoteState(RemoteState.ERROR)
            }

            msg.remote_ime_key_inject != null -> {
                val ime = msg.remote_ime_key_inject
                val text = ime.text ?: ""
                TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE RX:\ntype=REMOTE_IME_KEY_INJECT text='$text' app_info=${ime.app_info}")
                _imeActive.value = true
                _lastImeText.value = text
            }

            else -> {
                TvLogger.d(TvLogger.TAG_REMOTE, "REMOTE RX:\ntype=UNKNOWN")
            }
        }
    }

    private suspend fun sendRemoteMessage(message: RemoteMessage) {
        writeMutex.withLock {
            val trans = transport ?: throw IOException("Transport is not available")
            val writer = trans.frameWriter ?: throw IOException("Writer is not available")

            val payload = message.encode()
            val firstBytesHex = payload.take(8).joinToString("") { "%02X".format(it) }

            TvLogger.d(TvLogger.TAG_REMOTE, "[REMOTE_WRITER] FRAME TX: length=${payload.size} firstBytes=[$firstBytesHex]")
            writer.writeFrame(payload)
        }
    }

    override fun sendKey(keyCode: Int, direction: Direction) {
        if (_remoteState.value != RemoteState.READY) {
            TvLogger.w(TvLogger.TAG_REMOTE, "Cannot send key $keyCode: remoteState is ${_remoteState.value}, expected READY")
            return
        }

        val keyName = AndroidTvKeyCodes.getKeyName(keyCode)
        TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE TX:\ntype=KEY_INJECT\nkeyCode=$keyName ($keyCode)\ndirection=${direction.name}")

        scope.launch(Dispatchers.IO) {
            try {
                val keyMsg = RemoteMessage(
                    remote_key_inject = RemoteKeyInject(key_code = keyCode, direction = direction)
                )
                sendRemoteMessage(keyMsg)
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_REMOTE, "Failed to send key $keyName ($keyCode): ${e.message}", e)
            }
        }
    }

    override fun sendImeText(text: String) {
        sendImeTextWithFallback(text, useKeyCodesFallback = false)
    }

    override fun sendImeTextWithFallback(text: String, useKeyCodesFallback: Boolean) {
        if (_remoteState.value != RemoteState.READY) {
            TvLogger.w(TvLogger.TAG_REMOTE, "Cannot send IME text: remoteState is ${_remoteState.value}, expected READY")
            return
        }

        if (useKeyCodesFallback) {
            TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE TX:\ntype=DIRECT_KEYCODES text='$text'")
            scope.launch(Dispatchers.IO) {
                for (c in text) {
                    val keyCode = AndroidTvKeyCodes.getKeyCodeForChar(c)
                    if (keyCode != null) {
                        sendKey(keyCode, Direction.SHORT)
                        kotlinx.coroutines.delay(35)
                    }
                }
            }
            return
        }

        TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE TX:\ntype=IME_TEXT text='$text'")

        scope.launch(Dispatchers.IO) {
            try {
                val imeMsg = RemoteMessage(
                    remote_ime_key_inject = RemoteImeKeyInject(app_info = 0, text = text)
                )
                sendRemoteMessage(imeMsg)
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_REMOTE, "Failed to send IME text '$text': ${e.message}", e)
            }
        }
    }

    override fun sendChar(char: Char) {
        val keyCode = AndroidTvKeyCodes.getKeyCodeForChar(char)
        if (keyCode != null) {
            sendKey(keyCode, Direction.SHORT)
        } else {
            sendImeText(char.toString())
        }
    }

    override fun launchAppLink(appLink: String) {
        if (_remoteState.value != RemoteState.READY) {
            TvLogger.w(TvLogger.TAG_REMOTE, "Cannot launch app link '$appLink': remoteState is ${_remoteState.value}, expected READY")
            return
        }

        TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE TX:\ntype=APP_LINK_LAUNCH link='$appLink'")

        scope.launch(Dispatchers.IO) {
            try {
                val appLinkMsg = RemoteMessage(
                    remote_app_link_launch_request = RemoteAppLinkLaunchRequest(app_link = appLink)
                )
                sendRemoteMessage(appLinkMsg)
                TvLogger.i(TvLogger.TAG_REMOTE, "[APP_LAUNCH_SUCCESS] Sent App Link Launch Request via protocol for: $appLink")
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_REMOTE, "[APP_LAUNCH_FAILURE] Failed to launch app link '$appLink': ${e.message}", e)
            }
        }
    }

    private fun handleDisconnection(device: TvDevice) {
        updateRemoteState(RemoteState.DISCONNECTED)
        cleanup()
        scheduleReconnect(device, retryCount = 1)
    }

    private fun scheduleReconnect(device: TvDevice, retryCount: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (1000L * (1 shl (retryCount - 1))).coerceAtMost(8000L)
            TvLogger.i(TvLogger.TAG_REMOTE, "Paired — reconnecting to TV... (Attempt $retryCount in ${delayMs}ms)")
            _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount, nextDelayMs = delayMs)

            kotlinx.coroutines.delay(delayMs)
            connectInternal(device, retryCount)
        }
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        connectionJob?.cancel()
        readLoopJob?.cancel()
        cleanup()

        updateRemoteState(RemoteState.DISCONNECTED)
        _connectionState.value = ConnectionState.Disconnected
        _currentDevice.value = null
        TvLogger.i(TvLogger.TAG_REMOTE, "Disconnected remote control session")
    }

    private fun updateRemoteState(newState: RemoteState) {
        _remoteState.value = newState
        TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE STATE:\n${newState.name}")
    }

    private fun cleanup() {
        try {
            transport?.close()
        } catch (_: Exception) {}
        transport = null
        _imeActive.value = false
        _lastImeText.value = ""
        configureReceivedDeferred?.cancel()
        setActiveReceivedDeferred?.cancel()
        configureReceivedDeferred = null
        setActiveReceivedDeferred = null
    }
}
