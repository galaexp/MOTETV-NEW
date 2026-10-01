package com.gala.motetv.protocol.androidtv.model

enum class Status(val value: Int) {
    UNKNOWN(0),
    OK(200),
    ERROR(400),
    BAD_CONFIGURATION(401),
    BAD_SECRET(402);

    companion object {
        fun fromValue(v: Int): Status = entries.find { it.value == v } ?: UNKNOWN
    }
}

enum class RoleType(val value: Int) {
    UNKNOWN(0),
    INPUT(1),
    OUTPUT(2),
    CONTROLLER(3);

    companion object {
        fun fromValue(v: Int): RoleType = entries.find { it.value == v } ?: UNKNOWN
    }
}

enum class EncodingType(val value: Int) {
    UNKNOWN(0),
    ALPHANUMERIC(1),
    NUMERIC(2),
    HEXADECIMAL(3),
    QRCODE(4);

    companion object {
        fun fromValue(v: Int): EncodingType = entries.find { it.value == v } ?: UNKNOWN
    }
}

data class PairingRequest(
    val serviceName: String = "androidtv-remote",
    val clientName: String = "MoteTV"
)

data class PairingRequestAck(
    val status: Status = Status.OK,
    val roleType: RoleType = RoleType.INPUT,
    val protocolVersion: Int = 2
)

data class PairingOption(
    val preferredRole: EncodingType = EncodingType.HEXADECIMAL,
    val inputEncodings: List<EncodingType> = emptyList(),
    val outputEncodings: List<EncodingType> = emptyList()
)

data class PairingConfiguration(
    val encoding: EncodingType = EncodingType.HEXADECIMAL,
    val clientRole: RoleType = RoleType.INPUT
)

data class PairingConfigurationAck(
    val status: Status = Status.OK
)

data class PairingSecret(
    val secret: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingSecret) return false
        return secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int = secret.contentHashCode()
}

data class PairingSecretAck(
    val status: Status = Status.OK,
    val secret: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingSecretAck) return false
        if (status != other.status) return false
        if (secret != null) {
            if (other.secret == null) return false
            if (!secret.contentEquals(other.secret)) return false
        } else if (other.secret != null) return false
        return true
    }

    override fun hashCode(): Int = (status.hashCode() * 31) + (secret?.contentHashCode() ?: 0)
}

data class PairingMessage(
    val protocolVersion: Int = 2,
    val status: Status = Status.OK,
    val pairingRequest: PairingRequest? = null,
    val pairingRequestAck: PairingRequestAck? = null,
    val pairingOption: PairingOption? = null,
    val pairingConfiguration: PairingConfiguration? = null,
    val pairingConfigurationAck: PairingConfigurationAck? = null,
    val pairingSecret: PairingSecret? = null,
    val pairingSecretAck: PairingSecretAck? = null
)
