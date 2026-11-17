package com.gala.motetv.remote

import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.protocol.androidtv.ProtoFrameWriter
import com.gala.motetv.protocol.androidtv.TvTransport
import com.gala.motetv.storage.TvCredentialStore
import com.google.android.apps.tv.remote.protocol.Direction
import com.google.android.apps.tv.remote.protocol.RemoteConfigure
import com.google.android.apps.tv.remote.protocol.RemoteKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteMessage
import com.google.android.apps.tv.remote.protocol.RemotePingRequest
import com.google.android.apps.tv.remote.protocol.RemotePingResponse
import com.google.android.apps.tv.remote.protocol.RemoteSetActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit test suite verifying all connectivity, command queueing, serialized delivery,
 * heartbeat matching, long-press release, and diagnostic requirements.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvRemoteManagerConnectionTest {

    private val testDevice = TvDevice(
        id = "mi_stick_test",
        name = "Xiaomi Mi TV Stick",
        host = "192.168.1.150",
        pairingPort = 6467,
        remotePort = 6466
    )

    private val altDevice = TvDevice(
        id = "bravia_test",
        name = "Sony Bravia",
        host = "192.168.1.180",
        pairingPort = 6467,
        remotePort = 6466
    )

    private lateinit var credentialStore: TvCredentialStore

    @Before
    fun setup() {
        credentialStore = TvCredentialStore(null)
        val identity = credentialStore.getOrCreateIdentity(testDevice.id)
        credentialStore.setDevicePaired(testDevice.id, identity.certificate, paired = true)
        credentialStore.setDevicePaired(altDevice.id, identity.certificate, paired = true)
    }

    /**
     * Fake TvTransport to simulate TLS connections, incoming frames, write errors,
     * and decoded RemoteMessage tracking.
     */
    class FakeTvTransport(
        val host: String,
        val port: Int,
        var autoRespondHandshake: Boolean = true,
        var failOnConnect: Boolean = false,
        var failOnWrite: Boolean = false,
        var failOnNthWrite: Int = -1, // 1-indexed write count to fail mid-frame
        var failBeforeFirstByte: Boolean = false
    ) : TvTransport {
        val isOpen = AtomicBoolean(false)
        val sentFrames = CopyOnWriteArrayList<ByteArray>()
        val sentMessages = CopyOnWriteArrayList<RemoteMessage>()
        var connectCallCount = AtomicInteger(0)
        var closeCallCount = AtomicInteger(0)
        private var writeAttemptCount = AtomicInteger(0)

        private val incomingBytesQueue = java.util.concurrent.LinkedBlockingQueue<Int>()

        override var serverCertificate: X509Certificate? = null
        override var frameReader: ProtoFrameReader? = null
        override var frameWriter: ProtoFrameWriter? = null

        override val isConnected: Boolean
            get() = isOpen.get()

        override suspend fun connect() {
            connectCallCount.incrementAndGet()
            if (failOnConnect) {
                isOpen.set(false)
                throw IOException("Simulated TLS connection failure to $host:$port")
            }
            isOpen.set(true)

            val customInStream = object : java.io.InputStream() {
                override fun read(): Int {
                    if (!isOpen.get()) return -1
                    val b = incomingBytesQueue.poll()
                    return if (b != null) {
                        if (b == -1 || !isOpen.get()) -1 else b
                    } else {
                        throw SocketTimeoutException("Simulated socket idle timeout")
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (!isOpen.get()) return -1
                    val first = read()
                    if (first == -1) return -1
                    b[off] = first.toByte()
                    var count = 1
                    while (count < len) {
                        val next = incomingBytesQueue.poll() ?: break
                        if (next == -1) break
                        b[off + count] = next.toByte()
                        count++
                    }
                    return count
                }
            }
            frameReader = ProtoFrameReader(customInStream)

            val interceptingOut = object : ByteArrayOutputStream() {
                override fun write(b: Int) {
                    if (failBeforeFirstByte && writeAttemptCount.get() >= 2) {
                        throw IOException("Simulated pre-write failure before any byte sent")
                    }
                    super.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    val count = writeAttemptCount.incrementAndGet()
                    if (failOnWrite || (failOnNthWrite > 0 && count == failOnNthWrite)) {
                        throw IOException("Simulated write transmission failure (attempt=$count)")
                    }
                    super.write(b, off, len)
                }

                override fun flush() {
                    super.flush()
                    val bytes = toByteArray()
                    if (bytes.isNotEmpty()) {
                        sentFrames.add(bytes)
                        try {
                            val inStream = ByteArrayInputStream(bytes)
                            val len = ProtoFrameReader.readVarint32(inStream)
                            val payload = ByteArray(len)
                            inStream.read(payload)
                            val decoded = RemoteMessage.ADAPTER.decode(payload)
                            sentMessages.add(decoded)
                        } catch (_: Exception) {}
                        reset()
                    }
                }
            }
            frameWriter = ProtoFrameWriter(interceptingOut)

            if (autoRespondHandshake) {
                sendFrameToClient(RemoteMessage(remote_configure = RemoteConfigure(code1 = 622)))
                sendFrameToClient(RemoteMessage(remote_set_active = RemoteSetActive(active = 1)))
            }
        }

        fun sendFrameToClient(msg: RemoteMessage) {
            val bytes = RemoteMessage.ADAPTER.encode(msg)
            val lenBytes = ProtoFrameWriter.encodeVarint32(bytes.size)
            for (b in lenBytes) {
                incomingBytesQueue.offer(b.toInt() and 0xFF)
            }
            for (b in bytes) {
                incomingBytesQueue.offer(b.toInt() and 0xFF)
            }
        }

        override fun close() {
            closeCallCount.incrementAndGet()
            isOpen.set(false)
            incomingBytesQueue.offer(-1)
        }
    }

    // =========================================================================
    // Scenario 1: Normal connection and successful handshake transitions to READY
    // =========================================================================
    @Test
    fun test1_normalConnectionAndHandshake() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var createdTransport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { createdTransport = it }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()

            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            assertNotNull(createdTransport)
            assertTrue(createdTransport!!.isOpen.get())
            assertEquals(0, manager.connectionDiagnostics.value.reconnectAttemptCount)
            assertTrue(createdTransport!!.sentMessages.any { it.remote_configure != null })
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 2: Button press while READY sends immediately via serialized writer
    // =========================================================================
    @Test
    fun test2_buttonPressWhileReadySendsImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, Direction.SHORT)
            advanceUntilIdle()

            val keyMsg = transport!!.sentMessages.firstOrNull {
                it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_CENTER
            }
            assertNotNull("Expected DPAD_CENTER key injection frame to be transmitted", keyMsg)
            assertEquals(Direction.SHORT, keyMsg!!.remote_key_inject!!.direction)
            assertEquals(0, manager.connectionDiagnostics.value.pendingCommandCount)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 3: Button press while reconnecting queues command
    // =========================================================================
    @Test
    fun test3_buttonPressWhileReconnectingQueuesCommand() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port, failOnConnect = true)
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()

            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.SHORT)
            advanceUntilIdle()

            assertEquals(1, manager.connectionDiagnostics.value.pendingCommandCount)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 4: Queued command delivered after successful reconnection
    // =========================================================================
    @Test
    fun test4_queuedCommandDeliveredAfterReconnect() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transportCount = 0
        var secondTransport: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                transportCount++
                if (transportCount == 1) {
                    FakeTvTransport(host, port, failOnConnect = true)
                } else {
                    FakeTvTransport(host, port).also { secondTransport = it }
                }
            }
        )

        try {
            // Initial connect fails
            manager.connect(testDevice)
            advanceUntilIdle()

            // Press button while disconnected/reconnecting
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, Direction.SHORT)
            assertEquals(1, manager.connectionDiagnostics.value.pendingCommandCount)

            // Advance past retry backoff so second connection connects and drains queue
            advanceTimeBy(3000L)
            advanceUntilIdle()

            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            assertNotNull(secondTransport)

            val keyMsg = secondTransport!!.sentMessages.firstOrNull {
                it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_DOWN
            }
            assertNotNull("Expected queued DPAD_DOWN to be delivered on reconnected transport", keyMsg)
            assertEquals(0, manager.connectionDiagnostics.value.pendingCommandCount)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 5: Expired command not delivered
    // =========================================================================
    @Test
    fun test5_expiredCommandNotDelivered() = runTest {
        val queue = RemoteCommandQueue(maxCapacity = 25)
        // Command queued with old timestamp beyond 3500ms
        val oldTimestamp = System.currentTimeMillis() - 5000L
        queue.enqueue(QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_BACK, Direction.SHORT, createdAtMs = oldTimestamp))

        // peekNextValid purges expired entries automatically
        val validItem = queue.peekNextValid()
        assertTrue("Expired command must be purged and not returned", validItem == null)
        assertEquals(0, queue.size)
        assertEquals(1, queue.expiredCount)
    }

    // =========================================================================
    // Scenario 6: Queue size limit enforced (max 25 commands) & recorded in diagnostics
    // =========================================================================
    @Test
    fun test6_queueSizeLimitEnforcedAndDiagnosticsUpdated() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port, failOnConnect = true)
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()

            // Enqueue 35 commands while disconnected
            for (i in 1..35) {
                manager.sendKey(i, Direction.SHORT)
            }
            advanceUntilIdle()

            assertEquals(25, manager.connectionDiagnostics.value.pendingCommandCount)
            assertEquals(10, manager.connectionDiagnostics.value.evictedCommandCount)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 7: Multiple rapid button presses preserve order
    // =========================================================================
    @Test
    fun test7_rapidPressesPreserveOrder() = runTest {
        val queue = RemoteCommandQueue(maxCapacity = 25)
        val expectedCodes = listOf(
            AndroidTvKeyCodes.KEYCODE_DPAD_UP,
            AndroidTvKeyCodes.KEYCODE_DPAD_DOWN,
            AndroidTvKeyCodes.KEYCODE_DPAD_LEFT,
            AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT,
            AndroidTvKeyCodes.KEYCODE_DPAD_CENTER
        )

        expectedCodes.forEach { code ->
            queue.enqueue(QueuedRemoteCommand.KeyCommand(code, Direction.SHORT))
        }

        val collectedCodes = mutableListOf<Int>()
        while (true) {
            val cmd = queue.peekNextValid() ?: break
            collectedCodes.add((cmd as QueuedRemoteCommand.KeyCommand).keyCode)
            queue.removeHead(cmd)
        }

        assertEquals(expectedCodes, collectedCodes)
    }

    // =========================================================================
    // Scenario 8: Queue draining when a command write fails preserves remaining commands
    // =========================================================================
    @Test
    fun test8_queueDrainingFailsPreservesRemaining() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var firstTransport: FakeTvTransport? = null
        var secondTransport: FakeTvTransport? = null
        var transportCount = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                transportCount++
                if (transportCount == 1) {
                    // Handshake uses 2 writes (RemoteConfigure + RemoteSetActive).
                    // The 3rd write is the first button command!
                    FakeTvTransport(host, port, failOnNthWrite = 3).also { firstTransport = it }
                } else {
                    FakeTvTransport(host, port).also { secondTransport = it }
                }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            // Queue two button commands
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.SHORT)
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, Direction.SHORT)
            advanceUntilIdle()

            // 3rd write failed; reconnect triggered
            assertTrue(
                manager.connectionState.value is ConnectionState.Reconnecting ||
                        manager.connectionState.value is ConnectionState.Connecting
            )
            // Commands not confirmed delivered remain in queue!
            assertTrue(manager.connectionDiagnostics.value.pendingCommandCount >= 1)

            // Advance retry timer to connect 2nd transport and drain
            advanceTimeBy(3000L)
            advanceUntilIdle()

            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            assertNotNull(secondTransport)
            // Verified 2nd transport successfully delivered the commands
            assertTrue(secondTransport!!.sentMessages.any {
                it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_UP ||
                        it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_DOWN
            })
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 9: Button press racing with session teardown does not crash
    // =========================================================================
    @Test
    fun test9_buttonPressRacingWithTeardown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ -> FakeTvTransport(host, port) }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            // Race disconnect and button press
            manager.disconnect()
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, Direction.SHORT)
            manager.sendImeText("test input")
            manager.launchAppLink("https://youtube.com")
            advanceUntilIdle()

            assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 10: Old reader-loop events cannot disconnect a newer session
    // =========================================================================
    @Test
    fun test10_oldReaderLoopCannotDisconnectNewSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var session1Transport: FakeTvTransport? = null
        var session2Transport: FakeTvTransport? = null
        var callCount = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                callCount++
                if (callCount == 1) {
                    FakeTvTransport(host, port).also { session1Transport = it }
                } else {
                    FakeTvTransport(host, port).also { session2Transport = it }
                }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()
            val session1Id = manager.connectionDiagnostics.value.sessionId

            // Reconnect to initiate Session 2
            manager.connect(testDevice)
            advanceUntilIdle()
            val session2Id = manager.connectionDiagnostics.value.sessionId
            assertNotEquals(session1Id, session2Id)

            // Session 1 closes
            session1Transport?.close()
            advanceUntilIdle()

            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            assertEquals(session2Id, manager.connectionDiagnostics.value.sessionId)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 11: Matching heartbeat response clears ping outstanding
    // =========================================================================
    @Test
    fun test11_matchingHeartbeatResponse() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()

            // Advance to trigger ping (12s)
            advanceTimeBy(13000L)
            testScheduler.runCurrent()

            val pingMsg = transport!!.sentMessages.firstOrNull { it.remote_ping_request != null }
            assertNotNull("Expected RemotePingRequest message", pingMsg)
            val pingVal = pingMsg!!.remote_ping_request!!.val1 ?: 1
            assertTrue(manager.connectionDiagnostics.value.isPingOutstanding)

            // TV sends matching pong
            transport!!.sendFrameToClient(
                RemoteMessage(remote_ping_response = RemotePingResponse(val1 = pingVal))
            )
            advanceTimeBy(200L)
            testScheduler.runCurrent()

            assertFalse("Matching pong must clear isPingOutstanding", manager.connectionDiagnostics.value.isPingOutstanding)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 12: Mismatched heartbeat response is ignored
    // =========================================================================
    @Test
    fun test12_mismatchedHeartbeatResponseIsIgnored() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()

            // Advance to trigger ping (12s)
            advanceTimeBy(13000L)
            testScheduler.runCurrent()

            val pingMsg = transport!!.sentMessages.firstOrNull { it.remote_ping_request != null }
            assertNotNull(pingMsg)
            val pingVal = pingMsg!!.remote_ping_request!!.val1 ?: 1

            // TV sends mismatched pong (pingVal + 999)
            transport!!.sendFrameToClient(
                RemoteMessage(remote_ping_response = RemotePingResponse(val1 = pingVal + 999))
            )
            advanceTimeBy(200L)
            testScheduler.runCurrent()

            assertTrue("Mismatched pong must be ignored; ping remains outstanding", manager.connectionDiagnostics.value.isPingOutstanding)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 13: Stale heartbeat response from old session is ignored
    // =========================================================================
    @Test
    fun test13_staleHeartbeatFromOldSessionIgnored() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport1: FakeTvTransport? = null
        var transport2: FakeTvTransport? = null
        var count = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                count++
                if (count == 1) {
                    FakeTvTransport(host, port).also { transport1 = it }
                } else {
                    FakeTvTransport(host, port).also { transport2 = it }
                }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()

            // Reconnect to new session
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertFalse(manager.connectionDiagnostics.value.isPingOutstanding)

            // Old transport sends a pong
            transport1?.sendFrameToClient(RemoteMessage(remote_ping_response = RemotePingResponse(val1 = 1)))
            advanceTimeBy(200L)
            testScheduler.runCurrent()

            assertFalse(manager.connectionDiagnostics.value.isPingOutstanding)
            assertTrue(manager.connectionState.value is ConnectionState.Ready)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 14: Long-press release after disconnection is preserved
    // =========================================================================
    @Test
    fun test14_longPressReleaseAfterDisconnectionPreserved() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transportCount = 0
        var secondTransport: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                transportCount++
                if (transportCount == 1) {
                    FakeTvTransport(host, port, failOnConnect = true)
                } else {
                    FakeTvTransport(host, port).also { secondTransport = it }
                }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()

            // Press START_LONG while disconnected, then END_LONG
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT, Direction.START_LONG)
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT, Direction.END_LONG)
            advanceUntilIdle()

            // Reconnect
            advanceTimeBy(3000L)
            advanceUntilIdle()

            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            assertNotNull(secondTransport)
            // Confirm both START_LONG and END_LONG were preserved and delivered
            val rightKeys = secondTransport!!.sentMessages.filter {
                it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT
            }
            assertEquals(2, rightKeys.size)
            assertEquals(Direction.START_LONG, rightKeys[0].remote_key_inject!!.direction)
            assertEquals(Direction.END_LONG, rightKeys[1].remote_key_inject!!.direction)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 15: Missing ping response triggers reconnection
    // =========================================================================
    @Test
    fun test15_missingPingResponseTriggersReconnection() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transportCount = 0
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                transportCount++
                FakeTvTransport(host, port)
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()
            val initialSessionId = manager.connectionDiagnostics.value.sessionId

            // Advance time: 13s for ping + 8s for ping deadline
            advanceTimeBy(13000L)
            testScheduler.runCurrent()
            advanceTimeBy(8000L)
            testScheduler.runCurrent()

            println("TEST15 DIAGNOSTICS: ${manager.connectionDiagnostics.value}")
            println("TEST15 STATE: ${manager.connectionState.value}")
            println("TEST15 TRANSPORT COUNT: $transportCount")

            assertTrue(
                "Missing ping response must trigger reconnection: state=${manager.connectionState.value}, diag=${manager.connectionDiagnostics.value}",
                manager.connectionDiagnostics.value.lastDisconnectReason.contains("Ping response deadline exceeded") ||
                        manager.connectionDiagnostics.value.reconnectAttemptCount > 0 ||
                        manager.connectionDiagnostics.value.sessionId > initialSessionId ||
                        transportCount > 1
            )
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 16: Intentional disconnect cancels retries and closes sockets
    // =========================================================================
    @Test
    fun test16_intentionalDisconnectCancelsRetries() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            manager.disconnect()
            advanceUntilIdle()

            assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
            assertFalse(transport!!.isOpen.get())

            advanceTimeBy(30000L)
            advanceUntilIdle()
            assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 17: Switching TVs closes previous session
    // =========================================================================
    @Test
    fun test17_switchingTvsClosesPreviousSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var device1Transport: FakeTvTransport? = null
        var device2Transport: FakeTvTransport? = null
        var count = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                count++
                if (count == 1) {
                    FakeTvTransport(host, port).also { device1Transport = it }
                } else {
                    FakeTvTransport(host, port).also { device2Transport = it }
                }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()
            assertTrue(device1Transport!!.isOpen.get())

            manager.connect(altDevice)
            advanceUntilIdle()

            assertFalse("Previous TV transport must be closed when switching devices", device1Transport!!.isOpen.get())
            assertTrue("New TV transport must be open", device2Transport!!.isOpen.get())
            assertEquals(altDevice.id, (manager.connectionState.value as ConnectionState.Ready).device.id)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 18: TLS failure closes partially created sockets & enforces backoff
    // =========================================================================
    @Test
    fun test18_tlsFailureClosesSocketAndEnforcesBackoff() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port, failOnConnect = true).also { transport = it }
            }
        )

        try {
            manager.connect(testDevice)
            advanceUntilIdle()

            assertNotNull(transport)
            assertFalse(transport!!.isOpen.get())
            assertTrue(transport!!.closeCallCount.get() >= 1)

            assertTrue(manager.connectionDiagnostics.value.reconnectAttemptCount >= 1)
            assertTrue(
                manager.connectionState.value is ConnectionState.Reconnecting ||
                        manager.connectionState.value is ConnectionState.Connecting ||
                        manager.connectionState.value is ConnectionState.Failed
            )
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 19: Outgoing button presses do not delay heartbeat ping and missing pong reconnects
    // =========================================================================
    @Test
    fun test19_outgoingButtonsDoNotDelayHeartbeatAndMissingPongReconnects() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            // Start clock at non-zero offset for distinct timestamps
            advanceTimeBy(1000L)
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            val initialInboundTs = manager.connectionDiagnostics.value.lastReceivedResponseTimeMs
            transport!!.sentMessages.clear()

            // Send repeated outgoing button presses every 2 seconds for 12 seconds with NO incoming TV frames
            for (i in 1..6) {
                advanceTimeBy(2000L)
                manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, Direction.SHORT)
                testScheduler.runCurrent()
            }

            // Inbound timestamp must remain unchanged while outbound timestamp advanced
            assertEquals(
                "Outgoing buttons must never update lastReceivedResponseTimeMs",
                initialInboundTs,
                manager.connectionDiagnostics.value.lastReceivedResponseTimeMs
            )
            assertTrue(
                "Outgoing buttons must advance lastOutboundFrameTimeMs",
                manager.connectionDiagnostics.value.lastOutboundFrameTimeMs > initialInboundTs
            )

            // Verify a RemotePingRequest was transmitted despite continuous button presses
            val pingMsg = transport!!.sentMessages.firstOrNull { it.remote_ping_request != null }
            assertNotNull("Heartbeat ping must be sent after PING_INTERVAL_MS despite active outbound button presses", pingMsg)
            assertTrue("Ping must be marked outstanding", manager.connectionDiagnostics.value.isPingOutstanding)

            // Advance time past ping deadline without sending any incoming TV response
            advanceTimeBy(8000L)
            testScheduler.runCurrent()

            assertFalse("Dead transport must be closed after ping deadline", transport!!.isOpen.get())
            assertTrue(
                "Missing pong response must trigger reconnection",
                manager.connectionDiagnostics.value.recentReconnectReasons.any { it.contains("Ping response deadline exceeded") }
            )
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 20: Missing pong closes dead transport and succeeds on reconnect
    // =========================================================================
    @Test
    fun test20_missingPongClosesTransportAndSucceedsOnReconnect() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val transports = mutableListOf<FakeTvTransport>()

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transports.add(it) }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            advanceTimeBy(100L)
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertEquals(1, transports.size)
            val firstSessionId = manager.connectionDiagnostics.value.sessionId
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            // Trigger ping at 12s and miss deadline at 18s+
            advanceTimeBy(14000L)
            testScheduler.runCurrent()
            assertTrue(manager.connectionDiagnostics.value.isPingOutstanding)

            advanceTimeBy(7000L)
            testScheduler.runCurrent()

            assertFalse("First dead transport must be closed", transports[0].isOpen.get())
            assertEquals("Expected exactly one replacement transport on coordinated reconnect", 2, transports.size)
            assertTrue("Second transport must be open and READY", transports[1].isOpen.get())
            assertTrue("Connection state must recover to Ready", manager.connectionState.value is ConnectionState.Ready)
            assertTrue(manager.connectionDiagnostics.value.sessionId > firstSessionId)
            assertFalse(manager.connectionDiagnostics.value.isPingOutstanding)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 21: Wi-Fi loss invalidates session and restoration reconnects
    // =========================================================================
    @Test
    fun test21_wifiLossAndRestorationReconnects() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val transports = mutableListOf<FakeTvTransport>()

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transports.add(it) }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            advanceTimeBy(100L)
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            val session1 = manager.connectionDiagnostics.value.sessionId

            // Simulate Wi-Fi loss
            manager.onNetworkLost()
            testScheduler.runCurrent()

            assertFalse("Transport must close on network loss", transports[0].isOpen.get())
            assertTrue(manager.connectionState.value is ConnectionState.Reconnecting)
            assertEquals("Network lost", manager.connectionDiagnostics.value.lastDisconnectReason)

            // Simulate Wi-Fi restoration
            advanceTimeBy(500L)
            manager.onNetworkRestored()
            testScheduler.runCurrent()

            assertEquals(2, transports.size)
            assertTrue(transports[1].isOpen.get())
            assertTrue("Must transition back to Ready on network restoration", manager.connectionState.value is ConnectionState.Ready)
            assertTrue(manager.connectionDiagnostics.value.sessionId > session1)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 22: App background/resume skips healthy and reconnects stale session
    // =========================================================================
    @Test
    fun test22_appResumeSkipsHealthyAndReconnectsStale() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val transports = mutableListOf<FakeTvTransport>()
        var fakeClock = 1_000L

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transports.add(it) }
            },
            timeProvider = { fakeClock }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            val session1 = manager.connectionDiagnostics.value.sessionId

            // Case A: Resume shortly afterward while healthy -> should NOT reconnect
            fakeClock += 3_000L
            manager.onAppResume()
            testScheduler.runCurrent()
            assertEquals("Healthy resume must reuse existing session", 1, transports.size)
            assertEquals(session1, manager.connectionDiagnostics.value.sessionId)

            // Case B: Phone slept for 60s without advancing coroutine timers; session is now stale
            fakeClock += 60_000L
            manager.onAppResume()
            testScheduler.runCurrent()

            assertEquals("Stale resume must tear down old session and open new transport", 2, transports.size)
            assertFalse("Stale transport must be closed", transports[0].isOpen.get())
            assertTrue("New transport must be Ready", manager.connectionState.value is ConnectionState.Ready)
            assertTrue(manager.connectionDiagnostics.value.sessionId > session1)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 23: Repeated reconnect triggers coalesce without duplicate connects
    // =========================================================================
    @Test
    fun test23_repeatedReconnectTriggersCoalesce() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        val connectCalls = AtomicInteger(0)

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                connectCalls.incrementAndGet()
                // Initial connect fails so manager enters backoff Reconnecting state
                FakeTvTransport(host, port, failOnConnect = connectCalls.get() == 1)
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertEquals(1, connectCalls.get())
            assertTrue(manager.connectionState.value is ConnectionState.Reconnecting)

            // Fire repeated reconnect triggers while backoff reconnect job is pending
            manager.reconnectIfDisconnected()
            manager.onNetworkRestored()
            manager.onAppResume()
            testScheduler.runCurrent()

            // First trigger upgrades to immediate and others coalesce cleanly
            assertEquals("Coalesced reconnects must produce only one new transport attempt", 2, connectCalls.get())
            assertTrue("Expected coalescedReconnectCount >= 2", manager.connectionDiagnostics.value.coalescedReconnectCount >= 2)
            assertTrue(manager.connectionState.value is ConnectionState.Ready)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 24: Command queue preserves strict FIFO order and deterministic expiration
    // =========================================================================
    @Test
    fun test24_commandQueueOrderingAndDeterministicExpiration() = runTest {
        var clock = 10_000L
        val queue = RemoteCommandQueue(maxCapacity = 25, timeProvider = { clock })

        val cmd1 = QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.SHORT, createdAtMs = clock)
        clock += 2_000L
        val cmd2 = QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, Direction.SHORT, createdAtMs = clock)
        val cmd3 = QueuedRemoteCommand.ImeCommand("hello", createdAtMs = clock)
        val cmd4 = QueuedRemoteCommand.AppLinkCommand("https://youtube.com", createdAtMs = clock)

        queue.enqueue(cmd1)
        queue.enqueue(cmd2)
        queue.enqueue(cmd3)
        queue.enqueue(cmd4)

        // Advance clock by 2000ms -> cmd1 age is 4000ms (> 3500ms expired), cmd2/3/4 age is 2000ms (still valid!)
        clock += 2_000L
        val ordered = mutableListOf<QueuedRemoteCommand>()
        while (true) {
            val next = queue.peekNextValid() ?: break
            ordered.add(next)
            queue.removeHead(next)
        }

        assertEquals(3, ordered.size)
        assertTrue(ordered[0] is QueuedRemoteCommand.KeyCommand)
        assertEquals(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, (ordered[0] as QueuedRemoteCommand.KeyCommand).keyCode)
        assertEquals("hello", (ordered[1] as QueuedRemoteCommand.ImeCommand).text)
        assertEquals("https://youtube.com", (ordered[2] as QueuedRemoteCommand.AppLinkCommand).appLink)
        assertEquals(1, queue.expiredCount)
    }

    // =========================================================================
    // Scenario 25: Mid-write failure marks uncertain delivery and does NOT blindly replay
    // =========================================================================
    @Test
    fun test25_midWriteFailureFlagsUncertainDeliveryWithoutBlindReplay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var count = 0
        var transport2: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                count++
                if (count == 1) {
                    // Fail mid-frame on the 3rd write (after 2 handshake frames)
                    FakeTvTransport(host, port, failOnNthWrite = 3)
                } else {
                    FakeTvTransport(host, port).also { transport2 = it }
                }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            advanceTimeBy(100L)
            manager.connect(testDevice)
            testScheduler.runCurrent()
            assertTrue(manager.connectionState.value is ConnectionState.Ready)

            // Queue two commands: DPAD_UP will fail mid-write; DPAD_DOWN is never attempted
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.SHORT)
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, Direction.SHORT)
            testScheduler.runCurrent()

            assertEquals(1, manager.connectionDiagnostics.value.uncertainDeliveryCount)
            assertNotNull(manager.connectionDiagnostics.value.lastUncertainCommand)
            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            assertNotNull(transport2)

            // DPAD_UP must NOT be blindly replayed on transport2, while DPAD_DOWN MUST be delivered!
            val upReplayed = transport2!!.sentMessages.any {
                it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_UP
            }
            val downDelivered = transport2!!.sentMessages.any {
                it.remote_key_inject?.key_code == AndroidTvKeyCodes.KEYCODE_DPAD_DOWN
            }
            assertFalse("Uncertain mid-write DPAD_UP must NOT be blindly replayed", upReplayed)
            assertTrue("Subsequent unattempted DPAD_DOWN must be delivered after reconnect", downDelivered)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 26: Pre-write failure preserves command cleanly and replays in order
    // =========================================================================
    @Test
    fun test26_preWriteFailurePreservesCommandForRetry() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var count = 0
        var transport2: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                count++
                if (count == 1) {
                    FakeTvTransport(host, port, failBeforeFirstByte = true)
                } else {
                    FakeTvTransport(host, port).also { transport2 = it }
                }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            advanceTimeBy(100L)
            manager.connect(testDevice)
            testScheduler.runCurrent()

            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT, Direction.SHORT)
            manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT, Direction.SHORT)
            testScheduler.runCurrent()

            assertEquals(0, manager.connectionDiagnostics.value.uncertainDeliveryCount)
            assertTrue(manager.connectionState.value is ConnectionState.Ready)
            val deliveredKeys = transport2!!.sentMessages.mapNotNull { it.remote_key_inject?.key_code }
            assertEquals(
                listOf(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT, AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT),
                deliveredKeys
            )
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 27: Long-press END_LONG release is protected from eviction & uncertain drop
    // =========================================================================
    @Test
    fun test27_longPressEndLongReleaseNeverLost() = runTest {
        val queue = RemoteCommandQueue(maxCapacity = 3)
        queue.enqueue(QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.END_LONG))
        queue.enqueue(QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN, Direction.SHORT))
        queue.enqueue(QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT, Direction.SHORT))
        // 4th command exceeds capacity 3: should evict SHORT DPAD_DOWN rather than END_LONG DPAD_UP
        queue.enqueue(QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT, Direction.SHORT))

        val valid = queue.getAllValid().map { it as QueuedRemoteCommand.KeyCommand }
        assertEquals(3, valid.size)
        assertTrue(
            "END_LONG release command must be preserved during eviction",
            valid.any { it.keyCode == AndroidTvKeyCodes.KEYCODE_DPAD_UP && it.direction == Direction.END_LONG }
        )
        assertEquals(1, queue.evictedCount)
    }

    // =========================================================================
    // Scenario 28: TV sleeping causes backoff failure state; waking restores READY session
    // =========================================================================
    @Test
    fun test28_tvSleepingBackoffAndWakingRestoresSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var tvAwake = false

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port, failOnConnect = !tvAwake)
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            // TV is sleeping -> repeated backoff attempts escalate to ConnectionState.Failed
            manager.connect(testDevice)
            testScheduler.runCurrent()

            repeat(5) {
                advanceTimeBy(20_000L)
                testScheduler.runCurrent()
            }

            assertTrue(
                "Sleeping TV after >=5 retries should enter Failed state",
                manager.connectionState.value is ConnectionState.Failed
            )
            assertTrue(manager.connectionDiagnostics.value.reconnectAttemptCount >= 5)

            // TV wakes up and next retry or resume triggers recovery to READY
            tvAwake = true
            advanceTimeBy(20_000L)
            testScheduler.runCurrent()

            assertTrue("Waking TV must recover to ConnectionState.Ready", manager.connectionState.value is ConnectionState.Ready)
            assertEquals(0, manager.connectionDiagnostics.value.reconnectAttemptCount)
        } finally {
            managerScope.cancel()
        }
    }

    // =========================================================================
    // Scenario 29: Null ping_response val1 cannot clear outstanding heartbeat
    // =========================================================================
    @Test
    fun test29_nullPongValDoesNotClearOutstandingPing() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val managerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
        var transport: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            ioDispatcher = testDispatcher,
            scope = managerScope,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            },
            timeProvider = { testScheduler.currentTime }
        )

        try {
            manager.connect(testDevice)
            testScheduler.runCurrent()

            advanceTimeBy(13000L)
            testScheduler.runCurrent()
            assertTrue(manager.connectionDiagnostics.value.isPingOutstanding)

            // Send RemotePingResponse with val1 = null
            transport!!.sendFrameToClient(
                RemoteMessage(remote_ping_response = RemotePingResponse(val1 = null))
            )
            advanceTimeBy(200L)
            testScheduler.runCurrent()

            assertTrue(
                "Null pong val1 must NOT clear outstanding ping",
                manager.connectionDiagnostics.value.isPingOutstanding
            )
        } finally {
            managerScope.cancel()
        }
    }
}
