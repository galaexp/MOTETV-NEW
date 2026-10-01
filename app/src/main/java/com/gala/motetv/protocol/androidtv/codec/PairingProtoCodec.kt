package com.gala.motetv.protocol.androidtv.codec

import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.protocol.androidtv.ProtoFrameWriter
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object PairingProtoCodec {

    // Wire types
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    fun encode(message: PairingMessage): ByteArray {
        val out = ByteArrayOutputStream()

        if (message.protocolVersion != 0) {
            writeVarintField(out, 1, message.protocolVersion)
        }
        if (message.status != Status.UNKNOWN) {
            writeVarintField(out, 2, message.status.value)
        }
        message.pairingRequest?.let { req ->
            writeMessageField(out, 10, encodePairingRequest(req))
        }
        message.pairingRequestAck?.let { ack ->
            writeMessageField(out, 11, encodePairingRequestAck(ack))
        }
        message.pairingOption?.let { opt ->
            writeMessageField(out, 20, encodePairingOption(opt))
        }
        message.pairingConfiguration?.let { cfg ->
            writeMessageField(out, 30, encodePairingConfiguration(cfg))
        }
        message.pairingConfigurationAck?.let { cfgAck ->
            writeMessageField(out, 31, encodePairingConfigurationAck(cfgAck))
        }
        message.pairingSecret?.let { sec ->
            writeMessageField(out, 40, encodePairingSecret(sec))
        }
        message.pairingSecretAck?.let { secAck ->
            writeMessageField(out, 41, encodePairingSecretAck(secAck))
        }

        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): PairingMessage {
        val `in` = ByteArrayInputStream(bytes)
        var protocolVersion = 2
        var status = Status.OK
        var pairingRequest: PairingRequest? = null
        var pairingRequestAck: PairingRequestAck? = null
        var pairingOption: PairingOption? = null
        var pairingConfiguration: PairingConfiguration? = null
        var pairingConfigurationAck: PairingConfigurationAck? = null
        var pairingSecret: PairingSecret? = null
        var pairingSecretAck: PairingSecretAck? = null

        while (`in`.available() > 0) {
            val tag = try {
                ProtoFrameReader.readVarint32(`in`)
            } catch (e: EOFException) {
                break
            }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> protocolVersion = ProtoFrameReader.readVarint32(`in`)
                2 -> status = Status.fromValue(ProtoFrameReader.readVarint32(`in`))
                10 -> pairingRequest = decodePairingRequest(readDelimitedBytes(`in`))
                11 -> pairingRequestAck = decodePairingRequestAck(readDelimitedBytes(`in`))
                20 -> pairingOption = decodePairingOption(readDelimitedBytes(`in`))
                30 -> pairingConfiguration = decodePairingConfiguration(readDelimitedBytes(`in`))
                31 -> pairingConfigurationAck = decodePairingConfigurationAck(readDelimitedBytes(`in`))
                40 -> pairingSecret = decodePairingSecret(readDelimitedBytes(`in`))
                41 -> pairingSecretAck = decodePairingSecretAck(readDelimitedBytes(`in`))
                else -> skipField(`in`, wireType)
            }
        }

        return PairingMessage(
            protocolVersion = protocolVersion,
            status = status,
            pairingRequest = pairingRequest,
            pairingRequestAck = pairingRequestAck,
            pairingOption = pairingOption,
            pairingConfiguration = pairingConfiguration,
            pairingConfigurationAck = pairingConfigurationAck,
            pairingSecret = pairingSecret,
            pairingSecretAck = pairingSecretAck
        )
    }

    // Encoding sub-messages
    private fun encodePairingRequest(req: PairingRequest): ByteArray {
        val out = ByteArrayOutputStream()
        if (req.serviceName.isNotEmpty()) {
            writeStringField(out, 1, req.serviceName)
        }
        if (req.clientName.isNotEmpty()) {
            writeStringField(out, 2, req.clientName)
        }
        return out.toByteArray()
    }

    private fun decodePairingRequest(bytes: ByteArray): PairingRequest {
        val `in` = ByteArrayInputStream(bytes)
        var serviceName = "androidtv-remote"
        var clientName = "MoteTV"

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> serviceName = String(readDelimitedBytes(`in`), Charsets.UTF_8)
                2 -> clientName = String(readDelimitedBytes(`in`), Charsets.UTF_8)
                else -> skipField(`in`, wireType)
            }
        }
        return PairingRequest(serviceName = serviceName, clientName = clientName)
    }

    private fun encodePairingRequestAck(ack: PairingRequestAck): ByteArray {
        val out = ByteArrayOutputStream()
        if (ack.status != Status.UNKNOWN) {
            writeVarintField(out, 1, ack.status.value)
        }
        if (ack.roleType != RoleType.UNKNOWN) {
            writeVarintField(out, 2, ack.roleType.value)
        }
        if (ack.protocolVersion != 0) {
            writeVarintField(out, 3, ack.protocolVersion)
        }
        return out.toByteArray()
    }

    private fun decodePairingRequestAck(bytes: ByteArray): PairingRequestAck {
        val `in` = ByteArrayInputStream(bytes)
        var status = Status.OK
        var roleType = RoleType.INPUT
        var protocolVersion = 2

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> status = Status.fromValue(ProtoFrameReader.readVarint32(`in`))
                2 -> roleType = RoleType.fromValue(ProtoFrameReader.readVarint32(`in`))
                3 -> protocolVersion = ProtoFrameReader.readVarint32(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return PairingRequestAck(status = status, roleType = roleType, protocolVersion = protocolVersion)
    }

    private fun encodePairingOption(opt: PairingOption): ByteArray {
        val out = ByteArrayOutputStream()
        if (opt.preferredRole != EncodingType.UNKNOWN) {
            writeVarintField(out, 1, opt.preferredRole.value)
        }
        for (enc in opt.inputEncodings) {
            writeVarintField(out, 2, enc.value)
        }
        for (enc in opt.outputEncodings) {
            writeVarintField(out, 3, enc.value)
        }
        return out.toByteArray()
    }

    private fun decodePairingOption(bytes: ByteArray): PairingOption {
        val `in` = ByteArrayInputStream(bytes)
        var preferredRole = EncodingType.HEXADECIMAL
        val inputEncodings = mutableListOf<EncodingType>()
        val outputEncodings = mutableListOf<EncodingType>()

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> preferredRole = EncodingType.fromValue(ProtoFrameReader.readVarint32(`in`))
                2 -> inputEncodings.add(EncodingType.fromValue(ProtoFrameReader.readVarint32(`in`)))
                3 -> outputEncodings.add(EncodingType.fromValue(ProtoFrameReader.readVarint32(`in`)))
                else -> skipField(`in`, wireType)
            }
        }
        return PairingOption(preferredRole, inputEncodings, outputEncodings)
    }

    private fun encodePairingConfiguration(cfg: PairingConfiguration): ByteArray {
        val out = ByteArrayOutputStream()
        if (cfg.encoding != EncodingType.UNKNOWN) {
            writeVarintField(out, 1, cfg.encoding.value)
        }
        if (cfg.clientRole != RoleType.UNKNOWN) {
            writeVarintField(out, 2, cfg.clientRole.value)
        }
        return out.toByteArray()
    }

    private fun decodePairingConfiguration(bytes: ByteArray): PairingConfiguration {
        val `in` = ByteArrayInputStream(bytes)
        var encoding = EncodingType.HEXADECIMAL
        var clientRole = RoleType.INPUT

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> encoding = EncodingType.fromValue(ProtoFrameReader.readVarint32(`in`))
                2 -> clientRole = RoleType.fromValue(ProtoFrameReader.readVarint32(`in`))
                else -> skipField(`in`, wireType)
            }
        }
        return PairingConfiguration(encoding, clientRole)
    }

    private fun encodePairingConfigurationAck(cfgAck: PairingConfigurationAck): ByteArray {
        val out = ByteArrayOutputStream()
        if (cfgAck.status != Status.UNKNOWN) {
            writeVarintField(out, 1, cfgAck.status.value)
        }
        return out.toByteArray()
    }

    private fun decodePairingConfigurationAck(bytes: ByteArray): PairingConfigurationAck {
        val `in` = ByteArrayInputStream(bytes)
        var status = Status.OK

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> status = Status.fromValue(ProtoFrameReader.readVarint32(`in`))
                else -> skipField(`in`, wireType)
            }
        }
        return PairingConfigurationAck(status)
    }

    private fun encodePairingSecret(sec: PairingSecret): ByteArray {
        val out = ByteArrayOutputStream()
        writeBytesField(out, 1, sec.secret)
        return out.toByteArray()
    }

    private fun decodePairingSecret(bytes: ByteArray): PairingSecret {
        val `in` = ByteArrayInputStream(bytes)
        var secret = ByteArray(0)

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> secret = readDelimitedBytes(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return PairingSecret(secret)
    }

    private fun encodePairingSecretAck(secAck: PairingSecretAck): ByteArray {
        val out = ByteArrayOutputStream()
        if (secAck.status != Status.UNKNOWN) {
            writeVarintField(out, 1, secAck.status.value)
        }
        secAck.secret?.let { sec ->
            writeBytesField(out, 2, sec)
        }
        return out.toByteArray()
    }

    private fun decodePairingSecretAck(bytes: ByteArray): PairingSecretAck {
        val `in` = ByteArrayInputStream(bytes)
        var status = Status.OK
        var secret: ByteArray? = null

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> status = Status.fromValue(ProtoFrameReader.readVarint32(`in`))
                2 -> secret = readDelimitedBytes(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return PairingSecretAck(status = status, secret = secret)
    }

    // Low-level write helpers
    private fun writeTag(out: OutputStream, fieldNumber: Int, wireType: Int) {
        val tag = (fieldNumber shl 3) or (wireType and 0x07)
        ProtoFrameWriter.writeVarint32(out, tag)
    }

    private fun writeVarintField(out: OutputStream, fieldNumber: Int, value: Int) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        ProtoFrameWriter.writeVarint32(out, value)
    }

    private fun writeStringField(out: OutputStream, fieldNumber: Int, value: String) {
        writeBytesField(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytesField(out: OutputStream, fieldNumber: Int, value: ByteArray) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        ProtoFrameWriter.writeVarint32(out, value.size)
        out.write(value)
    }

    private fun writeMessageField(out: OutputStream, fieldNumber: Int, messageBytes: ByteArray) {
        writeBytesField(out, fieldNumber, messageBytes)
    }

    // Low-level read helpers
    private fun readDelimitedBytes(`in`: InputStream): ByteArray {
        val length = ProtoFrameReader.readVarint32(`in`)
        val buf = ByteArray(length)
        ProtoFrameReader.readFully(`in`, buf, 0, length)
        return buf
    }

    private fun skipField(`in`: InputStream, wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> { ProtoFrameReader.readVarint32(`in`) }
            WIRE_FIXED64 -> {
                val buf = ByteArray(8)
                ProtoFrameReader.readFully(`in`, buf, 0, 8)
            }
            WIRE_LENGTH_DELIMITED -> {
                val len = ProtoFrameReader.readVarint32(`in`)
                val buf = ByteArray(len)
                ProtoFrameReader.readFully(`in`, buf, 0, len)
            }
            WIRE_FIXED32 -> {
                val buf = ByteArray(4)
                ProtoFrameReader.readFully(`in`, buf, 0, 4)
            }
            else -> throw IllegalArgumentException("Unknown protobuf wire type: $wireType")
        }
    }
}
