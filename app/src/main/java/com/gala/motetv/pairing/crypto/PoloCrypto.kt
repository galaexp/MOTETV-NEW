package com.gala.motetv.pairing.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

object PoloCrypto {

    /**
     * Converts a BigInteger to unsigned Big-Endian ByteArray (stripping leading sign byte 0x00 if present).
     */
    fun toUnsignedByteArray(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    /**
     * Decodes a hex string into a ByteArray.
     */
    fun hexStringToByteArray(hex: String): ByteArray {
        val cleanHex = hex.trim().filter { it.isLetterOrDigit() }
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun toUnsignedFixedLengthByteArray(value: BigInteger, expectedSize: Int = 256): ByteArray {
        val unpadded = toUnsignedByteArray(value)
        if (unpadded.size == expectedSize) return unpadded
        if (unpadded.size < expectedSize) {
            val padded = ByteArray(expectedSize)
            System.arraycopy(unpadded, 0, padded, expectedSize - unpadded.size, unpadded.size)
            return padded
        }
        if (unpadded.size > expectedSize) {
            return unpadded.copyOfRange(unpadded.size - expectedSize, unpadded.size)
        }
        return unpadded
    }

    /**
     * Builds the exact hash input byte array for the client secret:
     * client_modulus + client_exponent + server_modulus + server_exponent + bytes.fromhex(pin[2:])
     * Reference: louis49/androidtv-remote, kud/androidtv-remote, tronikos/androidtvremote2
     * Note: NO 0x00 delimiter bytes between fields.
     */
    fun buildSecretHashInput(
        clientPubKey: RSAPublicKey,
        serverPubKey: RSAPublicKey,
        pinHex: String
    ): ByteArray {
        val clientModulus = toUnsignedFixedLengthByteArray(clientPubKey.modulus, 256)
        val clientExponent = toUnsignedByteArray(clientPubKey.publicExponent)
        val serverModulus = toUnsignedFixedLengthByteArray(serverPubKey.modulus, 256)
        val serverExponent = toUnsignedByteArray(serverPubKey.publicExponent)

        val pinSubstring = if (pinHex.length >= 6) pinHex.substring(2) else pinHex
        val pinBytes = hexStringToByteArray(pinSubstring)

        val totalLength = clientModulus.size + clientExponent.size + serverModulus.size + serverExponent.size + pinBytes.size
        val out = ByteArray(totalLength)
        var offset = 0

        System.arraycopy(clientModulus, 0, out, offset, clientModulus.size)
        offset += clientModulus.size

        System.arraycopy(clientExponent, 0, out, offset, clientExponent.size)
        offset += clientExponent.size

        System.arraycopy(serverModulus, 0, out, offset, serverModulus.size)
        offset += serverModulus.size

        System.arraycopy(serverExponent, 0, out, offset, serverExponent.size)
        offset += serverExponent.size

        System.arraycopy(pinBytes, 0, out, offset, pinBytes.size)

        return out
    }

    /**
     * Computes the client secret for Android TV Remote v2 pairing:
     * SHA256(client_n + client_e + server_n + server_e + bytes.fromhex(pin[2:]))
     */
    fun computeClientSecret(
        clientPubKey: RSAPublicKey,
        serverPubKey: RSAPublicKey,
        pinHex: String
    ): ByteArray {
        val hashInput = buildSecretHashInput(clientPubKey, serverPubKey, pinHex)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(hashInput)
    }

    /**
     * Builds the exact hash input byte array for expected server secret verification:
     * server_modulus + server_exponent + client_modulus + client_exponent + bytes.fromhex(pin[2:])
     */
    fun buildServerSecretHashInput(
        clientPubKey: RSAPublicKey,
        serverPubKey: RSAPublicKey,
        pinHex: String
    ): ByteArray {
        val clientModulus = toUnsignedFixedLengthByteArray(clientPubKey.modulus, 256)
        val clientExponent = toUnsignedByteArray(clientPubKey.publicExponent)
        val serverModulus = toUnsignedFixedLengthByteArray(serverPubKey.modulus, 256)
        val serverExponent = toUnsignedByteArray(serverPubKey.publicExponent)

        val pinSubstring = if (pinHex.length >= 6) pinHex.substring(2) else pinHex
        val pinBytes = hexStringToByteArray(pinSubstring)

        val totalLength = serverModulus.size + serverExponent.size + clientModulus.size + clientExponent.size + pinBytes.size
        val out = ByteArray(totalLength)
        var offset = 0

        System.arraycopy(serverModulus, 0, out, offset, serverModulus.size)
        offset += serverModulus.size

        System.arraycopy(serverExponent, 0, out, offset, serverExponent.size)
        offset += serverExponent.size

        System.arraycopy(clientModulus, 0, out, offset, clientModulus.size)
        offset += clientModulus.size

        System.arraycopy(clientExponent, 0, out, offset, clientExponent.size)
        offset += clientExponent.size

        System.arraycopy(pinBytes, 0, out, offset, pinBytes.size)

        return out
    }

    /**
     * Computes expected server secret for Android TV Remote v2 pairing verification:
     * SHA256(server_n + server_e + client_n + client_e + bytes.fromhex(pin[2:]))
     */
    fun computeExpectedServerSecret(
        clientPubKey: RSAPublicKey,
        serverPubKey: RSAPublicKey,
        pinHex: String
    ): ByteArray {
        val hashInput = buildServerSecretHashInput(clientPubKey, serverPubKey, pinHex)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(hashInput)
    }

    /**
     * Computes SHA-256 fingerprint of a public key's SubjectPublicKeyInfo encoding.
     */
    fun computePublicKeyFingerprint(pubKey: RSAPublicKey): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(pubKey.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
