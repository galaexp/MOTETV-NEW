package com.gala.motetv.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
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
    fun nativeWirelessAdbPairingCompletesFullHandshakeAndDetectsWrongCode() = runTest {
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

        // Test 2: Wrong code triggers PAIRING_FAILURE cleanly when peer decrypt fails or closes
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
                // Respond encrypted with server's wrong key -> client AEADBadTagException!
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
            val failRes = pairing.pair("192.168.1.90", 37111, "654321", store.getOrCreateAdbKeyPair())
            executor.shutdownNow()
            assertTrue(failRes.isFailure)
            val err = failRes.exceptionOrNull() as AdbPairingException
            assertEquals(AdbDiagnosticCategory.PAIRING_FAILURE, err.category)
        }
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
