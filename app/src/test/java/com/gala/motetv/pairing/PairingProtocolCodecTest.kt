package com.gala.motetv.pairing

import com.gala.motetv.protocol.androidtv.codec.PairingProtoCodec
import com.gala.motetv.protocol.androidtv.model.EncodingType
import com.gala.motetv.protocol.androidtv.model.PairingConfiguration
import com.gala.motetv.protocol.androidtv.model.PairingConfigurationAck
import com.gala.motetv.protocol.androidtv.model.PairingMessage
import com.gala.motetv.protocol.androidtv.model.PairingOption
import com.gala.motetv.protocol.androidtv.model.PairingRequest
import com.gala.motetv.protocol.androidtv.model.PairingRequestAck
import com.gala.motetv.protocol.androidtv.model.PairingSecret
import com.gala.motetv.protocol.androidtv.model.PairingSecretAck
import com.gala.motetv.protocol.androidtv.model.RoleType
import com.gala.motetv.protocol.androidtv.model.Status
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PairingProtocolCodecTest {

    @Test
    fun testPairingRequestCodecRoundTrip() {
        val originalReq = PairingMessage(
            protocolVersion = 2,
            status = Status.OK,
            pairingRequest = PairingRequest(
                serviceName = "androidtv-remote",
                clientName = "MoteTV"
            )
        )

        val encoded = PairingProtoCodec.encode(originalReq)
        val decoded = PairingProtoCodec.decode(encoded)

        assertEquals(2, decoded.protocolVersion)
        assertEquals(Status.OK, decoded.status)
        assertNotNull(decoded.pairingRequest)
        assertEquals("androidtv-remote", decoded.pairingRequest?.serviceName)
        assertEquals("MoteTV", decoded.pairingRequest?.clientName)
    }

    @Test
    fun testPairingRequestAckCodecRoundTrip() {
        val originalAck = PairingMessage(
            protocolVersion = 2,
            status = Status.OK,
            pairingRequestAck = PairingRequestAck(
                status = Status.OK,
                roleType = RoleType.INPUT,
                protocolVersion = 2
            )
        )

        val encoded = PairingProtoCodec.encode(originalAck)
        val decoded = PairingProtoCodec.decode(encoded)

        assertEquals(2, decoded.protocolVersion)
        assertEquals(Status.OK, decoded.status)
        assertNotNull(decoded.pairingRequestAck)
        assertEquals(Status.OK, decoded.pairingRequestAck?.status)
        assertEquals(RoleType.INPUT, decoded.pairingRequestAck?.roleType)
        assertEquals(2, decoded.pairingRequestAck?.protocolVersion)
    }

    @Test
    fun testPairingOptionAndConfigurationCodec() {
        val optionMsg = PairingMessage(
            pairingOption = PairingOption(
                preferredRole = EncodingType.HEXADECIMAL,
                inputEncodings = listOf(EncodingType.HEXADECIMAL, EncodingType.ALPHANUMERIC),
                outputEncodings = listOf(EncodingType.HEXADECIMAL)
            )
        )
        val decodedOption = PairingProtoCodec.decode(PairingProtoCodec.encode(optionMsg))
        assertNotNull(decodedOption.pairingOption)
        assertEquals(EncodingType.HEXADECIMAL, decodedOption.pairingOption?.preferredRole)

        val configMsg = PairingMessage(
            pairingConfiguration = PairingConfiguration(
                encoding = EncodingType.HEXADECIMAL,
                clientRole = RoleType.INPUT
            )
        )
        val decodedConfig = PairingProtoCodec.decode(PairingProtoCodec.encode(configMsg))
        assertEquals(EncodingType.HEXADECIMAL, decodedConfig.pairingConfiguration?.encoding)
        assertEquals(RoleType.INPUT, decodedConfig.pairingConfiguration?.clientRole)
    }

    @Test
    fun testPairingSecretAndAckCodec() {
        val secretBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val secretMsg = PairingMessage(
            pairingSecret = PairingSecret(secret = secretBytes)
        )
        val decodedSecret = PairingProtoCodec.decode(PairingProtoCodec.encode(secretMsg))
        assertArrayEquals(secretBytes, decodedSecret.pairingSecret?.secret)

        val ackMsg = PairingMessage(
            pairingSecretAck = PairingSecretAck(
                status = Status.OK,
                secret = secretBytes
            )
        )
        val decodedAck = PairingProtoCodec.decode(PairingProtoCodec.encode(ackMsg))
        assertEquals(Status.OK, decodedAck.pairingSecretAck?.status)
        assertArrayEquals(secretBytes, decodedAck.pairingSecretAck?.secret)
    }
}
