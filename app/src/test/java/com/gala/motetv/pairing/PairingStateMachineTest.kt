package com.gala.motetv.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import polo.wire.protobuf.Configuration
import polo.wire.protobuf.ConfigurationAck
import polo.wire.protobuf.Encoding
import polo.wire.protobuf.EncodingType
import polo.wire.protobuf.MessageType
import polo.wire.protobuf.Options
import polo.wire.protobuf.OuterMessage
import polo.wire.protobuf.PairingRequest
import polo.wire.protobuf.PairingRequestAck
import polo.wire.protobuf.RoleType
import polo.wire.protobuf.Status

class PairingStateMachineTest {

    enum class TestPairingStage {
        IDLE,
        TLS_CONNECTED,
        PAIRING_REQUEST_SENT,
        PAIRING_REQUEST_ACK_RECEIVED,
        OPTIONS_SENT,
        OPTIONS_RECEIVED,
        CONFIGURATION_SENT,
        CONFIGURATION_ACK_RECEIVED,
        WAITING_FOR_PIN,
        SECRET_SENT,
        SECRET_ACK_RECEIVED,
        PAIRED,
        FAILED
    }

    class PairingFlowHarness {
        var currentStage: TestPairingStage = TestPairingStage.IDLE
        val history = mutableListOf<TestPairingStage>()
        var failureReason: String? = null

        fun onTlsConnected() {
            advance(TestPairingStage.TLS_CONNECTED)
        }

        fun sendPairingRequest(): OuterMessage {
            check(currentStage == TestPairingStage.TLS_CONNECTED) { "Cannot send PairingRequest before TLS connected" }
            advance(TestPairingStage.PAIRING_REQUEST_SENT)
            return OuterMessage(
                protocol_version = 2,
                status = Status.STATUS_OK,
                type = MessageType.MESSAGE_TYPE_PAIRING_REQUEST,
                pairing_request = PairingRequest(service_name = "androidtvremote", client_name = "MoteTV")
            )
        }

        fun onPairingRequestAckReceived(ackMsg: OuterMessage) {
            check(currentStage == TestPairingStage.PAIRING_REQUEST_SENT) { "Cannot receive PairingRequestAck before request sent" }
            if (ackMsg.status == Status.STATUS_ERROR || ackMsg.status == Status.STATUS_BAD_CONFIGURATION) {
                fail("PairingRequest rejected: ${ackMsg.status}")
                return
            }
            advance(TestPairingStage.PAIRING_REQUEST_ACK_RECEIVED)
        }

        fun sendOptions(): OuterMessage {
            check(currentStage == TestPairingStage.PAIRING_REQUEST_ACK_RECEIVED) { "Cannot send Options before PairingRequestAck" }
            advance(TestPairingStage.OPTIONS_SENT)
            return OuterMessage(
                protocol_version = 2,
                status = Status.STATUS_OK,
                type = MessageType.MESSAGE_TYPE_OPTIONS,
                options = Options(
                    input_encodings = listOf(Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)),
                    output_encodings = listOf(Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)),
                    preferred_role = RoleType.ROLE_TYPE_INPUT
                )
            )
        }

        fun onOptionsResponseReceived(optMsg: OuterMessage) {
            check(currentStage == TestPairingStage.OPTIONS_SENT) { "Cannot receive Options before Options sent" }
            if (optMsg.status == Status.STATUS_ERROR || optMsg.status == Status.STATUS_BAD_CONFIGURATION) {
                fail("Options rejected: ${optMsg.status}")
                return
            }
            advance(TestPairingStage.OPTIONS_RECEIVED)
        }

        fun sendConfiguration(): OuterMessage {
            check(currentStage == TestPairingStage.OPTIONS_RECEIVED) {
                "PROTOCOL VIOLATION: Configuration cannot be sent before Options response is received from TV! Current stage: $currentStage"
            }
            advance(TestPairingStage.CONFIGURATION_SENT)
            return OuterMessage(
                protocol_version = 2,
                status = Status.STATUS_OK,
                type = MessageType.MESSAGE_TYPE_CONFIGURATION,
                configuration = Configuration(
                    encoding = Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6),
                    client_role = RoleType.ROLE_TYPE_INPUT
                )
            )
        }

        fun onConfigurationAckReceived(cfgAckMsg: OuterMessage) {
            check(currentStage == TestPairingStage.CONFIGURATION_SENT) { "Cannot receive ConfigurationAck before Configuration sent" }

            if (cfgAckMsg.status == Status.STATUS_ERROR ||
                cfgAckMsg.status == Status.STATUS_BAD_CONFIGURATION ||
                cfgAckMsg.status == Status.STATUS_BAD_SECRET
            ) {
                fail("TV rejected configuration with status ${cfgAckMsg.status}")
                return
            }

            advance(TestPairingStage.CONFIGURATION_ACK_RECEIVED)
            advance(TestPairingStage.WAITING_FOR_PIN)
        }

        private fun advance(stage: TestPairingStage) {
            currentStage = stage
            history.add(stage)
        }

        private fun fail(reason: String) {
            currentStage = TestPairingStage.FAILED
            failureReason = reason
            history.add(TestPairingStage.FAILED)
        }
    }

    @Test
    fun testConfigurationAckStatusOkAccepted() {
        val harness = PairingFlowHarness()

        harness.onTlsConnected()
        harness.sendPairingRequest()
        harness.onPairingRequestAckReceived(OuterMessage(status = Status.STATUS_OK, pairing_request_ack = PairingRequestAck(server_name = "Mi TV Stick")))
        harness.sendOptions()
        harness.onOptionsResponseReceived(OuterMessage(status = Status.STATUS_OK, options = Options(preferred_role = RoleType.ROLE_TYPE_INPUT)))
        harness.sendConfiguration()

        val okAck = OuterMessage(
            status = Status.STATUS_OK,
            type = MessageType.MESSAGE_TYPE_CONFIGURATION_ACK,
            configuration_ack = ConfigurationAck()
        )
        harness.onConfigurationAckReceived(okAck)

        assertEquals(TestPairingStage.WAITING_FOR_PIN, harness.currentStage)
        assertEquals(null, harness.failureReason)
        assertTrue(harness.history.contains(TestPairingStage.WAITING_FOR_PIN))
    }

    @Test
    fun testConfigurationAckBadConfigurationRejected() {
        val harness = PairingFlowHarness()

        harness.onTlsConnected()
        harness.sendPairingRequest()
        harness.onPairingRequestAckReceived(OuterMessage(status = Status.STATUS_OK, pairing_request_ack = PairingRequestAck(server_name = "Mi TV Stick")))
        harness.sendOptions()
        harness.onOptionsResponseReceived(OuterMessage(status = Status.STATUS_OK, options = Options(preferred_role = RoleType.ROLE_TYPE_INPUT)))
        harness.sendConfiguration()

        val badAck = OuterMessage(
            status = Status.STATUS_ERROR,
            type = MessageType.MESSAGE_TYPE_CONFIGURATION_ACK,
            configuration_ack = ConfigurationAck()
        )
        harness.onConfigurationAckReceived(badAck)

        assertEquals(TestPairingStage.FAILED, harness.currentStage)
        assertTrue(harness.failureReason?.contains("STATUS_ERROR") == true)
        assertFalse(harness.history.contains(TestPairingStage.WAITING_FOR_PIN))
    }

    @Test(expected = IllegalStateException::class)
    fun testConfigurationSentPrematurelyThrowsViolation() {
        val harness = PairingFlowHarness()
        harness.onTlsConnected()
        harness.sendPairingRequest()
        harness.onPairingRequestAckReceived(OuterMessage(status = Status.STATUS_OK, pairing_request_ack = PairingRequestAck(server_name = "Mi TV Stick")))
        harness.sendOptions()

        // Sending Configuration prematurely without waiting for TV Options response MUST fail
        harness.sendConfiguration()
    }
}
