package polo.wire.protobuf

import com.squareup.wire.EnumAdapter
import com.squareup.wire.FieldEncoding
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import com.squareup.wire.Syntax
import com.squareup.wire.WireEnum
import com.squareup.wire.WireField
import okio.ByteString

enum class RoleType(override val value: Int) : WireEnum {
    ROLE_TYPE_UNKNOWN(0),
    ROLE_TYPE_INPUT(1),
    ROLE_TYPE_OUTPUT(2),
    ROLE_TYPE_CONTROLLER(3);

    companion object {
        val ADAPTER: ProtoAdapter<RoleType> = object : EnumAdapter<RoleType>(
            RoleType::class,
            Syntax.PROTO_2,
            ROLE_TYPE_UNKNOWN
        ) {
            override fun fromValue(value: Int): RoleType? = RoleType.fromValue(value)
        }

        fun fromValue(value: Int): RoleType? = entries.find { it.value == value }
    }
}

enum class Status(override val value: Int) : WireEnum {
    STATUS_UNKNOWN(0),
    STATUS_OK(200),
    STATUS_ERROR(400),
    STATUS_BAD_CONFIGURATION(401),
    STATUS_BAD_SECRET(402);

    companion object {
        val ADAPTER: ProtoAdapter<Status> = object : EnumAdapter<Status>(
            Status::class,
            Syntax.PROTO_2,
            STATUS_UNKNOWN
        ) {
            override fun fromValue(value: Int): Status? = Status.fromValue(value)
        }

        fun fromValue(value: Int): Status? = entries.find { it.value == value }
    }
}

enum class EncodingType(override val value: Int) : WireEnum {
    ENCODING_TYPE_UNKNOWN(0),
    ENCODING_TYPE_ALPHANUMERIC(1),
    ENCODING_TYPE_NUMERIC(2),
    ENCODING_TYPE_HEXADECIMAL(3),
    ENCODING_TYPE_QRCODE(4);

    companion object {
        val ADAPTER: ProtoAdapter<EncodingType> = object : EnumAdapter<EncodingType>(
            EncodingType::class,
            Syntax.PROTO_2,
            ENCODING_TYPE_UNKNOWN
        ) {
            override fun fromValue(value: Int): EncodingType? = EncodingType.fromValue(value)
        }

        fun fromValue(value: Int): EncodingType? = entries.find { it.value == value }
    }
}

enum class MessageType(override val value: Int) : WireEnum {
    MESSAGE_TYPE_UNKNOWN(0),
    MESSAGE_TYPE_PAIRING_REQUEST(10),
    MESSAGE_TYPE_PAIRING_REQUEST_ACK(11),
    MESSAGE_TYPE_OPTIONS(20),
    MESSAGE_TYPE_CONFIGURATION(30),
    MESSAGE_TYPE_CONFIGURATION_ACK(31),
    MESSAGE_TYPE_SECRET(40),
    MESSAGE_TYPE_SECRET_ACK(41);

    companion object {
        val ADAPTER: ProtoAdapter<MessageType> = object : EnumAdapter<MessageType>(
            MessageType::class,
            Syntax.PROTO_2,
            MESSAGE_TYPE_UNKNOWN
        ) {
            override fun fromValue(value: Int): MessageType? = MessageType.fromValue(value)
        }

        fun fromValue(value: Int): MessageType? = entries.find { it.value == value }
    }
}

class Encoding(
    @field:WireField(tag = 1, adapter = "polo.wire.protobuf.EncodingType#ADAPTER")
    val type: EncodingType? = null,
    @field:WireField(tag = 2, adapter = "com.squareup.wire.ProtoAdapter#UINT32")
    val symbol_length: Int? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<Encoding, Encoding.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.type = type
        builder.symbol_length = symbol_length
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<Encoding, Builder>() {
        var type: EncodingType? = null
        var symbol_length: Int? = null

        fun type(type: EncodingType?): Builder { this.type = type; return this }
        fun symbol_length(symbol_length: Int?): Builder { this.symbol_length = symbol_length; return this }

        override fun build(): Encoding = Encoding(type, symbol_length, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<Encoding> = object : ProtoAdapter<Encoding>(
            FieldEncoding.LENGTH_DELIMITED,
            Encoding::class,
            "type.googleapis.com/polo.wire.protobuf.Encoding",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: Encoding): Int {
                var size = value.unknownFields.size
                size += EncodingType.ADAPTER.encodedSizeWithTag(1, value.type)
                size += ProtoAdapter.UINT32.encodedSizeWithTag(2, value.symbol_length)
                return size
            }

            override fun encode(writer: ProtoWriter, value: Encoding) {
                EncodingType.ADAPTER.encodeWithTag(writer, 1, value.type)
                ProtoAdapter.UINT32.encodeWithTag(writer, 2, value.symbol_length)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): Encoding {
                var type: EncodingType? = null
                var symbol_length: Int? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> try {
                            type = EncodingType.ADAPTER.decode(reader)
                        } catch (e: ProtoAdapter.EnumConstantNotFoundException) {
                            reader.addUnknownField(tag, FieldEncoding.VARINT, e.value.toLong())
                        }
                        2 -> symbol_length = ProtoAdapter.UINT32.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return Encoding(type, symbol_length, unknownFields)
            }

            override fun redact(value: Encoding): Encoding = value
        }
    }
}

class PairingRequest(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val service_name: String? = null,
    @field:WireField(tag = 2, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val client_name: String? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<PairingRequest, PairingRequest.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.service_name = service_name
        builder.client_name = client_name
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<PairingRequest, Builder>() {
        var service_name: String? = null
        var client_name: String? = null

        fun service_name(service_name: String?): Builder { this.service_name = service_name; return this }
        fun client_name(client_name: String?): Builder { this.client_name = client_name; return this }

        override fun build(): PairingRequest = PairingRequest(service_name, client_name, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<PairingRequest> = object : ProtoAdapter<PairingRequest>(
            FieldEncoding.LENGTH_DELIMITED,
            PairingRequest::class,
            "type.googleapis.com/polo.wire.protobuf.PairingRequest",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: PairingRequest): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.STRING.encodedSizeWithTag(1, value.service_name)
                size += ProtoAdapter.STRING.encodedSizeWithTag(2, value.client_name)
                return size
            }

            override fun encode(writer: ProtoWriter, value: PairingRequest) {
                ProtoAdapter.STRING.encodeWithTag(writer, 1, value.service_name)
                ProtoAdapter.STRING.encodeWithTag(writer, 2, value.client_name)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): PairingRequest {
                var service_name: String? = null
                var client_name: String? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> service_name = ProtoAdapter.STRING.decode(reader)
                        2 -> client_name = ProtoAdapter.STRING.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return PairingRequest(service_name, client_name, unknownFields)
            }

            override fun redact(value: PairingRequest): PairingRequest = value
        }
    }
}

class PairingRequestAck(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val server_name: String? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<PairingRequestAck, PairingRequestAck.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.server_name = server_name
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<PairingRequestAck, Builder>() {
        var server_name: String? = null

        fun server_name(server_name: String?): Builder { this.server_name = server_name; return this }

        override fun build(): PairingRequestAck = PairingRequestAck(server_name, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<PairingRequestAck> = object : ProtoAdapter<PairingRequestAck>(
            FieldEncoding.LENGTH_DELIMITED,
            PairingRequestAck::class,
            "type.googleapis.com/polo.wire.protobuf.PairingRequestAck",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: PairingRequestAck): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.STRING.encodedSizeWithTag(1, value.server_name)
                return size
            }

            override fun encode(writer: ProtoWriter, value: PairingRequestAck) {
                ProtoAdapter.STRING.encodeWithTag(writer, 1, value.server_name)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): PairingRequestAck {
                var server_name: String? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> server_name = ProtoAdapter.STRING.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return PairingRequestAck(server_name, unknownFields)
            }

            override fun redact(value: PairingRequestAck): PairingRequestAck = value
        }
    }
}

class Options(
    @field:WireField(tag = 2, adapter = "polo.wire.protobuf.Encoding#ADAPTER", label = WireField.Label.REPEATED)
    val input_encodings: List<Encoding> = emptyList(),
    @field:WireField(tag = 3, adapter = "polo.wire.protobuf.Encoding#ADAPTER", label = WireField.Label.REPEATED)
    val output_encodings: List<Encoding> = emptyList(),
    @field:WireField(tag = 4, adapter = "polo.wire.protobuf.RoleType#ADAPTER")
    val preferred_role: RoleType? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<Options, Options.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.input_encodings = input_encodings
        builder.output_encodings = output_encodings
        builder.preferred_role = preferred_role
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<Options, Builder>() {
        var input_encodings: List<Encoding> = emptyList()
        var output_encodings: List<Encoding> = emptyList()
        var preferred_role: RoleType? = null

        fun input_encodings(input_encodings: List<Encoding>): Builder { this.input_encodings = input_encodings; return this }
        fun output_encodings(output_encodings: List<Encoding>): Builder { this.output_encodings = output_encodings; return this }
        fun preferred_role(preferred_role: RoleType?): Builder { this.preferred_role = preferred_role; return this }

        override fun build(): Options = Options(input_encodings, output_encodings, preferred_role, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<Options> = object : ProtoAdapter<Options>(
            FieldEncoding.LENGTH_DELIMITED,
            Options::class,
            "type.googleapis.com/polo.wire.protobuf.Options",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: Options): Int {
                var size = value.unknownFields.size
                size += Encoding.ADAPTER.asRepeated().encodedSizeWithTag(2, value.input_encodings)
                size += Encoding.ADAPTER.asRepeated().encodedSizeWithTag(3, value.output_encodings)
                size += RoleType.ADAPTER.encodedSizeWithTag(4, value.preferred_role)
                return size
            }

            override fun encode(writer: ProtoWriter, value: Options) {
                Encoding.ADAPTER.asRepeated().encodeWithTag(writer, 2, value.input_encodings)
                Encoding.ADAPTER.asRepeated().encodeWithTag(writer, 3, value.output_encodings)
                RoleType.ADAPTER.encodeWithTag(writer, 4, value.preferred_role)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): Options {
                val input_encodings = mutableListOf<Encoding>()
                val output_encodings = mutableListOf<Encoding>()
                var preferred_role: RoleType? = null

                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> {
                            // Some TVs send preferred_role as tag 1
                            if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                                val roleVal = ProtoAdapter.INT32.decode(reader)
                                preferred_role = RoleType.fromValue(roleVal) ?: RoleType.ROLE_TYPE_INPUT
                            } else {
                                reader.readUnknownField(tag)
                            }
                        }
                        2 -> {
                            // Input encodings: can be VARINT (enum EncodingType) or LENGTH_DELIMITED (Encoding submessage)
                            if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                                val encVal = ProtoAdapter.INT32.decode(reader)
                                val encType = EncodingType.fromValue(encVal) ?: EncodingType.ENCODING_TYPE_HEXADECIMAL
                                input_encodings.add(Encoding(type = encType, symbol_length = 6))
                            } else if (reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED) {
                                input_encodings.add(Encoding.ADAPTER.decode(reader))
                            } else {
                                reader.readUnknownField(tag)
                            }
                        }
                        3 -> {
                            // Output encodings: can be VARINT (enum EncodingType) or LENGTH_DELIMITED (Encoding submessage)
                            if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                                val encVal = ProtoAdapter.INT32.decode(reader)
                                val encType = EncodingType.fromValue(encVal) ?: EncodingType.ENCODING_TYPE_HEXADECIMAL
                                output_encodings.add(Encoding(type = encType, symbol_length = 6))
                            } else if (reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED) {
                                output_encodings.add(Encoding.ADAPTER.decode(reader))
                            } else {
                                reader.readUnknownField(tag)
                            }
                        }
                        4 -> {
                            if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                                val roleVal = ProtoAdapter.INT32.decode(reader)
                                preferred_role = RoleType.fromValue(roleVal) ?: RoleType.ROLE_TYPE_INPUT
                            } else {
                                reader.readUnknownField(tag)
                            }
                        }
                        else -> reader.readUnknownField(tag)
                    }
                }
                return Options(input_encodings, output_encodings, preferred_role, unknownFields)
            }

            override fun redact(value: Options): Options = value
        }
    }
}

class Configuration(
    @field:WireField(tag = 1, adapter = "polo.wire.protobuf.Encoding#ADAPTER")
    val encoding: Encoding? = null,
    @field:WireField(tag = 2, adapter = "polo.wire.protobuf.RoleType#ADAPTER")
    val client_role: RoleType? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<Configuration, Configuration.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.encoding = encoding
        builder.client_role = client_role
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<Configuration, Builder>() {
        var encoding: Encoding? = null
        var client_role: RoleType? = null

        fun encoding(encoding: Encoding?): Builder { this.encoding = encoding; return this }
        fun client_role(client_role: RoleType?): Builder { this.client_role = client_role; return this }

        override fun build(): Configuration = Configuration(encoding, client_role, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<Configuration> = object : ProtoAdapter<Configuration>(
            FieldEncoding.LENGTH_DELIMITED,
            Configuration::class,
            "type.googleapis.com/polo.wire.protobuf.Configuration",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: Configuration): Int {
                var size = value.unknownFields.size
                size += Encoding.ADAPTER.encodedSizeWithTag(1, value.encoding)
                size += RoleType.ADAPTER.encodedSizeWithTag(2, value.client_role)
                return size
            }

            override fun encode(writer: ProtoWriter, value: Configuration) {
                Encoding.ADAPTER.encodeWithTag(writer, 1, value.encoding)
                RoleType.ADAPTER.encodeWithTag(writer, 2, value.client_role)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): Configuration {
                var encoding: Encoding? = null
                var client_role: RoleType? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> {
                            if (reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED) {
                                encoding = Encoding.ADAPTER.decode(reader)
                            } else if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                                val encVal = ProtoAdapter.INT32.decode(reader)
                                encoding = Encoding(type = EncodingType.fromValue(encVal), symbol_length = 6)
                            } else {
                                reader.readUnknownField(tag)
                            }
                        }
                        2 -> {
                            if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
                                val roleVal = ProtoAdapter.INT32.decode(reader)
                                client_role = RoleType.fromValue(roleVal)
                            } else {
                                reader.readUnknownField(tag)
                            }
                        }
                        else -> reader.readUnknownField(tag)
                    }
                }
                return Configuration(encoding, client_role, unknownFields)
            }

            override fun redact(value: Configuration): Configuration = value
        }
    }
}

class ConfigurationAck(
    unknownFields: ByteString = ByteString.EMPTY
) : Message<ConfigurationAck, ConfigurationAck.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<ConfigurationAck, Builder>() {
        override fun build(): ConfigurationAck = ConfigurationAck(buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<ConfigurationAck> = object : ProtoAdapter<ConfigurationAck>(
            FieldEncoding.LENGTH_DELIMITED,
            ConfigurationAck::class,
            "type.googleapis.com/polo.wire.protobuf.ConfigurationAck",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: ConfigurationAck): Int = value.unknownFields.size

            override fun encode(writer: ProtoWriter, value: ConfigurationAck) {
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): ConfigurationAck {
                val unknownFields = reader.forEachTag { tag ->
                    reader.readUnknownField(tag)
                }
                return ConfigurationAck(unknownFields)
            }

            override fun redact(value: ConfigurationAck): ConfigurationAck = value
        }
    }
}

class Secret(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#BYTES")
    val secret: ByteString? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<Secret, Secret.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.secret = secret
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<Secret, Builder>() {
        var secret: ByteString? = null

        fun secret(secret: ByteString?): Builder { this.secret = secret; return this }

        override fun build(): Secret = Secret(secret, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<Secret> = object : ProtoAdapter<Secret>(
            FieldEncoding.LENGTH_DELIMITED,
            Secret::class,
            "type.googleapis.com/polo.wire.protobuf.Secret",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: Secret): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.BYTES.encodedSizeWithTag(1, value.secret)
                return size
            }

            override fun encode(writer: ProtoWriter, value: Secret) {
                ProtoAdapter.BYTES.encodeWithTag(writer, 1, value.secret)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): Secret {
                var secret: ByteString? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> secret = ProtoAdapter.BYTES.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return Secret(secret, unknownFields)
            }

            override fun redact(value: Secret): Secret = value
        }
    }
}

class SecretAck(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#BYTES")
    val secret: ByteString? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<SecretAck, SecretAck.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.secret = secret
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<SecretAck, Builder>() {
        var secret: ByteString? = null

        fun secret(secret: ByteString?): Builder { this.secret = secret; return this }

        override fun build(): SecretAck = SecretAck(secret, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<SecretAck> = object : ProtoAdapter<SecretAck>(
            FieldEncoding.LENGTH_DELIMITED,
            SecretAck::class,
            "type.googleapis.com/polo.wire.protobuf.SecretAck",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: SecretAck): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.BYTES.encodedSizeWithTag(1, value.secret)
                return size
            }

            override fun encode(writer: ProtoWriter, value: SecretAck) {
                ProtoAdapter.BYTES.encodeWithTag(writer, 1, value.secret)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): SecretAck {
                var secret: ByteString? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> secret = ProtoAdapter.BYTES.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return SecretAck(secret, unknownFields)
            }

            override fun redact(value: SecretAck): SecretAck = value
        }
    }
}

class OuterMessage(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#UINT32")
    val protocol_version: Int? = null,
    @field:WireField(tag = 2, adapter = "polo.wire.protobuf.Status#ADAPTER")
    val status: Status? = null,
    @field:WireField(tag = 3, adapter = "polo.wire.protobuf.MessageType#ADAPTER")
    val type: MessageType? = null,
    @field:WireField(tag = 10, adapter = "polo.wire.protobuf.PairingRequest#ADAPTER")
    val pairing_request: PairingRequest? = null,
    @field:WireField(tag = 11, adapter = "polo.wire.protobuf.PairingRequestAck#ADAPTER")
    val pairing_request_ack: PairingRequestAck? = null,
    @field:WireField(tag = 20, adapter = "polo.wire.protobuf.Options#ADAPTER")
    val options: Options? = null,
    @field:WireField(tag = 30, adapter = "polo.wire.protobuf.Configuration#ADAPTER")
    val configuration: Configuration? = null,
    @field:WireField(tag = 31, adapter = "polo.wire.protobuf.ConfigurationAck#ADAPTER")
    val configuration_ack: ConfigurationAck? = null,
    @field:WireField(tag = 40, adapter = "polo.wire.protobuf.Secret#ADAPTER")
    val secret: Secret? = null,
    @field:WireField(tag = 41, adapter = "polo.wire.protobuf.SecretAck#ADAPTER")
    val secret_ack: SecretAck? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<OuterMessage, OuterMessage.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.protocol_version = protocol_version
        builder.status = status
        builder.type = type
        builder.pairing_request = pairing_request
        builder.pairing_request_ack = pairing_request_ack
        builder.options = options
        builder.configuration = configuration
        builder.configuration_ack = configuration_ack
        builder.secret = secret
        builder.secret_ack = secret_ack
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<OuterMessage, Builder>() {
        var protocol_version: Int? = null
        var status: Status? = null
        var type: MessageType? = null
        var pairing_request: PairingRequest? = null
        var pairing_request_ack: PairingRequestAck? = null
        var options: Options? = null
        var configuration: Configuration? = null
        var configuration_ack: ConfigurationAck? = null
        var secret: Secret? = null
        var secret_ack: SecretAck? = null

        fun protocol_version(protocol_version: Int?): Builder { this.protocol_version = protocol_version; return this }
        fun status(status: Status?): Builder { this.status = status; return this }
        fun type(type: MessageType?): Builder { this.type = type; return this }
        fun pairing_request(pairing_request: PairingRequest?): Builder { this.pairing_request = pairing_request; return this }
        fun pairing_request_ack(pairing_request_ack: PairingRequestAck?): Builder { this.pairing_request_ack = pairing_request_ack; return this }
        fun options(options: Options?): Builder { this.options = options; return this }
        fun configuration(configuration: Configuration?): Builder { this.configuration = configuration; return this }
        fun configuration_ack(configuration_ack: ConfigurationAck?): Builder { this.configuration_ack = configuration_ack; return this }
        fun secret(secret: Secret?): Builder { this.secret = secret; return this }
        fun secret_ack(secret_ack: SecretAck?): Builder { this.secret_ack = secret_ack; return this }

        override fun build(): OuterMessage = OuterMessage(
            protocol_version, status, type,
            pairing_request, pairing_request_ack, options, configuration, configuration_ack,
            secret, secret_ack, buildUnknownFields()
        )
    }

    companion object {
        val ADAPTER: ProtoAdapter<OuterMessage> = object : ProtoAdapter<OuterMessage>(
            FieldEncoding.LENGTH_DELIMITED,
            OuterMessage::class,
            "type.googleapis.com/polo.wire.protobuf.OuterMessage",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: OuterMessage): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.UINT32.encodedSizeWithTag(1, value.protocol_version)
                size += Status.ADAPTER.encodedSizeWithTag(2, value.status)
                size += MessageType.ADAPTER.encodedSizeWithTag(3, value.type)
                size += PairingRequest.ADAPTER.encodedSizeWithTag(10, value.pairing_request)
                size += PairingRequestAck.ADAPTER.encodedSizeWithTag(11, value.pairing_request_ack)
                size += Options.ADAPTER.encodedSizeWithTag(20, value.options)
                size += Configuration.ADAPTER.encodedSizeWithTag(30, value.configuration)
                size += ConfigurationAck.ADAPTER.encodedSizeWithTag(31, value.configuration_ack)
                size += Secret.ADAPTER.encodedSizeWithTag(40, value.secret)
                size += SecretAck.ADAPTER.encodedSizeWithTag(41, value.secret_ack)
                return size
            }

            override fun encode(writer: ProtoWriter, value: OuterMessage) {
                ProtoAdapter.UINT32.encodeWithTag(writer, 1, value.protocol_version)
                Status.ADAPTER.encodeWithTag(writer, 2, value.status)
                MessageType.ADAPTER.encodeWithTag(writer, 3, value.type)
                PairingRequest.ADAPTER.encodeWithTag(writer, 10, value.pairing_request)
                PairingRequestAck.ADAPTER.encodeWithTag(writer, 11, value.pairing_request_ack)
                Options.ADAPTER.encodeWithTag(writer, 20, value.options)
                Configuration.ADAPTER.encodeWithTag(writer, 30, value.configuration)
                ConfigurationAck.ADAPTER.encodeWithTag(writer, 31, value.configuration_ack)
                Secret.ADAPTER.encodeWithTag(writer, 40, value.secret)
                SecretAck.ADAPTER.encodeWithTag(writer, 41, value.secret_ack)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): OuterMessage {
                var protocol_version: Int? = null
                var status: Status? = null
                var type: MessageType? = null
                var pairing_request: PairingRequest? = null
                var pairing_request_ack: PairingRequestAck? = null
                var options: Options? = null
                var configuration: Configuration? = null
                var configuration_ack: ConfigurationAck? = null
                var secret: Secret? = null
                var secret_ack: SecretAck? = null

                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> protocol_version = ProtoAdapter.UINT32.decode(reader)
                        2 -> try {
                            status = Status.ADAPTER.decode(reader)
                        } catch (e: ProtoAdapter.EnumConstantNotFoundException) {
                            reader.addUnknownField(tag, FieldEncoding.VARINT, e.value.toLong())
                        }
                        3 -> try {
                            type = MessageType.ADAPTER.decode(reader)
                        } catch (e: ProtoAdapter.EnumConstantNotFoundException) {
                            reader.addUnknownField(tag, FieldEncoding.VARINT, e.value.toLong())
                        }
                        10 -> pairing_request = PairingRequest.ADAPTER.decode(reader)
                        11 -> pairing_request_ack = PairingRequestAck.ADAPTER.decode(reader)
                        20 -> options = Options.ADAPTER.decode(reader)
                        30 -> configuration = Configuration.ADAPTER.decode(reader)
                        31 -> configuration_ack = ConfigurationAck.ADAPTER.decode(reader)
                        40 -> secret = Secret.ADAPTER.decode(reader)
                        41 -> secret_ack = SecretAck.ADAPTER.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return OuterMessage(
                    protocol_version = protocol_version,
                    status = status,
                    type = type,
                    pairing_request = pairing_request,
                    pairing_request_ack = pairing_request_ack,
                    options = options,
                    configuration = configuration,
                    configuration_ack = configuration_ack,
                    secret = secret,
                    secret_ack = secret_ack,
                    unknownFields = unknownFields
                )
            }

            override fun redact(value: OuterMessage): OuterMessage = value
        }
    }
}
