package com.gala.motetv.pairing

import com.gala.motetv.pairing.crypto.PoloCrypto
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import polo.wire.protobuf.MessageType
import polo.wire.protobuf.OuterMessage
import polo.wire.protobuf.Secret
import polo.wire.protobuf.Status
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

class PoloCryptoDeterministicTest {

    private fun createDeterministicRsaPublicKey(modulusBytes: ByteArray, exponent: Long = 65537L): RSAPublicKey {
        val kf = KeyFactory.getInstance("RSA")
        // Create positive BigInteger from bytes
        val modBigInt = BigInteger(1, modulusBytes)
        val expBigInt = BigInteger.valueOf(exponent)
        val spec = RSAPublicKeySpec(modBigInt, expBigInt)
        return kf.generatePublic(spec) as RSAPublicKey
    }

    @Test
    fun testDeterministicSecretHashInputAndOutput() {
        // Fixed 256-byte (2048-bit) client modulus with deterministic pattern
        val clientModulusBytes = ByteArray(256) { (it and 0xFF).toByte() }
        // Ensure MSB is set so BigInteger represents a full 2048-bit integer
        clientModulusBytes[0] = 0x80.toByte()

        // Fixed 256-byte (2048-bit) server modulus with distinct deterministic pattern
        val serverModulusBytes = ByteArray(256) { ((255 - it) and 0xFF).toByte() }
        serverModulusBytes[0] = 0x90.toByte()

        val clientPubKey = createDeterministicRsaPublicKey(clientModulusBytes, 65537L)
        val serverPubKey = createDeterministicRsaPublicKey(serverModulusBytes, 65537L)

        val pin = "3F8A1B" // 6-character hex PIN displayed on TV
        // In protocol: pin[2:] = "8A1B" -> 2 bytes [0x8A, 0x1B]
        val expectedPinBytes = byteArrayOf(0x8A.toByte(), 0x1B.toByte())
        val expectedExponentBytes = byteArrayOf(0x01, 0x00, 0x01)

        val hashInput = PoloCrypto.buildSecretHashInput(clientPubKey, serverPubKey, pin)

        // 1. Verify exact total length: 256 + 3 + 256 + 3 + 2 = 520 bytes
        assertEquals(520, hashInput.size)

        // 2. Verify exact byte segments entering SHA-256:
        val clientModulusInHash = hashInput.copyOfRange(0, 256)
        val clientExpInHash = hashInput.copyOfRange(256, 259)
        val serverModulusInHash = hashInput.copyOfRange(259, 515)
        val serverExpInHash = hashInput.copyOfRange(515, 518)
        val pinBytesInHash = hashInput.copyOfRange(518, 520)

        assertArrayEquals("Client modulus in hash input mismatch", clientModulusBytes, clientModulusInHash)
        assertArrayEquals("Client exponent in hash input mismatch", expectedExponentBytes, clientExpInHash)
        assertArrayEquals("Server modulus in hash input mismatch", serverModulusBytes, serverModulusInHash)
        assertArrayEquals("Server exponent in hash input mismatch", expectedExponentBytes, serverExpInHash)
        assertArrayEquals("PIN bytes in hash input mismatch", expectedPinBytes, pinBytesInHash)

        // 3. Compute reference SHA-256 independently
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(hashInput)
        val computedSecret = PoloCrypto.computeClientSecret(clientPubKey, serverPubKey, pin)

        assertEquals(32, computedSecret.size)
        assertArrayEquals(expectedSha256, computedSecret)

        // 4. Verify Secret protobuf serialization
        val secretMsg = Secret(secret = computedSecret.toByteString())
        val secretBytes = secretMsg.encode()

        // Tag 1 (field 1, wire type 2 = 0x0A), length 32 (0x20), followed by 32 bytes
        assertEquals(34, secretBytes.size)
        assertEquals(0x0A.toByte(), secretBytes[0])
        assertEquals(0x20.toByte(), secretBytes[1])
        assertArrayEquals(computedSecret, secretBytes.copyOfRange(2, 34))

        // 5. Verify OuterMessage serialization
        val outerMsg = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_SECRET,
            secret = secretMsg
        )
        val outerBytes = outerMsg.encode()
        assertEquals(44, outerBytes.size)

        // Decode back and verify exact fields
        val decodedOuter = OuterMessage.ADAPTER.decode(outerBytes)
        assertEquals(2, decodedOuter.protocol_version)
        assertEquals(Status.STATUS_OK, decodedOuter.status)
        assertEquals(MessageType.MESSAGE_TYPE_SECRET, decodedOuter.type)
        assertNotNull(decodedOuter.secret)
        assertArrayEquals(computedSecret, decodedOuter.secret?.secret?.toByteArray())
    }

    @Test
    fun testServerSecretSymmetry() {
        val clientModulusBytes = ByteArray(256) { 0x11.toByte() }.apply { this[0] = 0x80.toByte() }
        val serverModulusBytes = ByteArray(256) { 0x22.toByte() }.apply { this[0] = 0x80.toByte() }

        val clientPubKey = createDeterministicRsaPublicKey(clientModulusBytes, 65537L)
        val serverPubKey = createDeterministicRsaPublicKey(serverModulusBytes, 65537L)
        val pin = "123456"

        val serverHashInput = PoloCrypto.buildServerSecretHashInput(clientPubKey, serverPubKey, pin)
        assertEquals(520, serverHashInput.size)

        // Server hash input starts with server modulus, then server exponent, then client modulus, then client exponent, then PIN
        assertArrayEquals(serverModulusBytes, serverHashInput.copyOfRange(0, 256))
        assertArrayEquals(clientModulusBytes, serverHashInput.copyOfRange(259, 515))
        assertArrayEquals(byteArrayOf(0x34.toByte(), 0x56.toByte()), serverHashInput.copyOfRange(518, 520))

        val serverSecret = PoloCrypto.computeExpectedServerSecret(clientPubKey, serverPubKey, pin)
        assertEquals(32, serverSecret.size)
    }

    @Test
    fun testHexStringToByteArray() {
        val hex = "1A2B3C"
        val bytes = PoloCrypto.hexStringToByteArray(hex)
        val expected = byteArrayOf(0x1A.toByte(), 0x2B.toByte(), 0x3C.toByte())
        assertArrayEquals(expected, bytes)
    }
}
