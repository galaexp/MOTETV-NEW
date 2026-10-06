package com.gala.motetv.pairing

import com.gala.motetv.pairing.crypto.PoloCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

class PairingCryptoTest {

    @Test
    fun testToUnsignedByteArrayStrippingLeadingZero() {
        val withLeadingZero = BigInteger(byteArrayOf(0x00, 0x80.toByte(), 0x01))
        val unsigned = PoloCrypto.toUnsignedByteArray(withLeadingZero)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), unsigned)

        val standard = BigInteger(byteArrayOf(0x7F, 0x02))
        val unsignedStandard = PoloCrypto.toUnsignedByteArray(standard)
        assertArrayEquals(byteArrayOf(0x7F, 0x02), unsignedStandard)
    }

    @Test
    fun testHexStringToByteArray() {
        val hexUpper = "A1B2"
        val bytesUpper = PoloCrypto.hexStringToByteArray(hexUpper)
        assertArrayEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte()), bytesUpper)

        val hexLower = "0a0f"
        val bytesLower = PoloCrypto.hexStringToByteArray(hexLower)
        assertArrayEquals(byteArrayOf(0x0A, 0x0F), bytesLower)
    }

    @Test
    fun testDeterministicSecretCalculation() {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)

        val clientKeyPair = keyGen.generateKeyPair()
        val serverKeyPair = keyGen.generateKeyPair()

        val clientPubKey = clientKeyPair.public as RSAPublicKey
        val serverPubKey = serverKeyPair.public as RSAPublicKey

        val pin = "12AB34" // 6 hex digits, pin[2:] = "AB34"

        val clientSecret = PoloCrypto.computeClientSecret(clientPubKey, serverPubKey, pin)
        val expectedServerSecret = PoloCrypto.computeExpectedServerSecret(clientPubKey, serverPubKey, pin)

        assertNotNull(clientSecret)
        assertEquals(32, clientSecret.size) // SHA-256 is 32 bytes
        assertEquals(32, expectedServerSecret.size)

        // Verify SHA-256 manual computation matches PoloCrypto helper without delimiter bytes
        val md = MessageDigest.getInstance("SHA-256")
        md.update(PoloCrypto.toUnsignedByteArray(clientPubKey.modulus))
        md.update(PoloCrypto.toUnsignedByteArray(clientPubKey.publicExponent))
        md.update(PoloCrypto.toUnsignedByteArray(serverPubKey.modulus))
        md.update(PoloCrypto.toUnsignedByteArray(serverPubKey.publicExponent))
        md.update(PoloCrypto.hexStringToByteArray("AB34"))
        val manualDigest = md.digest()

        assertArrayEquals(manualDigest, clientSecret)
    }
}
