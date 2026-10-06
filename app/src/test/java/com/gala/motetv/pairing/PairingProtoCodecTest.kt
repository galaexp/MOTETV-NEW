package com.gala.motetv.pairing

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import polo.wire.protobuf.Configuration
import polo.wire.protobuf.ConfigurationAck
import polo.wire.protobuf.Encoding
import polo.wire.protobuf.EncodingType
import polo.wire.protobuf.MessageType
import polo.wire.protobuf.Options
import polo.wire.protobuf.OuterMessage
import polo.wire.protobuf.PairingRequest
import polo.wire.protobuf.RoleType
import polo.wire.protobuf.Secret
import polo.wire.protobuf.SecretAck
import polo.wire.protobuf.Status

class PairingProtoCodecTest {

    @Test
    fun testConfigurationGoldenBytes() {
        val config = Configuration(
            encoding = Encoding(
                type = EncodingType.ENCODING_TYPE_HEXADECIMAL,
                symbol_length = 6
            ),
            client_role = RoleType.ROLE_TYPE_INPUT
        )

        val serialized = config.encode()
        val expectedGoldenBytes = byteArrayOf(
            0x0A.toByte(), 0x04.toByte(),
            0x08.toByte(), 0x03.toByte(),
            0x10.toByte(), 0x06.toByte(),
            0x10.toByte(), 0x01.toByte()
        )

        assertArrayEquals(expectedGoldenBytes, serialized)

        // Decode back and verify fields
        val decoded = Configuration.ADAPTER.decode(serialized)
        assertNotNull(decoded.encoding)
        assertEquals(EncodingType.ENCODING_TYPE_HEXADECIMAL, decoded.encoding?.type)
        assertEquals(6, decoded.encoding?.symbol_length)
        assertEquals(RoleType.ROLE_TYPE_INPUT, decoded.client_role)
    }

    @Test
    fun testOuterMessageConfigurationRoundTrip() {
        val config = Configuration(
            encoding = Encoding(
                type = EncodingType.ENCODING_TYPE_HEXADECIMAL,
                symbol_length = 6
            ),
            client_role = RoleType.ROLE_TYPE_INPUT
        )

        val outer = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_CONFIGURATION,
            configuration = config
        )

        val serialized = outer.encode()
        val decoded = OuterMessage.ADAPTER.decode(serialized)

        assertEquals(2, decoded.protocol_version)
        assertEquals(Status.STATUS_OK, decoded.status)
        assertEquals(MessageType.MESSAGE_TYPE_CONFIGURATION, decoded.type)
        assertNotNull(decoded.configuration)
        assertEquals(EncodingType.ENCODING_TYPE_HEXADECIMAL, decoded.configuration?.encoding?.type)
        assertEquals(6, decoded.configuration?.encoding?.symbol_length)
        assertEquals(RoleType.ROLE_TYPE_INPUT, decoded.configuration?.client_role)
    }

    @Test
    fun testTvOptionsWithVarintEncodingsDecodesWithoutBeginMessageException() {
        // Raw bytes representing TV Options where input_encodings/output_encodings are sent as varint enums:
        // OuterMessage: protocol_version=2 (08 02), status=200 (10 C8 01), type=20 (18 14), options (A2 01 06 08 01 10 03 18 03)
        val tvBytes = byteArrayOf(
            0x08, 0x02,                     // tag 1: uint32 = 2
            0x10, 0xC8.toByte(), 0x01,       // tag 2: status = 200 (STATUS_OK)
            0x18, 0x14,                     // tag 3: type = 20 (OPTIONS)
            0xA2.toByte(), 0x01, 0x06,       // tag 20: Options (length 6)
            0x08, 0x01,                     //   tag 1: preferred_role = 1 (INPUT)
            0x10, 0x03,                     //   tag 2: input_encodings = 3 (HEXADECIMAL)
            0x18, 0x03                      //   tag 3: output_encodings = 3 (HEXADECIMAL)
        )

        val decoded = OuterMessage.ADAPTER.decode(tvBytes)
        assertEquals(2, decoded.protocol_version)
        assertEquals(Status.STATUS_OK, decoded.status)
        assertEquals(MessageType.MESSAGE_TYPE_OPTIONS, decoded.type)
        assertNotNull(decoded.options)
        assertEquals(RoleType.ROLE_TYPE_INPUT, decoded.options?.preferred_role)
        assertEquals(1, decoded.options?.input_encodings?.size)
        assertEquals(EncodingType.ENCODING_TYPE_HEXADECIMAL, decoded.options?.input_encodings?.get(0)?.type)
        assertEquals(1, decoded.options?.output_encodings?.size)
        assertEquals(EncodingType.ENCODING_TYPE_HEXADECIMAL, decoded.options?.output_encodings?.get(0)?.type)
    }

    @Test
    fun testPairingRequestRoundTrip() {
        val originalReq = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_PAIRING_REQUEST,
            pairing_request = PairingRequest(
                service_name = "androidtvremote",
                client_name = "MoteTV"
            )
        )

        val encoded = originalReq.encode()
        val decoded = OuterMessage.ADAPTER.decode(encoded)

        assertEquals(2, decoded.protocol_version)
        assertEquals(Status.STATUS_OK, decoded.status)
        assertEquals(MessageType.MESSAGE_TYPE_PAIRING_REQUEST, decoded.type)
        assertNotNull(decoded.pairing_request)
        assertEquals("androidtvremote", decoded.pairing_request?.service_name)
        assertEquals("MoteTV", decoded.pairing_request?.client_name)
    }

    @Test
    fun testOptionsRoundTrip() {
        val optionMsg = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_OPTIONS,
            options = Options(
                input_encodings = listOf(Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)),
                output_encodings = listOf(Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)),
                preferred_role = RoleType.ROLE_TYPE_INPUT
            )
        )

        val decodedOpt = OuterMessage.ADAPTER.decode(optionMsg.encode())
        assertNotNull(decodedOpt.options)
        assertEquals(1, decodedOpt.options?.input_encodings?.size)
        assertEquals(EncodingType.ENCODING_TYPE_HEXADECIMAL, decodedOpt.options?.input_encodings?.get(0)?.type)
        assertEquals(6, decodedOpt.options?.input_encodings?.get(0)?.symbol_length)
        assertEquals(RoleType.ROLE_TYPE_INPUT, decodedOpt.options?.preferred_role)
    }

    @Test
    fun testConfigurationAckRoundTrip() {
        val configAckMsg = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_CONFIGURATION_ACK,
            configuration_ack = ConfigurationAck()
        )
        val decodedCfgAck = OuterMessage.ADAPTER.decode(configAckMsg.encode())
        assertEquals(Status.STATUS_OK, decodedCfgAck.status)
        assertEquals(MessageType.MESSAGE_TYPE_CONFIGURATION_ACK, decodedCfgAck.type)
        assertNotNull(decodedCfgAck.configuration_ack)
    }

    @Test
    fun testSecretAndAckRoundTrip() {
        val secretBytes = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E)
        val secretMsg = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_SECRET,
            secret = Secret(secret = secretBytes.toByteString())
        )

        val decodedSec = OuterMessage.ADAPTER.decode(secretMsg.encode())
        assertEquals(secretBytes.toByteString(), decodedSec.secret?.secret)

        val secretAckMsg = OuterMessage(
            protocol_version = 2,
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_SECRET_ACK,
            secret_ack = SecretAck(secret = secretBytes.toByteString())
        )
        val decodedSecAck = OuterMessage.ADAPTER.decode(secretAckMsg.encode())
        assertEquals(Status.STATUS_OK, decodedSecAck.status)
        assertEquals(MessageType.MESSAGE_TYPE_SECRET_ACK, decodedSecAck.type)
        assertEquals(secretBytes.toByteString(), decodedSecAck.secret_ack?.secret)
    }
}
