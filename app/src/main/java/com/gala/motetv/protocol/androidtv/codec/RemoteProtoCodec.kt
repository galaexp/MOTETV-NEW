package com.gala.motetv.protocol.androidtv.codec

import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.protocol.androidtv.ProtoFrameWriter
import com.gala.motetv.protocol.androidtv.model.KeyDirection
import com.gala.motetv.protocol.androidtv.model.RemoteConfigure
import com.gala.motetv.protocol.androidtv.model.RemoteImeKeyInject
import com.gala.motetv.protocol.androidtv.model.RemoteKeyInject
import com.gala.motetv.protocol.androidtv.model.RemoteMessage
import com.gala.motetv.protocol.androidtv.model.RemotePingRequest
import com.gala.motetv.protocol.androidtv.model.RemotePong
import com.gala.motetv.protocol.androidtv.model.RemoteSetActive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object RemoteProtoCodec {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    fun encode(message: RemoteMessage): ByteArray {
        val out = ByteArrayOutputStream()

        message.remoteConfigure?.let { cfg ->
            writeMessageField(out, 1, encodeRemoteConfigure(cfg))
        }
        message.remoteSetActive?.let { act ->
            writeMessageField(out, 2, encodeRemoteSetActive(act))
        }
        message.remotePingRequest?.let { ping ->
            writeMessageField(out, 3, encodeRemotePingRequest(ping))
        }
        message.remotePong?.let { pong ->
            writeMessageField(out, 4, encodeRemotePong(pong))
        }
        message.remoteKeyInject?.let { key ->
            writeMessageField(out, 5, encodeRemoteKeyInject(key))
        }
        message.remoteImeKeyInject?.let { ime ->
            writeMessageField(out, 6, encodeRemoteImeKeyInject(ime))
        }

        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): RemoteMessage {
        val `in` = ByteArrayInputStream(bytes)
        var remoteConfigure: RemoteConfigure? = null
        var remoteSetActive: RemoteSetActive? = null
        var remotePingRequest: RemotePingRequest? = null
        var remotePong: RemotePong? = null
        var remoteKeyInject: RemoteKeyInject? = null
        var remoteImeKeyInject: RemoteImeKeyInject? = null

        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> remoteConfigure = decodeRemoteConfigure(readDelimitedBytes(`in`))
                2 -> remoteSetActive = decodeRemoteSetActive(readDelimitedBytes(`in`))
                3 -> remotePingRequest = decodeRemotePingRequest(readDelimitedBytes(`in`))
                4 -> remotePong = decodeRemotePong(readDelimitedBytes(`in`))
                5 -> remoteKeyInject = decodeRemoteKeyInject(readDelimitedBytes(`in`))
                6 -> remoteImeKeyInject = decodeRemoteImeKeyInject(readDelimitedBytes(`in`))
                else -> skipField(`in`, wireType)
            }
        }

        return RemoteMessage(
            remoteConfigure = remoteConfigure,
            remoteSetActive = remoteSetActive,
            remotePingRequest = remotePingRequest,
            remotePong = remotePong,
            remoteKeyInject = remoteKeyInject,
            remoteImeKeyInject = remoteImeKeyInject
        )
    }

    private fun encodeRemoteConfigure(cfg: RemoteConfigure): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, cfg.code1)
        return out.toByteArray()
    }

    private fun decodeRemoteConfigure(bytes: ByteArray): RemoteConfigure {
        val `in` = ByteArrayInputStream(bytes)
        var code1 = 622
        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> code1 = ProtoFrameReader.readVarint32(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return RemoteConfigure(code1 = code1)
    }

    private fun encodeRemoteSetActive(act: RemoteSetActive): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, act.active)
        return out.toByteArray()
    }

    private fun decodeRemoteSetActive(bytes: ByteArray): RemoteSetActive {
        val `in` = ByteArrayInputStream(bytes)
        var active = 1
        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> active = ProtoFrameReader.readVarint32(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return RemoteSetActive(active)
    }

    private fun encodeRemotePingRequest(ping: RemotePingRequest): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, ping.val1)
        writeVarintField(out, 2, ping.val2)
        return out.toByteArray()
    }

    private fun decodeRemotePingRequest(bytes: ByteArray): RemotePingRequest {
        val `in` = ByteArrayInputStream(bytes)
        var val1 = 0
        var val2 = 0
        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> val1 = ProtoFrameReader.readVarint32(`in`)
                2 -> val2 = ProtoFrameReader.readVarint32(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return RemotePingRequest(val1, val2)
    }

    private fun encodeRemotePong(pong: RemotePong): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, pong.val1)
        return out.toByteArray()
    }

    private fun decodeRemotePong(bytes: ByteArray): RemotePong {
        val `in` = ByteArrayInputStream(bytes)
        var val1 = 0
        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> val1 = ProtoFrameReader.readVarint32(`in`)
                else -> skipField(`in`, wireType)
            }
        }
        return RemotePong(val1)
    }

    fun encodeRemoteKeyInject(key: RemoteKeyInject): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, key.keyCode)
        writeVarintField(out, 2, key.direction.value)
        return out.toByteArray()
    }

    private fun decodeRemoteKeyInject(bytes: ByteArray): RemoteKeyInject {
        val `in` = ByteArrayInputStream(bytes)
        var keyCode = 0
        var direction = KeyDirection.SHORT
        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> keyCode = ProtoFrameReader.readVarint32(`in`)
                2 -> direction = KeyDirection.fromValue(ProtoFrameReader.readVarint32(`in`))
                else -> skipField(`in`, wireType)
            }
        }
        return RemoteKeyInject(keyCode, direction)
    }

    private fun encodeRemoteImeKeyInject(ime: RemoteImeKeyInject): ByteArray {
        val out = ByteArrayOutputStream()
        if (ime.appInfo != 0) {
            writeVarintField(out, 1, ime.appInfo)
        }
        writeBytesField(out, 2, ime.text.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun decodeRemoteImeKeyInject(bytes: ByteArray): RemoteImeKeyInject {
        val `in` = ByteArrayInputStream(bytes)
        var appInfo = 0
        var text = ""
        while (`in`.available() > 0) {
            val tag = try { ProtoFrameReader.readVarint32(`in`) } catch (_: EOFException) { break }
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> appInfo = ProtoFrameReader.readVarint32(`in`)
                2 -> text = String(readDelimitedBytes(`in`), Charsets.UTF_8)
                else -> skipField(`in`, wireType)
            }
        }
        return RemoteImeKeyInject(appInfo, text)
    }

    // Low level writers
    private fun writeTag(out: OutputStream, fieldNumber: Int, wireType: Int) {
        val tag = (fieldNumber shl 3) or (wireType and 0x07)
        ProtoFrameWriter.writeVarint32(out, tag)
    }

    private fun writeVarintField(out: OutputStream, fieldNumber: Int, value: Int) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        ProtoFrameWriter.writeVarint32(out, value)
    }

    private fun writeBytesField(out: OutputStream, fieldNumber: Int, value: ByteArray) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        ProtoFrameWriter.writeVarint32(out, value.size)
        out.write(value)
    }

    private fun writeMessageField(out: OutputStream, fieldNumber: Int, messageBytes: ByteArray) {
        writeBytesField(out, fieldNumber, messageBytes)
    }

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
            else -> throw IllegalArgumentException("Unknown wire type: $wireType")
        }
    }
}
