package com.gala.motetv.remote

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.pairing.TvIdentity
import com.gala.motetv.protocol.androidtv.AndroidTvTransport
import com.gala.motetv.protocol.androidtv.FrameWriteException
import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.protocol.androidtv.ProtoFrameWriter
import com.gala.motetv.protocol.androidtv.TvTransport
import com.gala.motetv.storage.TvCredentialStore
import com.google.android.apps.tv.remote.protocol.DeviceInfo
import com.google.android.apps.tv.remote.protocol.Direction
import com.google.android.apps.tv.remote.protocol.RemoteAppLinkLaunchRequest
import com.google.android.apps.tv.remote.protocol.RemoteConfigure
import com.google.android.apps.tv.remote.protocol.RemoteImeKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteMessage
import com.google.android.apps.tv.remote.protocol.RemotePingRequest
import com.google.android.apps.tv.remote.protocol.RemotePingResponse
import com.google.android.apps.tv.remote.protocol.RemoteSetActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TvRemoteManagerImpl(
    private val credentialStore: TvCredentialStore,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + ioDispatcher + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            TvLogger.e(TvLogger.TAG_REMOTE, "Uncaught coroutine exception in TvRemoteManager: ${throwable.message}", throwable)
        }
    ),
    private val transportFactory: (String, Int, TvIdentity) -> TvTransport = { host, port, identity ->
        AndroidTvTransport(host, port, identity)
    },
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : TvRemoteManager {

    companion object {
        const val PING_INTERVAL_MS = 12000L
        const val PING_DEADLINE_MS = 6000L
        const val HANDSHAKE_CONFIGURE_TIMEOUT_MS = 10000L
        const val HANDSHAKE_SET_ACTIVE_TIMEOUT_MS = 3000L
        const val MAX_BACKOFF_DELAY_MS = 15000L
        val BACKOFF_DELAYS = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L)
        private const val MAX_REASON_HISTORY = 8
    }

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

    private val _diagnostics = MutableStateFlow(ConnectionDiagnostics())
    override val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = _diagnostics.asStateFlow()

    // Command Queue
    private val commandQueue = RemoteCommandQueue(timeProvider = timeProvider)

    // Concurrency & Session Management
    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()
    private val reconnectLock = Any()
    private val sessionCounter = AtomicLong(0)
    @Volatile
    private var activeSessionId = 0L

    @Volatile
    private var transport: TvTransport? = null
    private var readLoopJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionAttemptJob: Job? = null
    @Volatile
    private var reconnectIsImmediate: Boolean = false
    @Volatile
    private var isConnectingOrHandshaking: Boolean = false

    private var retryCount = 0
    @Volatile
    private var intentionalDisconnect = false

    // Health monitoring state (inbound and outbound kept strictly separate)
    @Volatile
    private var lastReceivedFrameTimeMs: Long = 0L
    @Volatile
    private var lastOutboundFrameTimeMs: Long = 0L
    @Volatile
    private var lastPingSentTimeMs: Long = 0L
    @Volatile
    private var isPingOutstanding: Boolean = false
    @Volatile
    private var outstandingPingVal: Int = 0
    private val pingSeqCounter = AtomicInteger(1)

    // Key hold tracking to prevent logical stuck keys on disconnects or releases
    private val currentlyHeldKeyCodes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private var lastSuccessfulConnectTimeMs: Long = 0L
    private var lastDisconnectReason: String = "None"
    private val recentReconnectReasons = ArrayDeque<String>()
    private var coalescedReconnectCount: Int = 0
    private var uncertainDeliveryCount: Int = 0
    private var lastUncertainCommand: String? = null

    private var configureReceivedDeferred: CompletableDeferred<Unit>? = null
    private var setActiveReceivedDeferred: CompletableDeferred<Unit>? = null

    override fun connect(device: TvDevice) {
        intentionalDisconnect = false
        val previousDevice = _currentDevice.value
        val isDifferentDevice = previousDevice != null && previousDevice.id != device.id

        if (isDifferentDevice) {
            TvLogger.i(TvLogger.TAG_REMOTE, "Switching TV from ${previousDevice?.name} to ${device.name}; clearing queue and reconnect jobs")
            commandQueue.clear()
        }

        _currentDevice.value = device
        updateDiagnostics()

        synchronized(reconnectLock) {
            cancelScheduledReconnectInternal()
            connectionAttemptJob?.cancel()
            connectionAttemptJob = scope.launch(ioDispatcher) {
                performConnect(device, currentRetry = 0)
            }
        }
    }

    override fun disconnect() {
        intentionalDisconnect = true
        synchronized(reconnectLock) {
            cancelScheduledReconnectInternal()
            connectionAttemptJob?.cancel()
            connectionAttemptJob = null
        }

        scope.launch(ioDispatcher) {
            connectionMutex.withLock {
                val sessionId = sessionCounter.incrementAndGet()
                activeSessionId = sessionId

                tearDownSessionInternal(closeTransport = true)
                commandQueue.clear()

                updateRemoteState(RemoteState.DISCONNECTED)
                _connectionState.value = ConnectionState.Disconnected
                _currentDevice.value = null
                recordDisconnectReason("User requested disconnect")
                updateDiagnostics()
                TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Disconnected remote control session")
            }
        }
    }

    override fun reconnectIfDisconnected() {
        if (intentionalDisconnect) return
        val dev = _currentDevice.value ?: credentialStore.getSavedDevices().firstOrNull() ?: return
        if (_currentDevice.value == null) {
            _currentDevice.value = dev
        }

        if (!isSessionHealthy()) {
            val state = _connectionState.value
            if (state !is ConnectionState.Connecting) {
                TvLogger.i(TvLogger.TAG_REMOTE, "reconnectIfDisconnected: Session unhealthy (state=${state::class.simpleName}), requesting reconnect to ${dev.name}")
                requestReconnect(reason = "reconnectIfDisconnected", immediate = true)
            }
        }
    }

    override fun onNetworkLost() {
        if (intentionalDisconnect) return
        val current = _connectionState.value
        TvLogger.w(TvLogger.TAG_REMOTE, "Active network lost. Invalidating session (currently ${current::class.simpleName}).")

        synchronized(reconnectLock) {
            cancelScheduledReconnectInternal()
            connectionAttemptJob?.cancel()
            connectionAttemptJob = null
        }

        val sessionId = sessionCounter.incrementAndGet()
        activeSessionId = sessionId

        scope.launch(ioDispatcher) {
            connectionMutex.withLock {
                tearDownSessionInternal(closeTransport = true)
                recordDisconnectReason("Network lost")
                updateRemoteState(RemoteState.DISCONNECTED)
                if (_currentDevice.value != null) {
                    _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
                updateDiagnostics()
            }
        }
    }

    override fun onNetworkRestored() {
        if (intentionalDisconnect) return
        val dev = _currentDevice.value ?: credentialStore.getSavedDevices().firstOrNull() ?: return
        if (_currentDevice.value == null) {
            _currentDevice.value = dev
        }
        TvLogger.i(TvLogger.TAG_REMOTE, "Network restored. Verifying remote connection to ${dev.name}.")

        if (!isSessionHealthy()) {
            requestReconnect(reason = "Network restored", immediate = true)
        }
    }

    override fun onAppResume() {
        if (intentionalDisconnect) return
        if (_currentDevice.value == null) return

        if (isSessionHealthy()) {
            TvLogger.d(TvLogger.TAG_REMOTE, "App resumed; current remote session is healthy, skipping reconnect.")
        } else {
            TvLogger.i(TvLogger.TAG_REMOTE, "App resumed with unhealthy or disconnected state (${_connectionState.value::class.simpleName}); reconnecting.")
            requestReconnect(reason = "App resumed", immediate = true)
        }
    }

    private fun isSessionHealthy(now: Long = timeProvider()): Boolean {
        val state = _connectionState.value
        val t = transport ?: return false
        if (state !is ConnectionState.Ready) return false
        if (_remoteState.value != RemoteState.READY) return false
        if (activeSessionId <= 0L) return false
        if (!t.isConnected) return false
        if (lastReceivedFrameTimeMs <= 0L) return false
        if ((now - lastReceivedFrameTimeMs) >= (PING_INTERVAL_MS + PING_DEADLINE_MS)) return false
        if (isPingOutstanding && (now - lastPingSentTimeMs) > PING_DEADLINE_MS) return false
        return true
    }

    private fun requestReconnect(
        reason: String,
        immediate: Boolean = false,
        fromConnectFailure: Boolean = false
    ) {
        if (intentionalDisconnect) return
        val device = _currentDevice.value ?: return

        synchronized(reconnectLock) {
            recordDisconnectReason(reason)

            if (!fromConnectFailure && isConnectingOrHandshaking) {
                coalescedReconnectCount++
                updateDiagnostics()
                TvLogger.d(TvLogger.TAG_REMOTE, "Coalescing reconnect '$reason' with in-progress connection attempt")
                return
            }

            val existing = reconnectJob
            if (!fromConnectFailure && existing?.isActive == true) {
                if (reconnectIsImmediate || !immediate) {
                    coalescedReconnectCount++
                    updateDiagnostics()
                    TvLogger.d(TvLogger.TAG_REMOTE, "Coalescing duplicate reconnect trigger '$reason'")
                    return
                }
                existing.cancel()
            } else if (fromConnectFailure && existing?.isActive == true) {
                existing.cancel()
            }

            reconnectIsImmediate = immediate
            reconnectJob = scope.launch(ioDispatcher) {
                val attempt = retryCount + 1
                if (!immediate && attempt > 0) {
                    val delayIndex = (attempt - 1).coerceAtMost(BACKOFF_DELAYS.size - 1)
                    val baseDelay = BACKOFF_DELAYS[delayIndex]
                    val jitter = (baseDelay * (0.8 + 0.4 * Math.random())).toLong()

                    if (attempt >= 5) {
                        _connectionState.value = ConnectionState.Failed(
                            errorCode = "ERR_REMOTE_CONNECT",
                            userMessage = "TV offline or sleeping. Retrying...",
                            technicalDetail = reason
                        )
                    } else {
                        _connectionState.value = ConnectionState.Reconnecting(attempt = attempt, nextDelayMs = jitter)
                    }
                    updateDiagnostics()
                    TvLogger.i(TvLogger.TAG_REMOTE, "Reconnection scheduled in ${jitter}ms (attempt $attempt, reason: $reason)")
                    delay(jitter)
                }

                if (!intentionalDisconnect) {
                    performConnect(device, attempt)
                }
            }
        }
    }

    private fun cancelScheduledReconnectInternal() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectIsImmediate = false
    }

    private suspend fun performConnect(device: TvDevice, currentRetry: Int) = connectionMutex.withLock {
        if (intentionalDisconnect) return@withLock

        if (isSessionHealthy() && _currentDevice.value?.id == device.id) {
            TvLogger.d(TvLogger.TAG_REMOTE, "Already healthy and connected to ${device.name}; skipping redundant connect")
            return@withLock
        }

        val sessionId = sessionCounter.incrementAndGet()
        activeSessionId = sessionId
        isConnectingOrHandshaking = true

        tearDownSessionInternal(closeTransport = true)

        try {
            retryCount = currentRetry
            updateRemoteState(RemoteState.CONNECTING)
            _connectionState.value = ConnectionState.Connecting(device.host, device.remotePort)
            updateDiagnostics()
            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Connecting remote session to ${device.name} @ ${device.host}:${device.remotePort} (attempt $currentRetry)")

            val identity = credentialStore.getOrCreateIdentity(device.id)
            val trans = transportFactory(device.host, device.remotePort, identity)
            transport = trans

            trans.connect()
            if (activeSessionId != sessionId) {
                trans.close()
                return@withLock
            }

            updateRemoteState(RemoteState.TLS_CONNECTED)
            _connectionState.value = ConnectionState.Authenticating
            updateDiagnostics()

            val reader = trans.frameReader ?: throw IOException("Failed to open remote input stream")
            val writer = trans.frameWriter ?: throw IOException("Failed to open remote output stream")

            configureReceivedDeferred = CompletableDeferred()
            setActiveReceivedDeferred = CompletableDeferred()

            startReaderLoop(reader, device, sessionId)

            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Sending RemoteConfigure (code1=622)")
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
            sendRemoteMessageDirect(writer, configureMsg, sessionId)
            updateRemoteState(RemoteState.CONFIGURE_SENT)

            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Waiting for RemoteConfigure from TV...")
            withTimeout(HANDSHAKE_CONFIGURE_TIMEOUT_MS) {
                configureReceivedDeferred?.await()
            }
            updateRemoteState(RemoteState.CONFIGURED)

            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Sending RemoteSetActive (active=622)")
            val setActiveMsg = RemoteMessage(
                remote_set_active = RemoteSetActive(active = 622)
            )
            sendRemoteMessageDirect(writer, setActiveMsg, sessionId)
            updateRemoteState(RemoteState.SET_ACTIVE_SENT)

            withTimeoutOrNull(HANDSHAKE_SET_ACTIVE_TIMEOUT_MS) {
                setActiveReceivedDeferred?.await()
            }

            if (activeSessionId != sessionId) {
                trans.close()
                return@withLock
            }

            // Session is READY! Record valid handshake timestamp only from TV handshake frames.
            updateRemoteState(RemoteState.READY)
            _connectionState.value = ConnectionState.Ready(device)
            retryCount = 0
            val now = timeProvider()
            lastSuccessfulConnectTimeMs = now
            if (lastReceivedFrameTimeMs == 0L || lastReceivedFrameTimeMs < lastSuccessfulConnectTimeMs) {
                lastReceivedFrameTimeMs = now
            }
            isPingOutstanding = false
            outstandingPingVal = 0
            isConnectingOrHandshaking = false
            updateDiagnostics()

            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Remote session READY! TV control active.")

            startHealthMonitor(sessionId)
            drainCommandQueue(writer, sessionId)

        } catch (e: Exception) {
            isConnectingOrHandshaking = false
            TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Connection attempt failed: ${e.message}")
            if (activeSessionId == sessionId) {
                tearDownSessionInternal(closeTransport = true)
                updateRemoteState(RemoteState.ERROR)
                updateDiagnostics()
                requestReconnect(
                    reason = "Connection attempt failed: ${e.message}",
                    immediate = false,
                    fromConnectFailure = true
                )
            }
        } finally {
            isConnectingOrHandshaking = false
        }
    }

    private fun startReaderLoop(reader: ProtoFrameReader, device: TvDevice, sessionId: Long) {
        readLoopJob?.cancel()
        readLoopJob = scope.launch(ioDispatcher) {
            try {
                while (isActive && activeSessionId == sessionId && transport?.isConnected == true) {
                    try {
                        val frameBytes = reader.readNextFrame()
                        val msg = RemoteMessage.ADAPTER.decode(frameBytes)
                        if (activeSessionId != sessionId) break

                        // Only update lastReceivedFrameTimeMs when a valid incoming TV frame is decoded
                        lastReceivedFrameTimeMs = timeProvider()
                        updateDiagnostics()
                        handleIncomingMessage(msg, sessionId)
                    } catch (e: SocketTimeoutException) {
                        if (activeSessionId != sessionId) break
                        delay(200L)
                    }
                }
            } catch (e: Exception) {
                if (activeSessionId == sessionId && !intentionalDisconnect) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Reader loop terminated: ${e.message}")
                    try {
                        transport?.close()
                    } catch (_: Exception) {}
                    updateRemoteState(RemoteState.DISCONNECTED)
                    _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                    requestReconnect(reason = "Reader loop: ${e.message}", immediate = false)
                }
            }
        }
    }

    private suspend fun handleIncomingMessage(msg: RemoteMessage, sessionId: Long) {
        if (activeSessionId != sessionId) return

        when {
            msg.remote_configure != null -> {
                TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: type=REMOTE_CONFIGURE")
                configureReceivedDeferred?.complete(Unit)
            }

            msg.remote_set_active != null -> {
                TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: type=REMOTE_SET_ACTIVE")
                setActiveReceivedDeferred?.complete(Unit)
            }

            msg.remote_start != null -> {
                val started = msg.remote_start.started ?: false
                TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: RemoteStart started=$started")
                setActiveReceivedDeferred?.complete(Unit)
            }

            msg.remote_ping_request != null -> {
                val pingVal = msg.remote_ping_request.val1 ?: 1
                TvLogger.d(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: PING_REQUEST (val1=$pingVal), responding PONG")
                val pongMsg = RemoteMessage(
                    remote_ping_response = RemotePingResponse(val1 = pingVal)
                )
                scope.launch(ioDispatcher) {
                    try {
                        sendRemoteMessage(pongMsg, sessionId)
                    } catch (e: Exception) {
                        TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Failed to send ping response: ${e.message}")
                    }
                }
            }

            msg.remote_ping_response != null -> {
                val pongVal = msg.remote_ping_response.val1 ?: 0
                TvLogger.d(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: PING_RESPONSE received (val1=$pongVal, expected=$outstandingPingVal, isOutstanding=$isPingOutstanding)")
                if (isPingOutstanding && pongVal == outstandingPingVal) {
                    isPingOutstanding = false
                    outstandingPingVal = 0
                    updateDiagnostics()
                } else {
                    TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Ignoring mismatched or stale PING_RESPONSE (val1=$pongVal, expected=$outstandingPingVal)")
                }
            }

            msg.remote_error != null -> {
                val err = msg.remote_error
                TvLogger.e(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: RemoteError value=${err.value} message=${err.message}")
                try {
                    transport?.close()
                } catch (_: Exception) {}
                updateRemoteState(RemoteState.ERROR)
                requestReconnect(reason = "TV sent RemoteError: ${err.message}", immediate = true)
            }

            msg.remote_ime_key_inject != null -> {
                val ime = msg.remote_ime_key_inject
                val text = ime.text ?: ""
                TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: IME text='$text'")
                _imeActive.value = true
                _lastImeText.value = text
            }

            else -> {
                TvLogger.d(TvLogger.TAG_REMOTE, "[$sessionId] REMOTE RX: type=UNKNOWN")
            }
        }
    }

    private fun startHealthMonitor(sessionId: Long) {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch(ioDispatcher) {
            while (isActive && activeSessionId == sessionId && transport?.isConnected == true) {
                delay(2000L)
                if (activeSessionId != sessionId || _remoteState.value != RemoteState.READY) break

                val now = timeProvider()

                if (isPingOutstanding) {
                    if (now - lastPingSentTimeMs > PING_DEADLINE_MS) {
                        TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Health monitor: TV ping response deadline exceeded (${now - lastPingSentTimeMs}ms > ${PING_DEADLINE_MS}ms). Marking connection dead.")
                        tearDownSessionInternal(closeTransport = true)
                        updateRemoteState(RemoteState.DISCONNECTED)
                        _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                        updateDiagnostics()
                        requestReconnect(reason = "Ping response deadline exceeded", immediate = true)
                        break
                    }
                } else {
                    if (now - lastReceivedFrameTimeMs >= PING_INTERVAL_MS) {
                        val seq = pingSeqCounter.getAndIncrement()
                        TvLogger.d(TvLogger.TAG_REMOTE, "[$sessionId] Health monitor: Sending keepalive ping (seq=$seq)")
                        val pingMsg = RemoteMessage(
                            remote_ping_request = RemotePingRequest(val1 = seq)
                        )
                        lastPingSentTimeMs = now
                        isPingOutstanding = true
                        outstandingPingVal = seq
                        updateDiagnostics()

                        try {
                            sendRemoteMessage(pingMsg, sessionId)
                        } catch (e: Exception) {
                            TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Health monitor: Failed to write ping: ${e.message}")
                            tearDownSessionInternal(closeTransport = true)
                            updateRemoteState(RemoteState.DISCONNECTED)
                            _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                            updateDiagnostics()
                            requestReconnect(reason = "Failed to write ping: ${e.message}", immediate = true)
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendRemoteMessageDirect(writer: ProtoFrameWriter, message: RemoteMessage, sessionId: Long) {
        writeMutex.withLock {
            if (activeSessionId != sessionId) throw IOException("Session expired")
            val payload = message.encode()
            writer.writeFrame(payload)
            lastOutboundFrameTimeMs = timeProvider()
        }
    }

    private suspend fun sendRemoteMessage(message: RemoteMessage, sessionId: Long) {
        writeMutex.withLock {
            if (activeSessionId != sessionId) throw IOException("Session expired")
            val trans = transport ?: throw IOException("Transport unavailable")
            if (!trans.isConnected) throw IOException("Transport disconnected")
            val writer = trans.frameWriter ?: throw IOException("Writer unavailable")
            val payload = message.encode()
            writer.writeFrame(payload)
            lastOutboundFrameTimeMs = timeProvider()
        }
    }

    override fun sendKey(keyCode: Int, direction: Direction) {
        val keyName = AndroidTvKeyCodes.getKeyName(keyCode)
        TvLogger.d(TvLogger.TAG_REMOTE, "sendKey called: $keyName ($keyCode, ${direction.name})")

        when (direction) {
            Direction.START_LONG -> currentlyHeldKeyCodes.add(keyCode)
            Direction.END_LONG -> currentlyHeldKeyCodes.remove(keyCode)
            Direction.SHORT -> currentlyHeldKeyCodes.remove(keyCode)
            else -> {}
        }

        val cmd = QueuedRemoteCommand.KeyCommand(
            keyCode = keyCode,
            direction = direction,
            createdAtMs = timeProvider()
        )
        enqueueAndDispatchCommand(cmd)
    }

    override fun sendImeText(text: String) {
        sendImeTextWithFallback(text, useKeyCodesFallback = false)
    }

    override fun sendImeTextWithFallback(text: String, useKeyCodesFallback: Boolean) {
        if (useKeyCodesFallback) {
            TvLogger.i(TvLogger.TAG_REMOTE, "REMOTE TX: DIRECT_KEYCODES text='$text'")
            scope.launch(ioDispatcher) {
                for (c in text) {
                    val keyCode = AndroidTvKeyCodes.getKeyCodeForChar(c)
                    if (keyCode != null) {
                        sendKey(keyCode, Direction.SHORT)
                        delay(35)
                    }
                }
            }
            return
        }

        val cmd = QueuedRemoteCommand.ImeCommand(text = text, createdAtMs = timeProvider())
        enqueueAndDispatchCommand(cmd)
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
        val cmd = QueuedRemoteCommand.AppLinkCommand(appLink = appLink, createdAtMs = timeProvider())
        enqueueAndDispatchCommand(cmd)
    }

    private fun enqueueAndDispatchCommand(command: QueuedRemoteCommand) {
        commandQueue.enqueue(command)
        updateDiagnostics()

        val isReady = isSessionHealthy()
        if (isReady) {
            val sessionId = activeSessionId
            scope.launch(ioDispatcher) {
                val trans = transport
                val writer = trans?.frameWriter
                if (writer != null && activeSessionId == sessionId && _remoteState.value == RemoteState.READY && trans.isConnected) {
                    drainCommandQueue(writer, sessionId)
                } else if (!intentionalDisconnect) {
                    reconnectIfDisconnected()
                }
            }
        } else if (!intentionalDisconnect) {
            reconnectIfDisconnected()
        }
    }

    private suspend fun drainCommandQueue(writer: ProtoFrameWriter, sessionId: Long) {
        writeMutex.withLock {
            while (true) {
                if (activeSessionId != sessionId || _remoteState.value != RemoteState.READY || transport?.isConnected != true) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Queue draining halted: session or transport not ready")
                    if (activeSessionId == sessionId && !intentionalDisconnect) {
                        try {
                            transport?.close()
                        } catch (_: Exception) {}
                        updateRemoteState(RemoteState.DISCONNECTED)
                        _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                        requestReconnect(reason = "Transport disconnected before command write", immediate = true)
                    }
                    break
                }

                val cmd = commandQueue.peekNextValid() ?: break

                if (commandQueue.isCommandExpired(cmd)) {
                    TvLogger.d(TvLogger.TAG_REMOTE, "[$sessionId] Discarding expired command (id=${cmd.commandId})")
                    commandQueue.removeHead(cmd)
                    continue
                }

                val message = when (cmd) {
                    is QueuedRemoteCommand.KeyCommand -> RemoteMessage(
                        remote_key_inject = RemoteKeyInject(key_code = cmd.keyCode, direction = cmd.direction)
                    )
                    is QueuedRemoteCommand.ImeCommand -> RemoteMessage(
                        remote_ime_key_inject = RemoteImeKeyInject(app_info = 0, text = cmd.text)
                    )
                    is QueuedRemoteCommand.AppLinkCommand -> RemoteMessage(
                        remote_app_link_launch_request = RemoteAppLinkLaunchRequest(app_link = cmd.appLink)
                    )
                }

                try {
                    val payload = message.encode()
                    writer.writeFrame(payload)

                    commandQueue.removeHead(cmd)
                    lastOutboundFrameTimeMs = timeProvider()

                    when (cmd) {
                        is QueuedRemoteCommand.KeyCommand -> {
                            val keyName = AndroidTvKeyCodes.getKeyName(cmd.keyCode)
                            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Delivered key $keyName (${cmd.keyCode}, ${cmd.direction.name})")
                        }
                        is QueuedRemoteCommand.ImeCommand -> {
                            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Delivered IME text '${cmd.text}'")
                        }
                        is QueuedRemoteCommand.AppLinkCommand -> {
                            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Delivered AppLink '${cmd.appLink}'")
                        }
                    }

                    delay(15)
                } catch (e: Exception) {
                    val bytesWrittenStarted = (e as? FrameWriteException)?.bytesWrittenStarted ?: true
                    val desc = describeCommand(cmd)
                    if (bytesWrittenStarted) {
                        val isEndLong = cmd is QueuedRemoteCommand.KeyCommand && cmd.direction == Direction.END_LONG
                        uncertainDeliveryCount++
                        if (isEndLong) {
                            lastUncertainCommand = "$desc [kept for release]"
                            TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Mid-write failure for release command $desc; keeping in queue to prevent stuck key: ${e.message}")
                        } else {
                            commandQueue.removeHead(cmd)
                            lastUncertainCommand = desc
                            TvLogger.e(TvLogger.TAG_REMOTE, "[$sessionId] Uncertain delivery for $desc (bytes hit stream); NOT replaying blindly", e)
                        }
                    } else {
                        TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Pre-write failure for $desc; keeping command queued for retry: ${e.message}")
                    }

                    try {
                        transport?.close()
                    } catch (_: Exception) {}
                    updateRemoteState(RemoteState.DISCONNECTED)
                    _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                    requestReconnect(reason = "Command transmission failed ($desc): ${e.message}", immediate = true)
                    break
                }
            }
            updateDiagnostics()
        }
    }

    private fun describeCommand(cmd: QueuedRemoteCommand): String = when (cmd) {
        is QueuedRemoteCommand.KeyCommand -> "${AndroidTvKeyCodes.getKeyName(cmd.keyCode)}(${cmd.direction.name})"
        is QueuedRemoteCommand.ImeCommand -> "IME(len=${cmd.text.length})"
        is QueuedRemoteCommand.AppLinkCommand -> "AppLink(${cmd.appLink})"
    }

    private fun recordDisconnectReason(reason: String) {
        lastDisconnectReason = reason
        if (recentReconnectReasons.lastOrNull() != reason) {
            if (recentReconnectReasons.size >= MAX_REASON_HISTORY) {
                recentReconnectReasons.removeFirst()
            }
            recentReconnectReasons.addLast(reason)
        }
    }

    private fun tearDownSessionInternal(closeTransport: Boolean) {
        healthMonitorJob?.cancel()
        healthMonitorJob = null

        readLoopJob?.cancel()
        readLoopJob = null

        configureReceivedDeferred?.cancel()
        setActiveReceivedDeferred?.cancel()
        configureReceivedDeferred = null
        setActiveReceivedDeferred = null

        isPingOutstanding = false
        outstandingPingVal = 0
        currentlyHeldKeyCodes.clear()

        if (closeTransport) {
            try {
                transport?.close()
            } catch (e: Exception) {
                TvLogger.w(TvLogger.TAG_REMOTE, "Exception closing transport: ${e.message}")
            }
            transport = null
        }

        _imeActive.value = false
        _lastImeText.value = ""
    }

    private fun updateRemoteState(newState: RemoteState) {
        _remoteState.value = newState
        updateDiagnostics()
        TvLogger.d(TvLogger.TAG_REMOTE, "REMOTE STATE -> ${newState.name}")
    }

    private fun updateDiagnostics() {
        val dev = _currentDevice.value
        _diagnostics.value = ConnectionDiagnostics(
            selectedDeviceName = dev?.name,
            selectedDeviceHost = dev?.host,
            connectionStateName = _connectionState.value::class.simpleName ?: "Unknown",
            sessionId = activeSessionId,
            reconnectAttemptCount = retryCount,
            coalescedReconnectCount = coalescedReconnectCount,
            lastSuccessfulConnectTimeMs = lastSuccessfulConnectTimeMs,
            lastReceivedResponseTimeMs = lastReceivedFrameTimeMs,
            lastOutboundFrameTimeMs = lastOutboundFrameTimeMs,
            lastDisconnectReason = lastDisconnectReason,
            recentReconnectReasons = recentReconnectReasons.toList(),
            pendingCommandCount = commandQueue.size,
            isPingOutstanding = isPingOutstanding,
            lastPingSentTimeMs = lastPingSentTimeMs,
            droppedCommandCount = commandQueue.expiredCount,
            evictedCommandCount = commandQueue.evictedCount,
            lastQueueExpirationTimeMs = commandQueue.lastExpirationTimeMs,
            uncertainDeliveryCount = uncertainDeliveryCount,
            lastUncertainCommand = lastUncertainCommand
        )
    }
}
