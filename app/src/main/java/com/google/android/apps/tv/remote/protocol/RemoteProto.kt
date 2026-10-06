package com.google.android.apps.tv.remote.protocol

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

enum class Direction(override val value: Int) : WireEnum {
    UNKNOWN_DIRECTION(0),
    START_LONG(1),
    END_LONG(2),
    SHORT(3);

    companion object {
        val ADAPTER: ProtoAdapter<Direction> = object : EnumAdapter<Direction>(
            Direction::class,
            Syntax.PROTO_2,
            UNKNOWN_DIRECTION
        ) {
            override fun fromValue(value: Int): Direction? = Direction.fromValue(value)
        }

        fun fromValue(value: Int): Direction? = entries.find { it.value == value }
    }
}

class DeviceInfo(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val model: String? = null,
    @field:WireField(tag = 2, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val vendor: String? = null,
    @field:WireField(tag = 3, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val unknown1: Int? = null,
    @field:WireField(tag = 4, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val unknown2: String? = null,
    @field:WireField(tag = 5, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val package_name: String? = null,
    @field:WireField(tag = 6, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val app_version: String? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<DeviceInfo, DeviceInfo.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.model = model
        builder.vendor = vendor
        builder.unknown1 = unknown1
        builder.unknown2 = unknown2
        builder.package_name = package_name
        builder.app_version = app_version
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<DeviceInfo, Builder>() {
        var model: String? = null
        var vendor: String? = null
        var unknown1: Int? = null
        var unknown2: String? = null
        var package_name: String? = null
        var app_version: String? = null

        fun model(model: String?): Builder { this.model = model; return this }
        fun vendor(vendor: String?): Builder { this.vendor = vendor; return this }
        fun unknown1(unknown1: Int?): Builder { this.unknown1 = unknown1; return this }
        fun unknown2(unknown2: String?): Builder { this.unknown2 = unknown2; return this }
        fun package_name(package_name: String?): Builder { this.package_name = package_name; return this }
        fun app_version(app_version: String?): Builder { this.app_version = app_version; return this }

        override fun build(): DeviceInfo = DeviceInfo(
            model, vendor, unknown1, unknown2, package_name, app_version, buildUnknownFields()
        )
    }

    companion object {
        val ADAPTER: ProtoAdapter<DeviceInfo> = object : ProtoAdapter<DeviceInfo>(
            FieldEncoding.LENGTH_DELIMITED,
            DeviceInfo::class,
            "type.googleapis.com/remotemessage.DeviceInfo",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: DeviceInfo): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.STRING.encodedSizeWithTag(1, value.model)
                size += ProtoAdapter.STRING.encodedSizeWithTag(2, value.vendor)
                size += ProtoAdapter.INT32.encodedSizeWithTag(3, value.unknown1)
                size += ProtoAdapter.STRING.encodedSizeWithTag(4, value.unknown2)
                size += ProtoAdapter.STRING.encodedSizeWithTag(5, value.package_name)
                size += ProtoAdapter.STRING.encodedSizeWithTag(6, value.app_version)
                return size
            }

            override fun encode(writer: ProtoWriter, value: DeviceInfo) {
                ProtoAdapter.STRING.encodeWithTag(writer, 1, value.model)
                ProtoAdapter.STRING.encodeWithTag(writer, 2, value.vendor)
                ProtoAdapter.INT32.encodeWithTag(writer, 3, value.unknown1)
                ProtoAdapter.STRING.encodeWithTag(writer, 4, value.unknown2)
                ProtoAdapter.STRING.encodeWithTag(writer, 5, value.package_name)
                ProtoAdapter.STRING.encodeWithTag(writer, 6, value.app_version)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): DeviceInfo {
                var model: String? = null
                var vendor: String? = null
                var unknown1: Int? = null
                var unknown2: String? = null
                var package_name: String? = null
                var app_version: String? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> model = ProtoAdapter.STRING.decode(reader)
                        2 -> vendor = ProtoAdapter.STRING.decode(reader)
                        3 -> unknown1 = ProtoAdapter.INT32.decode(reader)
                        4 -> unknown2 = ProtoAdapter.STRING.decode(reader)
                        5 -> package_name = ProtoAdapter.STRING.decode(reader)
                        6 -> app_version = ProtoAdapter.STRING.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return DeviceInfo(model, vendor, unknown1, unknown2, package_name, app_version, unknownFields)
            }

            override fun redact(value: DeviceInfo): DeviceInfo = value
        }
    }
}

class RemoteConfigure(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val code1: Int? = null,
    @field:WireField(tag = 2, adapter = "com.google.android.apps.tv.remote.protocol.DeviceInfo#ADAPTER")
    val device_info: DeviceInfo? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteConfigure, RemoteConfigure.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.code1 = code1
        builder.device_info = device_info
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteConfigure, Builder>() {
        var code1: Int? = null
        var device_info: DeviceInfo? = null

        fun code1(code1: Int?): Builder { this.code1 = code1; return this }
        fun device_info(device_info: DeviceInfo?): Builder { this.device_info = device_info; return this }

        override fun build(): RemoteConfigure = RemoteConfigure(code1, device_info, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteConfigure> = object : ProtoAdapter<RemoteConfigure>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteConfigure::class,
            "type.googleapis.com/remotemessage.RemoteConfigure",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteConfigure): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.INT32.encodedSizeWithTag(1, value.code1)
                size += DeviceInfo.ADAPTER.encodedSizeWithTag(2, value.device_info)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteConfigure) {
                ProtoAdapter.INT32.encodeWithTag(writer, 1, value.code1)
                DeviceInfo.ADAPTER.encodeWithTag(writer, 2, value.device_info)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteConfigure {
                var code1: Int? = null
                var device_info: DeviceInfo? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> code1 = ProtoAdapter.INT32.decode(reader)
                        2 -> device_info = DeviceInfo.ADAPTER.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteConfigure(code1, device_info, unknownFields)
            }

            override fun redact(value: RemoteConfigure): RemoteConfigure = value
        }
    }
}

class RemoteSetActive(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val active: Int? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteSetActive, RemoteSetActive.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.active = active
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteSetActive, Builder>() {
        var active: Int? = null

        fun active(active: Int?): Builder { this.active = active; return this }
        override fun build(): RemoteSetActive = RemoteSetActive(active, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteSetActive> = object : ProtoAdapter<RemoteSetActive>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteSetActive::class,
            "type.googleapis.com/remotemessage.RemoteSetActive",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteSetActive): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.INT32.encodedSizeWithTag(1, value.active)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteSetActive) {
                ProtoAdapter.INT32.encodeWithTag(writer, 1, value.active)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteSetActive {
                var active: Int? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> active = ProtoAdapter.INT32.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteSetActive(active, unknownFields)
            }

            override fun redact(value: RemoteSetActive): RemoteSetActive = value
        }
    }
}

class RemotePingRequest(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val val1: Int? = null,
    @field:WireField(tag = 2, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val val2: Int? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemotePingRequest, RemotePingRequest.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.val1 = val1
        builder.val2 = val2
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemotePingRequest, Builder>() {
        var val1: Int? = null
        var val2: Int? = null

        fun val1(val1: Int?): Builder { this.val1 = val1; return this }
        fun val2(val2: Int?): Builder { this.val2 = val2; return this }
        override fun build(): RemotePingRequest = RemotePingRequest(val1, val2, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemotePingRequest> = object : ProtoAdapter<RemotePingRequest>(
            FieldEncoding.LENGTH_DELIMITED,
            RemotePingRequest::class,
            "type.googleapis.com/remotemessage.RemotePingRequest",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemotePingRequest): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.INT32.encodedSizeWithTag(1, value.val1)
                size += ProtoAdapter.INT32.encodedSizeWithTag(2, value.val2)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemotePingRequest) {
                ProtoAdapter.INT32.encodeWithTag(writer, 1, value.val1)
                ProtoAdapter.INT32.encodeWithTag(writer, 2, value.val2)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemotePingRequest {
                var val1: Int? = null
                var val2: Int? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> val1 = ProtoAdapter.INT32.decode(reader)
                        2 -> val2 = ProtoAdapter.INT32.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemotePingRequest(val1, val2, unknownFields)
            }

            override fun redact(value: RemotePingRequest): RemotePingRequest = value
        }
    }
}

class RemotePingResponse(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val val1: Int? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemotePingResponse, RemotePingResponse.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.val1 = val1
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemotePingResponse, Builder>() {
        var val1: Int? = null

        fun val1(val1: Int?): Builder { this.val1 = val1; return this }
        override fun build(): RemotePingResponse = RemotePingResponse(val1, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemotePingResponse> = object : ProtoAdapter<RemotePingResponse>(
            FieldEncoding.LENGTH_DELIMITED,
            RemotePingResponse::class,
            "type.googleapis.com/remotemessage.RemotePingResponse",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemotePingResponse): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.INT32.encodedSizeWithTag(1, value.val1)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemotePingResponse) {
                ProtoAdapter.INT32.encodeWithTag(writer, 1, value.val1)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemotePingResponse {
                var val1: Int? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> val1 = ProtoAdapter.INT32.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemotePingResponse(val1, unknownFields)
            }

            override fun redact(value: RemotePingResponse): RemotePingResponse = value
        }
    }
}

class RemoteKeyInject(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val key_code: Int? = null,
    @field:WireField(tag = 2, adapter = "com.google.android.apps.tv.remote.protocol.Direction#ADAPTER")
    val direction: Direction? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteKeyInject, RemoteKeyInject.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.key_code = key_code
        builder.direction = direction
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteKeyInject, Builder>() {
        var key_code: Int? = null
        var direction: Direction? = null

        fun key_code(key_code: Int?): Builder { this.key_code = key_code; return this }
        fun direction(direction: Direction?): Builder { this.direction = direction; return this }

        override fun build(): RemoteKeyInject = RemoteKeyInject(key_code, direction, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteKeyInject> = object : ProtoAdapter<RemoteKeyInject>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteKeyInject::class,
            "type.googleapis.com/remotemessage.RemoteKeyInject",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteKeyInject): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.INT32.encodedSizeWithTag(1, value.key_code)
                size += Direction.ADAPTER.encodedSizeWithTag(2, value.direction)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteKeyInject) {
                ProtoAdapter.INT32.encodeWithTag(writer, 1, value.key_code)
                Direction.ADAPTER.encodeWithTag(writer, 2, value.direction)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteKeyInject {
                var key_code: Int? = null
                var direction: Direction? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> key_code = ProtoAdapter.INT32.decode(reader)
                        2 -> try {
                            direction = Direction.ADAPTER.decode(reader)
                        } catch (e: ProtoAdapter.EnumConstantNotFoundException) {
                            reader.addUnknownField(tag, FieldEncoding.VARINT, e.value.toLong())
                        }
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteKeyInject(key_code, direction, unknownFields)
            }

            override fun redact(value: RemoteKeyInject): RemoteKeyInject = value
        }
    }
}

class RemoteImeKeyInject(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#INT32")
    val app_info: Int? = null,
    @field:WireField(tag = 2, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val text: String? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteImeKeyInject, RemoteImeKeyInject.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.app_info = app_info
        builder.text = text
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteImeKeyInject, Builder>() {
        var app_info: Int? = null
        var text: String? = null

        fun app_info(app_info: Int?): Builder { this.app_info = app_info; return this }
        fun text(text: String?): Builder { this.text = text; return this }

        override fun build(): RemoteImeKeyInject = RemoteImeKeyInject(app_info, text, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteImeKeyInject> = object : ProtoAdapter<RemoteImeKeyInject>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteImeKeyInject::class,
            "type.googleapis.com/remotemessage.RemoteImeKeyInject",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteImeKeyInject): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.INT32.encodedSizeWithTag(1, value.app_info)
                size += ProtoAdapter.STRING.encodedSizeWithTag(2, value.text)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteImeKeyInject) {
                ProtoAdapter.INT32.encodeWithTag(writer, 1, value.app_info)
                ProtoAdapter.STRING.encodeWithTag(writer, 2, value.text)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteImeKeyInject {
                var app_info: Int? = null
                var text: String? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> app_info = ProtoAdapter.INT32.decode(reader)
                        2 -> text = ProtoAdapter.STRING.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteImeKeyInject(app_info, text, unknownFields)
            }

            override fun redact(value: RemoteImeKeyInject): RemoteImeKeyInject = value
        }
    }
}

class RemoteStart(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#BOOL")
    val started: Boolean? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteStart, RemoteStart.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.started = started
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteStart, Builder>() {
        var started: Boolean? = null

        fun started(started: Boolean?): Builder { this.started = started; return this }
        override fun build(): RemoteStart = RemoteStart(started, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteStart> = object : ProtoAdapter<RemoteStart>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteStart::class,
            "type.googleapis.com/remotemessage.RemoteStart",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteStart): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.BOOL.encodedSizeWithTag(1, value.started)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteStart) {
                ProtoAdapter.BOOL.encodeWithTag(writer, 1, value.started)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteStart {
                var started: Boolean? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> started = ProtoAdapter.BOOL.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteStart(started, unknownFields)
            }

            override fun redact(value: RemoteStart): RemoteStart = value
        }
    }
}

class RemoteError(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#BOOL")
    val value: Boolean? = null,
    @field:WireField(tag = 2, adapter = "com.google.android.apps.tv.remote.protocol.RemoteMessage#ADAPTER")
    val message: RemoteMessage? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteError, RemoteError.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.value = value
        builder.message = message
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteError, Builder>() {
        var value: Boolean? = null
        var message: RemoteMessage? = null

        fun value(value: Boolean?): Builder { this.value = value; return this }
        fun message(message: RemoteMessage?): Builder { this.message = message; return this }
        override fun build(): RemoteError = RemoteError(value, message, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteError> = object : ProtoAdapter<RemoteError>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteError::class,
            "type.googleapis.com/remotemessage.RemoteError",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteError): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.BOOL.encodedSizeWithTag(1, value.value)
                size += RemoteMessage.ADAPTER.encodedSizeWithTag(2, value.message)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteError) {
                ProtoAdapter.BOOL.encodeWithTag(writer, 1, value.value)
                RemoteMessage.ADAPTER.encodeWithTag(writer, 2, value.message)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteError {
                var value: Boolean? = null
                var message: RemoteMessage? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> value = ProtoAdapter.BOOL.decode(reader)
                        2 -> message = RemoteMessage.ADAPTER.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteError(value, message, unknownFields)
            }

            override fun redact(value: RemoteError): RemoteError = value
        }
    }
}

class RemoteAppLinkLaunchRequest(
    @field:WireField(tag = 1, adapter = "com.squareup.wire.ProtoAdapter#STRING")
    val app_link: String? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteAppLinkLaunchRequest, RemoteAppLinkLaunchRequest.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.app_link = app_link
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteAppLinkLaunchRequest, Builder>() {
        var app_link: String? = null

        fun app_link(app_link: String?): Builder { this.app_link = app_link; return this }
        override fun build(): RemoteAppLinkLaunchRequest = RemoteAppLinkLaunchRequest(app_link, buildUnknownFields())
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteAppLinkLaunchRequest> = object : ProtoAdapter<RemoteAppLinkLaunchRequest>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteAppLinkLaunchRequest::class,
            "type.googleapis.com/remotemessage.RemoteAppLinkLaunchRequest",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteAppLinkLaunchRequest): Int {
                var size = value.unknownFields.size
                size += ProtoAdapter.STRING.encodedSizeWithTag(1, value.app_link)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteAppLinkLaunchRequest) {
                ProtoAdapter.STRING.encodeWithTag(writer, 1, value.app_link)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteAppLinkLaunchRequest {
                var app_link: String? = null
                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> app_link = ProtoAdapter.STRING.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteAppLinkLaunchRequest(app_link, unknownFields)
            }

            override fun redact(value: RemoteAppLinkLaunchRequest): RemoteAppLinkLaunchRequest = value
        }
    }
}

class RemoteMessage(
    @field:WireField(tag = 1, adapter = "com.google.android.apps.tv.remote.protocol.RemoteConfigure#ADAPTER")
    val remote_configure: RemoteConfigure? = null,
    @field:WireField(tag = 2, adapter = "com.google.android.apps.tv.remote.protocol.RemoteSetActive#ADAPTER")
    val remote_set_active: RemoteSetActive? = null,
    @field:WireField(tag = 3, adapter = "com.google.android.apps.tv.remote.protocol.RemoteError#ADAPTER")
    val remote_error: RemoteError? = null,
    @field:WireField(tag = 8, adapter = "com.google.android.apps.tv.remote.protocol.RemotePingRequest#ADAPTER")
    val remote_ping_request: RemotePingRequest? = null,
    @field:WireField(tag = 9, adapter = "com.google.android.apps.tv.remote.protocol.RemotePingResponse#ADAPTER")
    val remote_ping_response: RemotePingResponse? = null,
    @field:WireField(tag = 10, adapter = "com.google.android.apps.tv.remote.protocol.RemoteKeyInject#ADAPTER")
    val remote_key_inject: RemoteKeyInject? = null,
    @field:WireField(tag = 20, adapter = "com.google.android.apps.tv.remote.protocol.RemoteImeKeyInject#ADAPTER")
    val remote_ime_key_inject: RemoteImeKeyInject? = null,
    @field:WireField(tag = 40, adapter = "com.google.android.apps.tv.remote.protocol.RemoteStart#ADAPTER")
    val remote_start: RemoteStart? = null,
    @field:WireField(tag = 90, adapter = "com.google.android.apps.tv.remote.protocol.RemoteAppLinkLaunchRequest#ADAPTER")
    val remote_app_link_launch_request: RemoteAppLinkLaunchRequest? = null,
    unknownFields: ByteString = ByteString.EMPTY
) : Message<RemoteMessage, RemoteMessage.Builder>(ADAPTER, unknownFields) {

    override fun newBuilder(): Builder {
        val builder = Builder()
        builder.remote_configure = remote_configure
        builder.remote_set_active = remote_set_active
        builder.remote_error = remote_error
        builder.remote_ping_request = remote_ping_request
        builder.remote_ping_response = remote_ping_response
        builder.remote_key_inject = remote_key_inject
        builder.remote_ime_key_inject = remote_ime_key_inject
        builder.remote_start = remote_start
        builder.remote_app_link_launch_request = remote_app_link_launch_request
        builder.addUnknownFields(unknownFields)
        return builder
    }

    class Builder : Message.Builder<RemoteMessage, Builder>() {
        var remote_configure: RemoteConfigure? = null
        var remote_set_active: RemoteSetActive? = null
        var remote_error: RemoteError? = null
        var remote_ping_request: RemotePingRequest? = null
        var remote_ping_response: RemotePingResponse? = null
        var remote_key_inject: RemoteKeyInject? = null
        var remote_ime_key_inject: RemoteImeKeyInject? = null
        var remote_start: RemoteStart? = null
        var remote_app_link_launch_request: RemoteAppLinkLaunchRequest? = null

        fun remote_configure(remote_configure: RemoteConfigure?): Builder {
            this.remote_configure = remote_configure
            return this
        }

        fun remote_set_active(remote_set_active: RemoteSetActive?): Builder {
            this.remote_set_active = remote_set_active
            return this
        }

        fun remote_error(remote_error: RemoteError?): Builder {
            this.remote_error = remote_error
            return this
        }

        fun remote_ping_request(remote_ping_request: RemotePingRequest?): Builder {
            this.remote_ping_request = remote_ping_request
            return this
        }

        fun remote_ping_response(remote_ping_response: RemotePingResponse?): Builder {
            this.remote_ping_response = remote_ping_response
            return this
        }

        fun remote_key_inject(remote_key_inject: RemoteKeyInject?): Builder {
            this.remote_key_inject = remote_key_inject
            return this
        }

        fun remote_ime_key_inject(remote_ime_key_inject: RemoteImeKeyInject?): Builder {
            this.remote_ime_key_inject = remote_ime_key_inject
            return this
        }

        fun remote_start(remote_start: RemoteStart?): Builder {
            this.remote_start = remote_start
            return this
        }

        fun remote_app_link_launch_request(remote_app_link_launch_request: RemoteAppLinkLaunchRequest?): Builder {
            this.remote_app_link_launch_request = remote_app_link_launch_request
            return this
        }

        override fun build(): RemoteMessage = RemoteMessage(
            remote_configure, remote_set_active, remote_error,
            remote_ping_request, remote_ping_response, remote_key_inject,
            remote_ime_key_inject, remote_start, remote_app_link_launch_request, buildUnknownFields()
        )
    }

    companion object {
        val ADAPTER: ProtoAdapter<RemoteMessage> = object : ProtoAdapter<RemoteMessage>(
            FieldEncoding.LENGTH_DELIMITED,
            RemoteMessage::class,
            "type.googleapis.com/remotemessage.RemoteMessage",
            Syntax.PROTO_2,
            null
        ) {
            override fun encodedSize(value: RemoteMessage): Int {
                var size = value.unknownFields.size
                size += RemoteConfigure.ADAPTER.encodedSizeWithTag(1, value.remote_configure)
                size += RemoteSetActive.ADAPTER.encodedSizeWithTag(2, value.remote_set_active)
                size += RemoteError.ADAPTER.encodedSizeWithTag(3, value.remote_error)
                size += RemotePingRequest.ADAPTER.encodedSizeWithTag(8, value.remote_ping_request)
                size += RemotePingResponse.ADAPTER.encodedSizeWithTag(9, value.remote_ping_response)
                size += RemoteKeyInject.ADAPTER.encodedSizeWithTag(10, value.remote_key_inject)
                size += RemoteImeKeyInject.ADAPTER.encodedSizeWithTag(20, value.remote_ime_key_inject)
                size += RemoteStart.ADAPTER.encodedSizeWithTag(40, value.remote_start)
                size += RemoteAppLinkLaunchRequest.ADAPTER.encodedSizeWithTag(90, value.remote_app_link_launch_request)
                return size
            }

            override fun encode(writer: ProtoWriter, value: RemoteMessage) {
                RemoteConfigure.ADAPTER.encodeWithTag(writer, 1, value.remote_configure)
                RemoteSetActive.ADAPTER.encodeWithTag(writer, 2, value.remote_set_active)
                RemoteError.ADAPTER.encodeWithTag(writer, 3, value.remote_error)
                RemotePingRequest.ADAPTER.encodeWithTag(writer, 8, value.remote_ping_request)
                RemotePingResponse.ADAPTER.encodeWithTag(writer, 9, value.remote_ping_response)
                RemoteKeyInject.ADAPTER.encodeWithTag(writer, 10, value.remote_key_inject)
                RemoteImeKeyInject.ADAPTER.encodeWithTag(writer, 20, value.remote_ime_key_inject)
                RemoteStart.ADAPTER.encodeWithTag(writer, 40, value.remote_start)
                RemoteAppLinkLaunchRequest.ADAPTER.encodeWithTag(writer, 90, value.remote_app_link_launch_request)
                writer.writeBytes(value.unknownFields)
            }

            override fun decode(reader: ProtoReader): RemoteMessage {
                var remote_configure: RemoteConfigure? = null
                var remote_set_active: RemoteSetActive? = null
                var remote_error: RemoteError? = null
                var remote_ping_request: RemotePingRequest? = null
                var remote_ping_response: RemotePingResponse? = null
                var remote_key_inject: RemoteKeyInject? = null
                var remote_ime_key_inject: RemoteImeKeyInject? = null
                var remote_start: RemoteStart? = null
                var remote_app_link_launch_request: RemoteAppLinkLaunchRequest? = null

                val unknownFields = reader.forEachTag { tag ->
                    when (tag) {
                        1 -> remote_configure = RemoteConfigure.ADAPTER.decode(reader)
                        2 -> remote_set_active = RemoteSetActive.ADAPTER.decode(reader)
                        3 -> remote_error = RemoteError.ADAPTER.decode(reader)
                        8 -> remote_ping_request = RemotePingRequest.ADAPTER.decode(reader)
                        9 -> remote_ping_response = RemotePingResponse.ADAPTER.decode(reader)
                        10 -> remote_key_inject = RemoteKeyInject.ADAPTER.decode(reader)
                        20 -> remote_ime_key_inject = RemoteImeKeyInject.ADAPTER.decode(reader)
                        40 -> remote_start = RemoteStart.ADAPTER.decode(reader)
                        90 -> remote_app_link_launch_request = RemoteAppLinkLaunchRequest.ADAPTER.decode(reader)
                        else -> reader.readUnknownField(tag)
                    }
                }
                return RemoteMessage(
                    remote_configure, remote_set_active, remote_error,
                    remote_ping_request, remote_ping_response, remote_key_inject,
                    remote_ime_key_inject, remote_start, remote_app_link_launch_request, unknownFields
                )
            }

            override fun redact(value: RemoteMessage): RemoteMessage = value
        }
    }
}
