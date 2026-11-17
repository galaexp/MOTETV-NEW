package com.gala.motetv.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class AdbConnectionAndAuthTest {

    @Test
    fun adbCredentialStoreGeneratesRsaKeyStructAndValidAuthSignature() {
        val store = AdbCredentialStore(null)
        val kp = store.getOrCreateAdbKeyPair()
        val pub = kp.public as RSAPublicKey
        assertEquals(2048, pub.modulus.bitLength())

        val adbPubPayload = store.encodeAdbPublicKeyPayload("motetv@test")
        val ascii = adbPubPayload.toString(Charsets.US_ASCII)
        assertTrue(ascii.contains(" motetv@test\u0000"))

        val structBytes = store.encodeAndroidRsaPublicKey(pub)
        assertEquals(524, structBytes.size)

        val token = ByteArray(20) { (it + 1).toByte() }
        val sig = store.signAuthToken(token)
        assertTrue("Generated signature must verify locally with public key", store.verifySignatureLocally(token, sig))
        assertFalse(
            "Altered token must fail signature verification",
            store.verifySignatureLocally(ByteArray(20) { 0x7F }, sig)
        )
    }

    @Test
    fun adbWireCodecRoundTripAndRejectsInvalidMagic() {
        val payload = "shell:pm list packages\u0000".toByteArray(Charsets.UTF_8)
        val encoded = AdbWireCodec.encodePacket(AdbProtocolConstants.A_OPEN, 7, 0, payload)
        val decoded = AdbWireCodec.readPacket(DataInputStream(ByteArrayInputStream(encoded)))

        assertEquals(AdbProtocolConstants.A_OPEN, decoded.command)
        assertEquals(7, decoded.arg0)
        assertEquals(0, decoded.arg1)
        assertArrayEquals(payload, decoded.payload)

        val corrupted = encoded.copyOf()
        corrupted[20] = (corrupted[20].toInt() xor 0xFF).toByte()
        var magicRejected = false
        try {
            AdbWireCodec.readPacket(DataInputStream(ByteArrayInputStream(corrupted)))
        } catch (_: IOException) {
            magicRejected = true
        }
        assertTrue("Corrupted magic header must be rejected", magicRejected)
    }

    @Test
    fun ed25519Spake2MatchesBetweenClientAndServerAndRejectsMismatchedPassword() {
        val ekm = ByteArray(64) { (it * 3 + 7).toByte() }
        val pwClient = "482910".toByteArray(Charsets.UTF_8) + ekm
        val pwServerMatch = "482910".toByteArray(Charsets.UTF_8) + ekm
        val pwServerWrong = "999999".toByteArray(Charsets.UTF_8) + ekm

        val aliceName = "adb pair client".toByteArray(Charsets.US_ASCII)
        val bobName = "adb pair server".toByteArray(Charsets.US_ASCII)

        val alice = Ed25519Spake2.Spake2Session(Ed25519Spake2.Role.ALICE_CLIENT, aliceName, bobName)
        val bob = Ed25519Spake2.Spake2Session(Ed25519Spake2.Role.BOB_SERVER, bobName, aliceName)

        val aliceMsg = alice.generateMessage(pwClient)
        val bobMsg = bob.generateMessage(pwServerMatch)
        assertEquals(32, aliceMsg.size)
        assertEquals(32, bobMsg.size)

        val keyA = alice.processMessage(bobMsg)
        val keyB = bob.processMessage(aliceMsg)
        assertArrayEquals("Matching passwords must derive identical 64-byte SPAKE2 key", keyA, keyB)

        // Mismatched password must produce a completely different secret
        val alice2 = Ed25519Spake2.Spake2Session(Ed25519Spake2.Role.ALICE_CLIENT, aliceName, bobName)
        val bobWrong = Ed25519Spake2.Spake2Session(Ed25519Spake2.Role.BOB_SERVER, bobName, aliceName)
        val mA = alice2.generateMessage(pwClient)
        val mB = bobWrong.generateMessage(pwServerWrong)
        assertFalse(alice2.processMessage(mB).contentEquals(bobWrong.processMessage(mA)))
    }

    @Test
    fun nativeWirelessAdbPairingCompletesFullHandshakeAndDetectsIncorrectCode() = runTest {
        val store = AdbCredentialStore(null)
        val ekm = ByteArray(64) { (it + 11).toByte() }

        // Test 1: Matching 6-digit code succeeds end-to-end over simulated TLS channel
        run {
            val clientToServerOut = PipedOutputStream()
            val serverIn = DataInputStream(PipedInputStream(clientToServerOut, 32 * 1024))
            val serverOut = PipedOutputStream()
            val clientIn = PipedInputStream(serverOut, 32 * 1024)

            val executor = Executors.newSingleThreadExecutor()
            val serverFuture = executor.submit<ByteArray> {
                val bob = Ed25519Spake2.Spake2Session(
                    Ed25519Spake2.Role.BOB_SERVER,
                    "adb pair server".toByteArray(Charsets.US_ASCII),
                    "adb pair client".toByteArray(Charsets.US_ASCII)
                )
                val pw = "654321".toByteArray(Charsets.UTF_8) + ekm
                val serverSpakeMsg = bob.generateMessage(pw)
                val clientSpakePkt = NativeWirelessAdbPairing.readPacket(serverIn, NativeWirelessAdbPairing.PACKET_TYPE_SPAKE2_MSG)
                serverOut.write(NativeWirelessAdbPairing.encodePacket(NativeWirelessAdbPairing.PACKET_TYPE_SPAKE2_MSG, serverSpakeMsg))
                serverOut.flush()

                val shared = bob.processMessage(clientSpakePkt.payload)
                val aesKey = SecretKeySpec(
                    NativeWirelessAdbPairing.hkdfSha256(shared, "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII), 16),
                    "AES"
                )
                val zeroNonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()

                val clientPeerPkt = NativeWirelessAdbPairing.readPacket(serverIn, NativeWirelessAdbPairing.PACKET_TYPE_PEER_INFO)
                val dec = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, zeroNonce))
                }
                val decryptedClientPeer = dec.doFinal(clientPeerPkt.payload)

                val serverPeer = NativeWirelessAdbPairing.buildPeerInfoRsaKey("tv-guid-1234\u0000".toByteArray(Charsets.UTF_8))
                val enc = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, zeroNonce))
                }
                serverOut.write(NativeWirelessAdbPairing.encodePacket(NativeWirelessAdbPairing.PACKET_TYPE_PEER_INFO, enc.doFinal(serverPeer)))
                serverOut.flush()
                decryptedClientPeer
            }

            val pairing = NativeWirelessAdbPairing(store) { _, _, _ ->
                object : TlsPairingChannel {
                    override val input = clientIn
                    override val output = clientToServerOut
                    override val exportedKeyingMaterial = ekm
                    override fun close() {
                        runCatching { clientIn.close() }
                        runCatching { clientToServerOut.close() }
                    }
                }
            }
            val result = pairing.pair("192.168.1.90", 37111, "654321", store.getOrCreateAdbKeyPair())
            val peerReceivedByTv = serverFuture.get()
            executor.shutdownNow()

            assertTrue("Native TLS pairing should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue(result.getOrThrow().contains("tv-guid-1234"))
            assertEquals(NativeWirelessAdbPairing.PEER_INFO_SIZE, peerReceivedByTv.size)
            assertEquals(NativeWirelessAdbPairing.PEER_INFO_TYPE_ADB_RSA_PUB_KEY, peerReceivedByTv[0])
        }

        // Test 2: Incorrect pairing code triggers PAIRING_FAILURE via AdbConnectionManagerImpl and never reports success
        run {
            val clientToServerOut = PipedOutputStream()
            val serverIn = DataInputStream(PipedInputStream(clientToServerOut, 32 * 1024))
            val serverOut = PipedOutputStream()
            val clientIn = PipedInputStream(serverOut, 32 * 1024)

            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                val bob = Ed25519Spake2.Spake2Session(
                    Ed25519Spake2.Role.BOB_SERVER,
                    "adb pair server".toByteArray(Charsets.US_ASCII),
                    "adb pair client".toByteArray(Charsets.US_ASCII)
                )
                val serverSpakeMsg = bob.generateMessage("999999".toByteArray(Charsets.UTF_8) + ekm)
                val clientSpakePkt = NativeWirelessAdbPairing.readPacket(serverIn, NativeWirelessAdbPairing.PACKET_TYPE_SPAKE2_MSG)
                serverOut.write(NativeWirelessAdbPairing.encodePacket(NativeWirelessAdbPairing.PACKET_TYPE_SPAKE2_MSG, serverSpakeMsg))
                serverOut.flush()

                val shared = bob.processMessage(clientSpakePkt.payload)
                val wrongAesKey = SecretKeySpec(
                    NativeWirelessAdbPairing.hkdfSha256(shared, "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII), 16),
                    "AES"
                )
                val zeroNonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
                val enc = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, wrongAesKey, GCMParameterSpec(128, zeroNonce))
                }
                NativeWirelessAdbPairing.readPacket(serverIn, NativeWirelessAdbPairing.PACKET_TYPE_PEER_INFO)
                val serverPeer = NativeWirelessAdbPairing.buildPeerInfoRsaKey("tv-guid\u0000".toByteArray())
                serverOut.write(NativeWirelessAdbPairing.encodePacket(NativeWirelessAdbPairing.PACKET_TYPE_PEER_INFO, enc.doFinal(serverPeer)))
                serverOut.flush()
            }

            val pairing = NativeWirelessAdbPairing(store) { _, _, _ ->
                object : TlsPairingChannel {
                    override val input = clientIn
                    override val output = clientToServerOut
                    override val exportedKeyingMaterial = ekm
                    override fun close() {
                        runCatching { clientIn.close() }
                        runCatching { clientToServerOut.close() }
                    }
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val manager = AdbConnectionManagerImpl(
                context = null,
                credentialStore = store,
                ioDispatcher = dispatcher,
                scope = TestScope(dispatcher),
                wirelessPairingTransport = pairing
            )
            val failRes = manager.pairWirelessDebugging("192.168.1.90", 37111, "654321")
            executor.shutdownNow()
            assertTrue(failRes.isFailure)
            assertEquals(AdbDiagnosticCategory.PAIRING_FAILURE, manager.diagnosticCategory.value)
            val st = manager.pairingState.value as AdbPairingState.Failed
            assertEquals(AdbDiagnosticCategory.PAIRING_FAILURE, st.category)
        }
    }

    @Test
    fun pairingSocketConnectTimeoutAndSocketReadTimeoutClassifyAsConnectionTimeout() = runTest {
        val store = AdbCredentialStore(null)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ekm = ByteArray(64) { 3 }

        // Default connect & read timeouts must be consistent
        val defaultPairing = NativeWirelessAdbPairing(store)
        assertEquals(defaultPairing.connectTimeoutMs, defaultPairing.readTimeoutMs)

        // Scenario 1: Socket connect timeout
        val connectTimeoutPairing = NativeWirelessAdbPairing(store) { _, _, _ ->
            throw SocketTimeoutException("connect timed out")
        }
        val mgrConnectTimeout = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = dispatcher,
            scope = TestScope(dispatcher),
            wirelessPairingTransport = connectTimeoutPairing
        )
        val connectRes = mgrConnectTimeout.pairWirelessDebugging("192.168.1.77", 37111, "654321")
        assertTrue(connectRes.isFailure)
        assertEquals(AdbDiagnosticCategory.CONNECTION_TIMEOUT, mgrConnectTimeout.diagnosticCategory.value)
        assertEquals(
            AdbDiagnosticCategory.CONNECTION_TIMEOUT,
            (mgrConnectTimeout.pairingState.value as AdbPairingState.Failed).category
        )

        // Scenario 2: Socket read timeout (TV accepts connection, never answers readPacket)
        val channelClosed = AtomicBoolean(false)
        val readTimeoutPairing = NativeWirelessAdbPairing(store) { _, _, _ ->
            object : TlsPairingChannel {
                override val input = object : InputStream() {
                    override fun read(): Int = throw SocketTimeoutException("Read timed out")
                }
                override val output = ByteArrayOutputStream()
                override val exportedKeyingMaterial = ekm
                override fun close() {
                    channelClosed.set(true)
                }
            }
        }
        val mgrReadTimeout = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = dispatcher,
            scope = TestScope(dispatcher),
            wirelessPairingTransport = readTimeoutPairing
        )
        val readRes = mgrReadTimeout.pairWirelessDebugging("192.168.1.77", 37111, "654321")
        assertTrue(readRes.isFailure)
        assertTrue("Channel must close on read timeout", channelClosed.get())
        assertEquals(AdbDiagnosticCategory.CONNECTION_TIMEOUT, mgrReadTimeout.diagnosticCategory.value)
        assertEquals(
            AdbDiagnosticCategory.CONNECTION_TIMEOUT,
            (mgrReadTimeout.pairingState.value as AdbPairingState.Failed).category
        )
    }

    @Test
    fun userCancellationDuringBlockingPairingClosesSocketImmediatelyWithoutLeavingReaderRunning() {
        val store = AdbCredentialStore(null)
        val ekm = ByteArray(64) { 5 }
        val readEnteredLatch = CountDownLatch(1)
        val socketClosedLatch = CountDownLatch(1)
        val readerExitedLatch = CountDownLatch(1)

        val blockingChannelPairing = NativeWirelessAdbPairing(store) { _, _, _ ->
            val closed = AtomicBoolean(false)
            val blockLock = Object()
            object : TlsPairingChannel {
                override val input = object : InputStream() {
                    override fun read(): Int {
                        readEnteredLatch.countDown()
                        synchronized(blockLock) {
                            while (!closed.get()) {
                                blockLock.wait(5000L)
                            }
                        }
                        readerExitedLatch.countDown()
                        throw IOException("Socket closed")
                    }
                }
                override val output = ByteArrayOutputStream()
                override val exportedKeyingMaterial = ekm
                override fun close() {
                    closed.set(true)
                    synchronized(blockLock) {
                        blockLock.notifyAll()
                    }
                    socketClosedLatch.countDown()
                }
            }
        }

        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = Dispatchers.IO,
            scope = testScope,
            wirelessPairingTransport = blockingChannelPairing
        )

        val job = testScope.launch {
            manager.pairWirelessDebugging("192.168.1.90", 37111, "654321")
        }

        try {
            assertTrue("Pairing must enter socket read", readEnteredLatch.await(3, TimeUnit.SECONDS))
            assertTrue(manager.pairingState.value is AdbPairingState.Pairing)

            // User cancels active pairing!
            manager.cancelWirelessPairing()

            // Verify socket close was triggered immediately and reader was unblocked
            assertTrue("Socket close must be called immediately upon cancellation", socketClosedLatch.await(3, TimeUnit.SECONDS))
            assertTrue("Reader thread must exit promptly upon socket closure", readerExitedLatch.await(3, TimeUnit.SECONDS))

            // Verify the job or coroutine cancellation completed
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(3000L) { job.join() }
            }
            assertTrue("Pairing job must terminate after cancellation", job.isCompleted)
            val finalState = manager.pairingState.value
            assertTrue("Pairing state must not stay stuck in Pairing ($finalState)", finalState is AdbPairingState.Failed)
            assertEquals(AdbDiagnosticCategory.PAIRING_FAILURE, manager.diagnosticCategory.value)
        } finally {
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun secondPairingAttemptDoesNotInterfereWithActiveFirstAttempt() {
        val store = AdbCredentialStore(null)
        val firstEnteredLatch = CountDownLatch(1)
        val allowFirstFinishLatch = CountDownLatch(1)
        val firstClosed = AtomicBoolean(false)
        val callsCount = AtomicInteger(0)

        val transport = AdbPairingTransport { host, port, _, _ ->
            val callNum = callsCount.incrementAndGet()
            if (callNum == 1) {
                firstEnteredLatch.countDown()
                allowFirstFinishLatch.await(5, TimeUnit.SECONDS)
                Result.success("Paired with TV ($host:$port)")
            } else {
                Result.success("Second attempt should never execute concurrently")
            }
        }

        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = Dispatchers.IO,
            scope = testScope,
            wirelessPairingTransport = transport
        )

        try {
            val firstDeferred = testScope.async {
                manager.pairWirelessDebugging("192.168.1.90", 37111, "654321")
            }
            assertTrue(firstEnteredLatch.await(3, TimeUnit.SECONDS))

            // Attempt 2 starts while Attempt 1 is still active -> MUST NOT cancel Attempt 1 or corrupt Pairing state!
            val secondRes = kotlinx.coroutines.runBlocking {
                manager.pairWirelessDebugging("192.168.1.90", 38999, "999888")
            }
            assertTrue("Second concurrent attempt must be rejected non-destructively", secondRes.isFailure)
            assertEquals(1, callsCount.get())
            assertFalse(firstClosed.get())
            val activeState = manager.pairingState.value as AdbPairingState.Pairing
            assertEquals(37111, activeState.pairingPort)

            // Allow Attempt 1 to finish -> must complete normally as Paired!
            allowFirstFinishLatch.countDown()
            val firstRes = kotlinx.coroutines.runBlocking { firstDeferred.await() }
            assertTrue("First attempt must finish cleanly: ${firstRes.exceptionOrNull()}", firstRes.isSuccess)
            assertTrue(manager.pairingState.value is AdbPairingState.Paired)
            assertEquals(AdbDiagnosticCategory.NONE, manager.diagnosticCategory.value)
            assertEquals(null, manager.activePairingJobForTest())
        } finally {
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun rejectedSecondAttemptPreservesFirstPairingJobCancellingTerminatesFirstAndSubsequentSucceeds() {
        val store = AdbCredentialStore(null)
        val ekm = ByteArray(64) { 7 }
        val firstReadEnteredLatch = CountDownLatch(1)
        val firstSocketClosedLatch = CountDownLatch(1)
        val firstReaderExitedLatch = CountDownLatch(1)
        val attemptCounter = AtomicInteger(0)

        val transport = NativeWirelessAdbPairing(store) { _, _, _ ->
            val attempt = attemptCounter.incrementAndGet()
            if (attempt == 1) {
                val closed = AtomicBoolean(false)
                val lock = Object()
                object : TlsPairingChannel {
                    override val input = object : InputStream() {
                        override fun read(): Int {
                            firstReadEnteredLatch.countDown()
                            synchronized(lock) {
                                while (!closed.get()) {
                                    lock.wait(5000L)
                                }
                            }
                            firstReaderExitedLatch.countDown()
                            throw IOException("First pairing socket closed on cancel")
                        }
                    }
                    override val output = ByteArrayOutputStream()
                    override val exportedKeyingMaterial = ekm
                    override fun close() {
                        closed.set(true)
                        synchronized(lock) { lock.notifyAll() }
                        firstSocketClosedLatch.countDown()
                    }
                }
            } else {
                // Subsequent attempt performs real SPAKE2 + AES-GCM exchange
                val code = "654321"
                val pw = code.toByteArray(Charsets.UTF_8) + ekm
                val serverSpake = Ed25519Spake2.Spake2Session(
                    role = Ed25519Spake2.Role.BOB_SERVER,
                    myName = NativeWirelessAdbPairing.SERVER_NAME,
                    theirName = NativeWirelessAdbPairing.CLIENT_NAME
                )
                val serverSpakeMsg = serverSpake.generateMessage(pw)
                val serverResponseStream = java.io.PipedOutputStream()
                val clientInput = java.io.PipedInputStream(serverResponseStream, 65536)
                val clientOutput = object : OutputStream() {
                    private val buf = ByteArrayOutputStream()
                    private var stage = 0
                    private var aesKey: javax.crypto.spec.SecretKeySpec? = null

                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        buf.write(b, off, len)
                        while (buf.size() >= 6) {
                            val all = buf.toByteArray()
                            val header = java.nio.ByteBuffer.wrap(all, 0, 6).order(java.nio.ByteOrder.BIG_ENDIAN)
                            header.get()
                            val type = header.get()
                            val payloadLen = header.getInt()
                            if (all.size < 6 + payloadLen) break
                            val payload = all.copyOfRange(6, 6 + payloadLen)
                            buf.reset()
                            if (all.size > 6 + payloadLen) {
                                buf.write(all, 6 + payloadLen, all.size - 6 - payloadLen)
                            }
                            if (stage == 0 && type == 0.toByte()) {
                                stage = 1
                                val shared = serverSpake.processMessage(payload)
                                val hkdfKey = NativeWirelessAdbPairing.hkdfSha256(
                                    shared, NativeWirelessAdbPairing.HKDF_INFO, 16
                                )
                                aesKey = javax.crypto.spec.SecretKeySpec(hkdfKey, "AES")
                                serverResponseStream.write(NativeWirelessAdbPairing.encodePacket(0, serverSpakeMsg))
                                serverResponseStream.flush()
                            } else if (stage == 1 && type == 1.toByte()) {
                                stage = 2
                                val serverPeer = ByteArray(8192).also {
                                    it[0] = 0
                                    "MoteTV-Subsequent-Ok".toByteArray().copyInto(it, 1)
                                }
                                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                                val nonce = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
                                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, aesKey, javax.crypto.spec.GCMParameterSpec(128, nonce))
                                serverResponseStream.write(
                                    NativeWirelessAdbPairing.encodePacket(1, cipher.doFinal(serverPeer))
                                )
                                serverResponseStream.flush()
                            }
                        }
                    }
                }
                object : TlsPairingChannel {
                    override val input = clientInput
                    override val output = clientOutput
                    override val exportedKeyingMaterial = ekm
                    override fun close() {
                        runCatching { serverResponseStream.close() }
                        runCatching { clientInput.close() }
                    }
                }
            }
        }

        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = Dispatchers.IO,
            scope = testScope,
            wirelessPairingTransport = transport
        )

        try {
            val firstJob = testScope.launch {
                manager.pairWirelessDebugging("192.168.1.90", 37111, "654321")
            }
            assertTrue(firstReadEnteredLatch.await(3, TimeUnit.SECONDS))
            val originalPairingJob = manager.activePairingJobForTest()
            assertTrue("First pairing job must be registered while active", originalPairingJob != null && originalPairingJob.isActive)

            // Rejected second attempt MUST NOT overwrite or clear Attempt 1's pairingJob!
            val secondResult = kotlinx.coroutines.runBlocking {
                manager.pairWirelessDebugging("192.168.1.90", 38999, "111222")
            }
            assertTrue(secondResult.isFailure)
            assertTrue(
                "Rejected second attempt must leave Attempt 1's pairingJob intact",
                manager.activePairingJobForTest() === originalPairingJob
            )
            assertEquals(1, attemptCounter.get())
            assertTrue("First job must still be running before explicit cancel", firstJob.isActive)

            // Cancelling now must close Attempt 1's native socket and terminate the first coroutine
            manager.cancelWirelessPairing()
            assertTrue(firstSocketClosedLatch.await(3, TimeUnit.SECONDS))
            assertTrue(firstReaderExitedLatch.await(3, TimeUnit.SECONDS))
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(3000L) { firstJob.join() } }
            assertTrue("First pairing coroutine must terminate on cancellation", firstJob.isCompleted)
            assertTrue("First pairing coroutine must be cancelled", firstJob.isCancelled)
            assertEquals("No stale pairing job may remain after cancellation", null, manager.activePairingJobForTest())

            // A subsequent pairing attempt MUST now succeed normally!
            val thirdResult = kotlinx.coroutines.runBlocking {
                manager.pairWirelessDebugging("192.168.1.90", 37111, "654321")
            }
            assertTrue("Subsequent pairing attempt must succeed: ${thirdResult.exceptionOrNull()}", thirdResult.isSuccess)
            assertTrue(manager.pairingState.value is AdbPairingState.Paired)
            assertEquals("No stale pairing job may remain after success", null, manager.activePairingJobForTest())
        } finally {
            testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun noStalePairingJobRemainsAfterSuccessFailureTimeoutOrCancellation() = runTest {
        val store = AdbCredentialStore(null)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var mode = "success"

        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = dispatcher,
            scope = scope,
            pairingTimeoutMs = 100L,
            wirelessPairingTransport = AdbPairingTransport { host, _, _, _ ->
                when (mode) {
                    "success" -> Result.success("Paired with $host")
                    "failure" -> Result.failure(AdbPairingException(AdbDiagnosticCategory.PAIRING_FAILURE, "Wrong pairing code"))
                    "timeout" -> {
                        delay(500L)
                        Result.success("Timed out")
                    }
                    else -> {
                        delay(5_000L)
                        Result.success("Cancelled")
                    }
                }
            }
        )

        // 1. Success -> no stale job
        mode = "success"
        assertTrue(manager.pairWirelessDebugging("192.168.1.55", 37111, "654321").isSuccess)
        assertEquals(null, manager.activePairingJobForTest())

        // 2. Validation failure + Transport failure -> no stale job
        assertTrue(manager.pairWirelessDebugging("192.168.1.55", 37111, "123").isFailure)
        assertEquals(null, manager.activePairingJobForTest())
        mode = "failure"
        assertTrue(manager.pairWirelessDebugging("192.168.1.55", 37111, "654321").isFailure)
        assertEquals(null, manager.activePairingJobForTest())

        // 3. Timeout -> no stale job
        mode = "timeout"
        assertTrue(manager.pairWirelessDebugging("192.168.1.55", 37111, "654321").isFailure)
        assertEquals(AdbDiagnosticCategory.CONNECTION_TIMEOUT, manager.diagnosticCategory.value)
        assertEquals(null, manager.activePairingJobForTest())

        // 4. Cancellation -> no stale job
        mode = "cancel"
        val cancelJob = scope.launch {
            manager.pairWirelessDebugging("192.168.1.55", 37111, "654321")
        }
        testScheduler.advanceTimeBy(20L)
        testScheduler.runCurrent()
        assertTrue(manager.activePairingJobForTest() != null)
        manager.cancelWirelessPairing()
        testScheduler.runCurrent()
        assertTrue(cancelJob.isCancelled)
        assertEquals(null, manager.activePairingJobForTest())

        // 5. Next attempt after all four outcomes still succeeds cleanly
        mode = "success"
        assertTrue(manager.pairWirelessDebugging("192.168.1.55", 37111, "654321").isSuccess)
        assertEquals(null, manager.activePairingJobForTest())
    }

    @Test
    fun successfulPairingFollowedByConnectOnSeparateConnectPortSucceeds() = runTest {
        val store = AdbCredentialStore(null)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val connectedPorts = mutableListOf<Int>()

        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = dispatcher,
            scope = scope,
            wirelessPairingTransport = AdbPairingTransport { host, pairingPort, code, _ ->
                assertEquals(37111, pairingPort)
                assertEquals("654321", code)
                Result.success("Paired with TV ($host)")
            },
            sessionFactory = { _, port, mode, _ ->
                connectedPorts.add(port)
                assertEquals(AdbConnectionMode.WIRELESS_DEBUGGING_TLS, mode)
                FakeAdbSession(
                    banner = "device::ro.product.model=MiTV-AESP0;",
                    propsStdout = "MFG=Xiaomi\nMOD=MiTV-AESP0\nDEV=aquaman\nBRD=aquaman\nREL=9\nSDK=28",
                    homeStdout = "com.google.android.tvlauncher/.MainActivity"
                )
            }
        )

        val pairRes = manager.pairWirelessDebugging("192.168.1.90", 37111, "654321")
        assertTrue(pairRes.isSuccess)
        assertTrue(manager.pairingState.value is AdbPairingState.Paired)

        // Now connect using the SEPARATE Wireless Debugging Connect port (NOT the pairing port!)
        manager.connect("192.168.1.90", 41555, AdbConnectionMode.WIRELESS_DEBUGGING_TLS)
        advanceUntilIdle()

        assertEquals(listOf(41555), connectedPorts)
        val conn = manager.connectionState.value as AdbConnectionState.Connected
        assertEquals(41555, conn.port)
        assertEquals(AdbConnectionMode.WIRELESS_DEBUGGING_TLS, conn.mode)
        assertEquals(41555, store.getSavedEndpoint()?.port)
    }

    @Test
    fun connectionManagerHandlesAuthApprovalDiagnosticsTimeoutAndDisconnectCleanup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val store = AdbCredentialStore(null)

        var awaitingCallbackFired = false
        val fakeSession = FakeAdbSession(
            banner = "device::ro.product.model=MiTV-AESP0;",
            propsStdout = """
                MFG=Xiaomi
                MOD=MiTV-AESP0
                DEV=aquaman
                BRD=aquaman
                REL=9
                SDK=28
                BID=PI.3115
                FGP=Xiaomi/aquaman/aquaman:9/PI/3115:user/release-keys
            """.trimIndent(),
            homeStdout = "com.google.android.tvlauncher/.MainActivity"
        )

        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = store,
            ioDispatcher = dispatcher,
            scope = scope,
            sessionFactory = { _, _, _, onAwaiting ->
                onAwaiting()
                awaitingCallbackFired = true
                fakeSession
            }
        )

        // Below Android 11 JVM environment reports API < 30 fallback cleanly
        assertFalse(manager.pairingCapability.isNativeSpake2Supported)
        val unsupportedPair = manager.pairWirelessDebugging("192.168.1.50", 37123, "123456")
        assertTrue(unsupportedPair.isFailure)
        assertEquals(AdbDiagnosticCategory.PAIRING_FAILURE, manager.diagnosticCategory.value)

        // Invalid endpoint rejected synchronously with category
        manager.connect("  ", 0, AdbConnectionMode.NETWORK_LEGACY_5555)
        val errState = manager.connectionState.value as AdbConnectionState.Error
        assertEquals(AdbDiagnosticCategory.TV_UNAVAILABLE, errState.category)

        // Valid connection inspects TV & stores endpoint
        manager.connect("192.168.1.88", 5555, AdbConnectionMode.NETWORK_LEGACY_5555)
        advanceUntilIdle()

        assertTrue(awaitingCallbackFired)
        val state = manager.connectionState.value
        assertTrue("Expected Connected state, got $state", state is AdbConnectionState.Connected)
        assertEquals(AdbDiagnosticCategory.NONE, manager.diagnosticCategory.value)

        // Read-back & existence over fake session
        val configPath = "/sdcard/Android/data/com.gathin.tvhome/files/config.json"
        assertFalse(manager.remoteFileExists(configPath).getOrThrow())
        assertTrue(manager.pushAppConfigFile(configPath, """{"columns":5}""".toByteArray()).isSuccess)
        assertTrue(manager.remoteFileExists(configPath).getOrThrow())
        assertEquals("""{"columns":5}""", manager.readRemoteFile(configPath).getOrThrow().decodeToString())

        // Unexpected socket drop during command cleans up session & moves to TV_DISCONNECTED Error state
        fakeSession.isConnected = false
        val afterDrop = manager.listAllPackages()
        assertTrue(afterDrop.isFailure)
        assertEquals(AdbDiagnosticCategory.TV_DISCONNECTED, manager.diagnosticCategory.value)
        assertTrue(manager.connectionState.value is AdbConnectionState.Error)
    }

    @Test
    fun pairingFailuresClassifyTimeoutAndEofProperly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = AdbCredentialStore(null),
            ioDispatcher = dispatcher,
            scope = scope,
            pairingTimeoutMs = 100L,
            wirelessPairingTransport = AdbPairingTransport { _, _, _, _ ->
                delay(500L)
                Result.success("Too late")
            }
        )

        assertTrue(manager.pairingCapability.isNativeSpake2Supported)
        assertTrue(manager.pairWirelessDebugging("192.168.1.40", 38111, "12345").isFailure)

        val timedOut = manager.pairWirelessDebugging("192.168.1.40", 38111, "654321")
        assertTrue(timedOut.isFailure)
        assertEquals(AdbDiagnosticCategory.CONNECTION_TIMEOUT, manager.diagnosticCategory.value)
        assertTrue(manager.pairingState.value is AdbPairingState.Failed)
    }

    @Test
    fun readRemoteFileThrowsWhenAdbNotConnectedAndInvalidPathBlocked() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = AdbCredentialStore(null),
            ioDispatcher = dispatcher,
            scope = scope,
            sessionFactory = { _, _, _, _ -> throw EOFException("Dropped") }
        )

        assertTrue(manager.readRemoteFile("/data/system/secret.xml").isFailure)
        assertTrue(manager.readRemoteFile("/sdcard/Android/data/com.gathin.tvhome/files/config.json").isFailure)
        assertEquals(AdbDiagnosticCategory.TV_DISCONNECTED, manager.diagnosticCategory.value)

        manager.connect("192.168.1.50", 5555, AdbConnectionMode.NETWORK_LEGACY_5555)
        advanceUntilIdle()
        assertEquals(AdbDiagnosticCategory.TV_DISCONNECTED, manager.diagnosticCategory.value)
    }

    private class FakeAdbSession(
        override val banner: String,
        private val propsStdout: String,
        private val homeStdout: String
    ) : AdbSession {
        override var isConnected: Boolean = true
        val files = linkedMapOf<String, ByteArray>()

        override suspend fun openShell(command: String): AdbCommandResult {
            return when {
                command.contains("getprop ro.product.manufacturer") -> AdbCommandResult(0, propsStdout)
                command.contains("resolve-activity") -> AdbCommandResult(0, homeStdout)
                command.startsWith("test -s ") -> {
                    val path = command.substringAfter("'").substringBefore("'")
                    AdbCommandResult(0, if (files.containsKey(path)) "EXISTS" else "MISSING")
                }
                else -> AdbCommandResult(0, "OK")
            }
        }

        override suspend fun pushFile(remotePath: String, content: ByteArray, mode: Int): AdbCommandResult {
            files[remotePath] = content
            return AdbCommandResult(0, "Pushed ${content.size} bytes to $remotePath")
        }

        override suspend fun pullFile(remotePath: String): ByteArray {
            return files[remotePath] ?: throw IOException("Remote file not found: $remotePath")
        }

        override fun close() {
            isConnected = false
        }
    }
}
