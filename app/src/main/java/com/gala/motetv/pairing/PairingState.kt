package com.gala.motetv.pairing

import com.gala.motetv.core.model.TvDevice
import java.security.cert.X509Certificate

sealed class PairingState {
    data object Idle : PairingState()
    data class Connecting(val host: String, val port: Int) : PairingState()
    data object TlsHandshaking : PairingState()
    data class TlsConnected(val serverCertificate: X509Certificate) : PairingState()
    data object SendingPairingRequest : PairingState()
    data object WaitingPairingRequestAck : PairingState()
    data object SendingOptions : PairingState()
    data object WaitingOptionsResponse : PairingState()
    data object SendingConfiguration : PairingState()
    data object WaitingConfigurationAck : PairingState()
    data class WaitingForUserPin(
        val tvName: String,
        val prompt: String,
        val errorMessage: String? = null,
        val isSubmitting: Boolean = false
    ) : PairingState()
    data object SendingSecret : PairingState()
    data object WaitingSecretAck : PairingState()
    data class Paired(val device: TvDevice) : PairingState()
    data class Failed(
        val errorCode: String,
        val userMessage: String,
        val technicalDetails: String? = null
    ) : PairingState()
    data object Cancelled : PairingState()
}
