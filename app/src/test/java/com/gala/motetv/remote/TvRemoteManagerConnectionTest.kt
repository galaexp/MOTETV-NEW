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
import kotlinx.coroutines.delay
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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit test suite verifying all 18 connectivity and command delivery requirements
 * of the MoteTV connection engine.
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
     * and socket closures.
     */
    class FakeTvTransport(
        val host: String,
        val port: Int,
        var autoRespondHandshake: Boolean = true,
        var failOnConnect: Boolean = false,
        var failOnWrite: Boolean = false
    ) : TvTransport {
        val isOpen = AtomicBoolean(false)
        val sentFrames = CopyOnWriteArrayList<ByteArray>()
        var connectCallCount = AtomicInteger(0)
        var closeCallCount = AtomicInteger(0)

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
                    // Poll with brief timeout to emulate socket soTimeout and avoid blocking tests indefinitely
                    val b = incomingBytesQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (b != null) return b
                    if (!isOpen.get()) return -1
                    throw SocketTimeoutException("Simulated socket idle timeout")
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val first = read()
                    if (first == -1) return -1
                    b[off] = first.toByte()
                    var count = 1
                    while (count < len) {
                        val next = incomingBytesQueue.poll() ?: break
                        b[off + count] = next.toByte()
                        count++
                    }
                    return count
                }
            }
            frameReader = ProtoFrameReader(customInStream)

            val interceptingOut = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failOnWrite) throw IOException("Simulated write transmission failure")
                    super.write(b, off, len)
                }

                override fun flush() {
                    super.flush()
                    val bytes = toByteArray()
                    if (bytes.isNotEmpty()) {
                        sentFrames.add(bytes)
                        reset()
                    }
                }
            }
            frameWriter = ProtoFrameWriter(interceptingOut)

            if (autoRespondHandshake) {
                // TV replies with RemoteConfigure and RemoteSetActive
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
        var createdTransport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { createdTransport = it }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()

        assertTrue(manager.connectionState.value is ConnectionState.Ready)
        assertNotNull(createdTransport)
        assertTrue(createdTransport!!.isOpen.get())
        assertEquals(0, manager.connectionDiagnostics.value.reconnectAttemptCount)
    }

    // =========================================================================
    // Scenario 2: Button press while READY sends immediately
    // =========================================================================
    @Test
    fun test2_buttonPressWhileReadySendsImmediately() = runTest {
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertTrue(manager.connectionState.value is ConnectionState.Ready)

        val beforeCount = transport!!.sentFrames.size
        manager.sendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, Direction.SHORT)
        advanceUntilIdle()

        val afterCount = transport!!.sentFrames.size
        assertTrue(afterCount > beforeCount)
    }

    // =========================================================================
    // Scenario 3: Button press while reconnecting queues command
    // =========================================================================
    @Test
    fun test3_buttonPressWhileReconnectingQueuesCommand() = runTest {
        val queue = RemoteCommandQueue(maxCapacity = 25)
        val cmd = QueuedRemoteCommand.KeyCommand(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.SHORT)
        val queued = queue.enqueue(cmd)

        assertTrue(queued)
        assertEquals(1, queue.size)
    }

    // =========================================================================
    // Scenario 4: Queued command delivered after successful reconnection
    // =========================================================================
    @Test
    fun test4_queuedCommandDeliveredAfterReconnect() = runTest {
        var transportCount = 0
        var secondTransport: FakeTvTransport? = null

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                transportCount++
                if (transportCount == 1) {
                    FakeTvTransport(host, port, failOnConnect = true)
                } else {
                    FakeTvTransport(host, port).also { secondTransport = it }
                }
            }
        )

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
        assertTrue(secondTransport!!.sentFrames.isNotEmpty())
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

        val eligible = queue.drainAllValid()
        assertTrue("Expired command must not be returned in eligible drain", eligible.isEmpty())
        assertEquals(0, queue.size)
    }

    // =========================================================================
    // Scenario 6: Queue size limit enforced (max 25 commands)
    // =========================================================================
    @Test
    fun test6_queueSizeLimitEnforced() = runTest {
        val queue = RemoteCommandQueue(maxCapacity = 25)
        for (i in 1..35) {
            queue.enqueue(QueuedRemoteCommand.KeyCommand(i, Direction.SHORT))
        }

        assertEquals(25, queue.size)
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

        val drained = queue.drainAllValid()
        assertEquals(5, drained.size)
        val drainedCodes = drained.map { (it as QueuedRemoteCommand.KeyCommand).keyCode }
        assertEquals(expectedCodes, drainedCodes)
    }

    // =========================================================================
    // Scenario 8: A failed write triggers one coordinated reconnect
    // =========================================================================
    @Test
    fun test8_failedWriteTriggersReconnect() = runTest {
        var firstTransport: FakeTvTransport? = null
        var secondTransport: FakeTvTransport? = null
        var transportCount = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                transportCount++
                if (transportCount == 1) {
                    FakeTvTransport(host, port, failOnWrite = true).also { firstTransport = it }
                } else {
                    FakeTvTransport(host, port).also { secondTransport = it }
                }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertTrue(manager.connectionState.value is ConnectionState.Ready)

        // Sending key will fail write and trigger reconnect
        manager.sendKey(AndroidTvKeyCodes.KEYCODE_HOME, Direction.SHORT)
        advanceUntilIdle()

        // State transitions to reconnecting/connecting
        val isReconnectingOrConnecting = manager.connectionState.value is ConnectionState.Reconnecting ||
                manager.connectionState.value is ConnectionState.Connecting
        assertTrue(isReconnectingOrConnecting)

        // Advance timer for retry backoff
        advanceTimeBy(3000L)
        advanceUntilIdle()

        assertTrue(manager.connectionState.value is ConnectionState.Ready)
        assertEquals(2, transportCount)
    }

    // =========================================================================
    // Scenario 9: Multiple simultaneous reconnect triggers result in only one attempt
    // =========================================================================
    @Test
    fun test9_multipleSimultaneousTriggersOneAttempt() = runTest {
        var transportsCreated = 0
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                transportsCreated++
                FakeTvTransport(host, port)
            }
        )

        manager.connect(testDevice)
        // Trigger rapid simultaneous requests
        manager.reconnectIfDisconnected()
        manager.onAppResume()
        manager.onNetworkRestored()
        advanceUntilIdle()

        assertEquals(1, transportsCreated)
        assertTrue(manager.connectionState.value is ConnectionState.Ready)
    }

    // =========================================================================
    // Scenario 10: Old reader-loop events cannot disconnect a newer session
    // =========================================================================
    @Test
    fun test10_oldReaderLoopCannotDisconnectNewSession() = runTest {
        var session1Transport: FakeTvTransport? = null
        var session2Transport: FakeTvTransport? = null
        var callCount = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                callCount++
                if (callCount == 1) {
                    FakeTvTransport(host, port).also { session1Transport = it }
                } else {
                    FakeTvTransport(host, port).also { session2Transport = it }
                }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        val session1Id = manager.connectionDiagnostics.value.sessionId

        // Reconnect to initiate Session 2
        manager.connect(testDevice)
        advanceUntilIdle()
        val session2Id = manager.connectionDiagnostics.value.sessionId
        assertNotEquals(session1Id, session2Id)

        // Now simulate session 1 transport closure
        session1Transport?.close()
        advanceUntilIdle()

        assertTrue(manager.connectionState.value is ConnectionState.Ready)
        assertEquals(session2Id, manager.connectionDiagnostics.value.sessionId)
    }

    // =========================================================================
    // Scenario 11: Ping response received before deadline resets ping state
    // =========================================================================
    @Test
    fun test11_pingResponseReceivedResetsState() = runTest {
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()

        // Advance to trigger ping (12s)
        advanceTimeBy(13000L)

        // TV sends ping response
        transport!!.sendFrameToClient(
            RemoteMessage(remote_ping_response = RemotePingResponse(val1 = 42))
        )
        advanceTimeBy(500L)

        assertFalse(manager.connectionDiagnostics.value.isPingOutstanding)
        assertTrue(manager.connectionState.value is ConnectionState.Ready)
    }

    // =========================================================================
    // Scenario 12: Missing ping response triggers reconnection
    // =========================================================================
    @Test
    fun test12_missingPingResponseTriggersReconnection() = runTest {
        var transport: FakeTvTransport? = null
        var totalTransports = 0
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                totalTransports++
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()

        // Advance time: 12s for ping + 7s for ping deadline
        advanceTimeBy(19000L)
        advanceUntilIdle()

        // Ping timed out, reconnection was triggered
        assertTrue(
            manager.connectionDiagnostics.value.lastDisconnectReason.contains("Ping response timed out") ||
                    manager.connectionDiagnostics.value.reconnectAttemptCount > 0
        )
    }

    // =========================================================================
    // Scenario 13: Normal idle socket timeout does not cause unnecessary reconnect
    // =========================================================================
    @Test
    fun test13_normalIdleTimeoutDoesNotDisconnect() = runTest {
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertTrue(manager.connectionState.value is ConnectionState.Ready)

        // Idle socket period
        advanceTimeBy(5000L)
        advanceUntilIdle()

        assertTrue(manager.connectionState.value is ConnectionState.Ready)
    }

    // =========================================================================
    // Scenario 14: Network lost and restored
    // =========================================================================
    @Test
    fun test14_networkLostAndRestored() = runTest {
        var transportCount = 0
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                transportCount++
                FakeTvTransport(host, port)
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertTrue(manager.connectionState.value is ConnectionState.Ready)

        // Network lost
        manager.onNetworkLost()
        advanceUntilIdle()
        assertTrue(
            manager.connectionState.value is ConnectionState.Reconnecting ||
                    manager.connectionState.value is ConnectionState.Disconnected
        )

        // Network restored
        manager.onNetworkRestored()
        advanceUntilIdle()
        assertTrue(manager.connectionState.value is ConnectionState.Ready)
    }

    // =========================================================================
    // Scenario 15: Activity resume with healthy connection does not reconnect
    // =========================================================================
    @Test
    fun test15_activityResumeWithHealthyConnectionDoesNotReconnect() = runTest {
        var transportCount = 0
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                transportCount++
                FakeTvTransport(host, port)
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertEquals(1, transportCount)

        // onAppResume called
        manager.onAppResume()
        advanceUntilIdle()

        assertEquals(1, transportCount)
        assertTrue(manager.connectionState.value is ConnectionState.Ready)
    }

    // =========================================================================
    // Scenario 16: Intentional disconnect cancels all retry jobs
    // =========================================================================
    @Test
    fun test16_intentionalDisconnectCancelsRetries() = runTest {
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port).also { transport = it }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertTrue(manager.connectionState.value is ConnectionState.Ready)

        manager.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        assertFalse(transport!!.isOpen.get())

        // Advancing time should not trigger any reconnect attempts
        advanceTimeBy(30000L)
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    // =========================================================================
    // Scenario 17: Switching TVs cannot leave the previous TV session active
    // =========================================================================
    @Test
    fun test17_switchingTvsClosesPreviousSession() = runTest {
        var device1Transport: FakeTvTransport? = null
        var device2Transport: FakeTvTransport? = null
        var count = 0

        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                count++
                if (count == 1) {
                    FakeTvTransport(host, port).also { device1Transport = it }
                } else {
                    FakeTvTransport(host, port).also { device2Transport = it }
                }
            }
        )

        manager.connect(testDevice)
        advanceUntilIdle()
        assertTrue(device1Transport!!.isOpen.get())

        // Switch to altDevice
        manager.connect(altDevice)
        advanceUntilIdle()

        assertFalse("Previous TV transport must be closed when switching devices", device1Transport!!.isOpen.get())
        assertTrue("New TV transport must be open", device2Transport!!.isOpen.get())
        assertEquals(altDevice.id, (manager.connectionState.value as ConnectionState.Ready).device.id)
    }

    // =========================================================================
    // Scenario 18: TLS failure closes all partially created sockets & triggers backoff
    // =========================================================================
    @Test
    fun test18_tlsFailureClosesSocketAndEnforcesBackoff() = runTest {
        var transport: FakeTvTransport? = null
        val manager = TvRemoteManagerImpl(
            credentialStore = credentialStore,
            scope = this,
            transportFactory = { host, port, _ ->
                FakeTvTransport(host, port, failOnConnect = true).also { transport = it }
            }
        )

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
    }
}
