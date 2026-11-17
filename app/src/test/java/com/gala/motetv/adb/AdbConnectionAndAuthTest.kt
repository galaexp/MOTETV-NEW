package com.gala.motetv.adb

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException
import java.security.interfaces.RSAPublicKey

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
        // 4 + 4 + 256 + 256 + 4 = 524 bytes exact Android RSAPublicKey C-struct
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
    fun connectionManagerHandlesAuthApprovalDiscoveryErrorAndStrictPathValidation() = runTest {
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

        // Honest Wireless Debugging SPAKE2 capability reporting when unprivileged
        assertFalse(manager.pairingCapability.isNativeSpake2Supported)
        val unsupportedPair = manager.pairWirelessDebugging("192.168.1.50", 37123, "123456")
        assertTrue(unsupportedPair.isFailure)

        // Invalid endpoint rejected synchronously
        manager.connect("  ", 0, AdbConnectionMode.NETWORK_LEGACY_5555)
        assertTrue(manager.connectionState.value is AdbConnectionState.Error)

        // Valid connection inspects TV & stores endpoint
        manager.connect("192.168.1.88", 5555, AdbConnectionMode.NETWORK_LEGACY_5555)
        advanceUntilIdle()

        assertTrue(awaitingCallbackFired)
        val state = manager.connectionState.value
        assertTrue("Expected Connected state, got $state", state is AdbConnectionState.Connected)
        val connected = state as AdbConnectionState.Connected
        assertEquals("Xiaomi", connected.deviceInfo?.manufacturer)
        assertEquals("MiTV-AESP0", connected.deviceInfo?.model)
        assertEquals("com.google.android.tvlauncher", connected.deviceInfo?.currentHomePackage)
        assertEquals("192.168.1.88", manager.savedEndpoint.value?.host)

        // Typed boundary guards reject injection & unauthorized paths
        assertTrue(manager.disablePackageForUser("com.evil; rm -rf /").isFailure)
        assertTrue(manager.pushAppConfigFile("/data/system/users/0/settings.xml", byteArrayOf(1)).isFailure)
        assertTrue(manager.pushAppConfigFile("/sdcard/Android/data/com.gathin.tvhome/files/config.json", "{}".toByteArray()).isSuccess)
    }

    @Test
    fun connectionManagerAcceptsInjectedPairingTransportWithValidSixDigitCode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val manager = AdbConnectionManagerImpl(
            context = null,
            credentialStore = AdbCredentialStore(null),
            ioDispatcher = dispatcher,
            scope = scope,
            wirelessPairingTransport = AdbPairingTransport { host, port, code, _ ->
                Result.success("Paired $host:$port with $code")
            }
        )

        assertTrue(manager.pairingCapability.isNativeSpake2Supported)
        assertTrue(manager.pairWirelessDebugging("192.168.1.40", 38111, "12345").isFailure)
        val ok = manager.pairWirelessDebugging("192.168.1.40", 38111, "654321")
        assertTrue(ok.isSuccess)
    }

    private class FakeAdbSession(
        override val banner: String,
        private val propsStdout: String,
        private val homeStdout: String
    ) : AdbSession {
        override var isConnected: Boolean = true
        val commands = mutableListOf<String>()

        override suspend fun openShell(command: String): AdbCommandResult {
            commands.add(command)
            return when {
                command.contains("getprop ro.product.manufacturer") -> AdbCommandResult(0, propsStdout)
                command.contains("resolve-activity") -> AdbCommandResult(0, homeStdout)
                else -> AdbCommandResult(0, "OK")
            }
        }

        override suspend fun pushFile(remotePath: String, content: ByteArray, mode: Int): AdbCommandResult {
            return AdbCommandResult(0, "Pushed ${content.size} bytes to $remotePath")
        }

        override fun close() {
            isConnected = false
        }
    }
}
