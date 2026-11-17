package com.gala.motetv.remote

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.pairing.TvIdentity
import com.gala.motetv.protocol.androidtv.AndroidTvTransport
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
import kotlinx.coroutines.yield
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
    private val commandQueue = RemoteCommandQueue()

    // Concurrency & Session Management
    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()
    private val sessionCounter = AtomicLong(0)
    private var activeSessionId = 0L

    private var transport: TvTransport? = null
    private var readLoopJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionAttemptJob: Job? = null

    private var retryCount = 0
    private var intentionalDisconnect = false

    // Health monitoring state
    private var lastReceivedFrameTimeMs: Long = 0L
    private var lastOutboundFrameTimeMs: Long = 0L
    private var lastPingSentTimeMs: Long = 0L
    private var isPingOutstanding: Boolean = false
    private var outstandingPingVal: Int = 0
    private val pingSeqCounter = AtomicInteger(1)

    // Key hold tracking to prevent logical stuck keys on disconnects or releases
    private val currentlyHeldKeyCodes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private var lastSuccessfulConnectTimeMs: Long = 0L
    private var lastDisconnectReason: String = "None"

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

        // Cancel any pending reconnect job
        cancelScheduledReconnect()

        connectionAttemptJob?.cancel()
        connectionAttemptJob = scope.launch(ioDispatcher) {
            performConnect(device, currentRetry = 0)
        }
    }

    override fun disconnect() {
        intentionalDisconnect = true
        cancelScheduledReconnect()
        connectionAttemptJob?.cancel()

        scope.launch(ioDispatcher) {
            connectionMutex.withLock {
                val sessionId = sessionCounter.incrementAndGet()
                activeSessionId = sessionId

                tearDownSessionInternal(closeTransport = true)
                commandQueue.clear()

                updateRemoteState(RemoteState.DISCONNECTED)
                _connectionState.value = ConnectionState.Disconnected
                _currentDevice.value = null
                lastDisconnectReason = "User requested disconnect"
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

        val state = _connectionState.value
        val isHealthy = state is ConnectionState.Ready &&
                transport?.isConnected == true &&
                (timeProvider() - lastReceivedFrameTimeMs) < (PING_INTERVAL_MS + PING_DEADLINE_MS)

        if (!isHealthy && state !is ConnectionState.Connecting) {
            TvLogger.i(TvLogger.TAG_REMOTE, "reconnectIfDisconnected: State is ${state::class.simpleName}, requesting reconnect to ${dev.name}")
            requestReconnect(reason = "reconnectIfDisconnected", immediate = true)
        }
    }

    override fun onNetworkLost() {
        if (intentionalDisconnect) return
        val current = _connectionState.value
        TvLogger.w(TvLogger.TAG_REMOTE, "Active network lost. Invalidating session (currently ${current::class.simpleName}).")

        // Cancel current session immediately
        val sessionId = sessionCounter.incrementAndGet()
        activeSessionId = sessionId

        scope.launch {
            connectionMutex.withLock {
                tearDownSessionInternal(closeTransport = true)
                lastDisconnectReason = "Network lost"
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
        TvLogger.i(TvLogger.TAG_REMOTE, "Network restored. Triggering reconnect to ${dev.name}.")

        val isHealthy = _connectionState.value is ConnectionState.Ready &&
                transport?.isConnected == true &&
                (timeProvider() - lastReceivedFrameTimeMs) < (PING_INTERVAL_MS + PING_DEADLINE_MS)

        if (!isHealthy) {
            requestReconnect(reason = "Network restored", immediate = true)
        }
    }

    override fun onAppResume() {
        if (intentionalDisconnect) return
        val dev = _currentDevice.value ?: return

        val now = timeProvider()
        val isHealthy = _connectionState.value is ConnectionState.Ready &&
                transport?.isConnected == true &&
                lastReceivedFrameTimeMs > 0L &&
                (now - lastReceivedFrameTimeMs) < (PING_INTERVAL_MS + PING_DEADLINE_MS)

        if (isHealthy) {
            TvLogger.d(TvLogger.TAG_REMOTE, "App resumed; current remote session is healthy, skipping reconnect.")
        } else {
            TvLogger.i(TvLogger.TAG_REMOTE, "App resumed with unhealthy or disconnected state (${_connectionState.value::class.simpleName}); reconnecting.")
            requestReconnect(reason = "App resumed", immediate = true)
        }
    }

    private fun requestReconnect(reason: String, immediate: Boolean = false) {
        if (intentionalDisconnect) return
        val device = _currentDevice.value ?: return

        lastDisconnectReason = reason
        cancelScheduledReconnect()

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

            performConnect(device, attempt)
        }
    }

    private fun cancelScheduledReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private suspend fun performConnect(device: TvDevice, currentRetry: Int) = connectionMutex.withLock {
        if (intentionalDisconnect) return@withLock

        // Check if current connection is already ready and connected to this device
        if (_connectionState.value is ConnectionState.Ready && transport?.isConnected == true && activeSessionId > 0) {
            TvLogger.d(TvLogger.TAG_REMOTE, "Already ready and connected to ${device.name}; skipping redundant connect")
            return@withLock
        }

        val sessionId = sessionCounter.incrementAndGet()
        activeSessionId = sessionId

        // Completely tear down previous transport and listeners
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

            // Step 1: Start single reader loop for this session
            startReaderLoop(reader, device, sessionId)

            // Step 2: TX RemoteConfigure
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

            // Step 3: Wait for RX RemoteConfigure from TV
            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Waiting for RemoteConfigure from TV...")
            withTimeout(HANDSHAKE_CONFIGURE_TIMEOUT_MS) {
                configureReceivedDeferred?.await()
            }
            updateRemoteState(RemoteState.CONFIGURED)

            // Step 4: TX RemoteSetActive
            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Sending RemoteSetActive (active=622)")
            val setActiveMsg = RemoteMessage(
                remote_set_active = RemoteSetActive(active = 622)
            )
            sendRemoteMessageDirect(writer, setActiveMsg, sessionId)
            updateRemoteState(RemoteState.SET_ACTIVE_SENT)

            // Step 5: Optional wait for RemoteSetActive / RemoteStart ack
            withTimeoutOrNull(HANDSHAKE_SET_ACTIVE_TIMEOUT_MS) {
                setActiveReceivedDeferred?.await()
            }

            if (activeSessionId != sessionId) {
                trans.close()
                return@withLock
            }

            // Step 6: Session is READY!
            updateRemoteState(RemoteState.READY)
            _connectionState.value = ConnectionState.Ready(device)
            retryCount = 0 // Reset retry count strictly on successful protocol session!
            lastSuccessfulConnectTimeMs = timeProvider()
            lastReceivedFrameTimeMs = timeProvider()
            isPingOutstanding = false
            updateDiagnostics()

            TvLogger.i(TvLogger.TAG_REMOTE, "[$sessionId] Remote session READY! TV control active.")

            // Start health monitor for this session
            startHealthMonitor(sessionId)

            // Drain queued commands in original order
            drainCommandQueue(writer, sessionId)

        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Connection attempt failed: ${e.message}")
            if (activeSessionId == sessionId) {
                tearDownSessionInternal(closeTransport = true)
                updateRemoteState(RemoteState.ERROR)
                updateDiagnostics()
                requestReconnect(reason = "Connection attempt failed: ${e.message}", immediate = false)
            }
        }
    }

    private fun startReaderLoop(reader: ProtoFrameReader, device: TvDevice, sessionId: Long) {
        readLoopJob?.cancel()
        readLoopJob = scope.launch(ioDispatcher) {
            try {
                while (isActive && activeSessionId == sessionId && transport?.isConnected == true) {
                    try {
                        val frameBytes = reader.readNextFrame()
                        lastReceivedFrameTimeMs = timeProvider()
                        updateDiagnostics()

                        val msg = RemoteMessage.ADAPTER.decode(frameBytes)
                        handleIncomingMessage(msg, sessionId)
                    } catch (e: SocketTimeoutException) {
                        // Harmless idle timeout while socket is waiting for TV frames
                        if (activeSessionId != sessionId) break
                        delay(200L)
                    }
                }
            } catch (e: Exception) {
                if (activeSessionId == sessionId && !intentionalDisconnect) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Reader loop terminated: ${e.message}")
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
                scope.launch(Dispatchers.IO) {
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
                    // Check if ping response deadline expired
                    if (now - lastPingSentTimeMs > PING_DEADLINE_MS) {
                        TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Health monitor: TV ping response deadline exceeded (${now - lastPingSentTimeMs}ms > ${PING_DEADLINE_MS}ms). Marking connection dead.")
                        tearDownSessionInternal(closeTransport = true)
                        _connectionState.value = ConnectionState.Reconnecting(attempt = retryCount + 1, nextDelayMs = 0L)
                        updateDiagnostics()
                        requestReconnect(reason = "Ping response deadline exceeded", immediate = true)
                        break
                    }
                } else {
                    // Send ping if idle beyond PING_INTERVAL_MS
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
                            isPingOutstanding = false
                            outstandingPingVal = 0
                            tearDownSessionInternal(closeTransport = true)
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
            val writer = trans.frameWriter ?: throw IOException("Writer unavailable")
            val payload = message.encode()
            writer.writeFrame(payload)
            lastOutboundFrameTimeMs = timeProvider()
        }
    }

    override fun sendKey(keyCode: Int, direction: Direction) {
        val keyName = AndroidTvKeyCodes.getKeyName(keyCode)
        TvLogger.d(TvLogger.TAG_REMOTE, "sendKey called: $keyName ($keyCode, ${direction.name})")

        // Track key press state for START_LONG and END_LONG
        when (direction) {
            Direction.START_LONG -> currentlyHeldKeyCodes.add(keyCode)
            Direction.END_LONG -> currentlyHeldKeyCodes.remove(keyCode)
            Direction.SHORT -> currentlyHeldKeyCodes.remove(keyCode)
            else -> {}
        }

        val cmd = QueuedRemoteCommand.KeyCommand(keyCode, direction)
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

        val cmd = QueuedRemoteCommand.ImeCommand(text)
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
        val cmd = QueuedRemoteCommand.AppLinkCommand(appLink)
        enqueueAndDispatchCommand(cmd)
    }

    /**
     * Unified, serialized command dispatch path.
     * Enqueues the command into the bounded queue. If session is READY, asynchronously drains
     * the queue through the serialized writer. If disconnected, triggers reconnection.
     */
    private fun enqueueAndDispatchCommand(command: QueuedRemoteCommand) {
        val queued = commandQueue.enqueue(command)
        updateDiagnostics()

        val isReady = _connectionState.value is ConnectionState.Ready &&
                _remoteState.value == RemoteState.READY &&
                transport?.isConnected == true

        if (isReady) {
            val sessionId = activeSessionId
            scope.launch(ioDispatcher) {
                val trans = transport
                val writer = trans?.frameWriter
                if (writer != null && activeSessionId == sessionId && _remoteState.value == RemoteState.READY) {
                    drainCommandQueue(writer, sessionId)
                } else if (!intentionalDisconnect) {
                    reconnectIfDisconnected()
                }
            }
        } else if (!intentionalDisconnect) {
            reconnectIfDisconnected()
        }
    }

    /**
     * Drains the command queue one-by-one under writeMutex lock.
     * Each command is ONLY removed from the queue AFTER successful transmission.
     * If write fails, the command remains in queue (or is discarded if expired)
     * and delivery aborts safely without replaying unattempted commands.
     */
    private suspend fun drainCommandQueue(writer: ProtoFrameWriter, sessionId: Long) {
        writeMutex.withLock {
            while (true) {
                if (activeSessionId != sessionId || _remoteState.value != RemoteState.READY) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "[$sessionId] Queue draining halted: session no longer ready")
                    break
                }

                val cmd = commandQueue.peekNextValid() ?: break

                if (cmd.isExpired) {
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

                    // Successfully transmitted: remove from queue head
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

                    delay(15) // Pacing between queued commands
                } catch (e: Exception) {
                    TvLogger.e(TvLogger.TAG_REMOTE, "[$sessionId] Transmission failed for command ${cmd.commandId}: ${e.message}", e)
                    // Do NOT remove from queue if delivery failed before transmission
                    // However, if write threw midway, delivery status is uncertain
                    requestReconnect(reason = "Command transmission failed: ${e.message}", immediate = true)
                    break
                }
            }
            updateDiagnostics()
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
            lastSuccessfulConnectTimeMs = lastSuccessfulConnectTimeMs,
            lastReceivedResponseTimeMs = lastReceivedFrameTimeMs,
            lastOutboundFrameTimeMs = lastOutboundFrameTimeMs,
            lastDisconnectReason = lastDisconnectReason,
            pendingCommandCount = commandQueue.size,
            isPingOutstanding = isPingOutstanding,
            lastPingSentTimeMs = lastPingSentTimeMs,
            droppedCommandCount = commandQueue.expiredCount,
            evictedCommandCount = commandQueue.evictedCount
        )
    }
}
