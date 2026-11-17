package com.gala.motetv.protocol.androidtv

import java.io.IOException
import java.io.OutputStream

class FrameWriteException(
    val bytesWrittenStarted: Boolean,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class ProtoFrameWriter(
    private val outputStream: OutputStream
) {
    /**
     * Writes a length-prefixed protobuf frame to the stream.
     * The length prefix is encoded as a protobuf varint32.
     * Throws FrameWriteException indicating whether any bytes already entered the stream.
     */
    @Throws(IOException::class)
    fun writeFrame(payload: ByteArray) {
        var bytesWrittenStarted = false
        try {
            bytesWrittenStarted = writeVarint32Tracked(outputStream, payload.size)
            bytesWrittenStarted = true
            outputStream.write(payload)
            outputStream.flush()
        } catch (e: IOException) {
            if (e is FrameWriteException) throw e
            throw FrameWriteException(
                bytesWrittenStarted = bytesWrittenStarted,
                message = e.message ?: "Frame write failed (started=$bytesWrittenStarted)",
                cause = e
            )
        }
    }

    private fun writeVarint32Tracked(outputStream: OutputStream, value: Int): Boolean {
        var v = value
        var wroteAny = false
        try {
            while (true) {
                if ((v and 0x7F.inv()) == 0) {
                    outputStream.write(v)
                    return true
                } else {
                    outputStream.write((v and 0x7F) or 0x80)
                    wroteAny = true
                    v = v ushr 7
                }
            }
        } catch (e: IOException) {
            throw FrameWriteException(
                bytesWrittenStarted = wroteAny,
                message = e.message ?: "Varint write failed (started=$wroteAny)",
                cause = e
            )
        }
    }

    companion object {
        /**
         * Writes a 32-bit integer encoded as a protobuf varint.
         */
        @Throws(IOException::class)
        fun writeVarint32(outputStream: OutputStream, value: Int) {
            var v = value
            while (true) {
                if ((v and 0x7F.inv()) == 0) {
                    outputStream.write(v)
                    return
                } else {
                    outputStream.write((v and 0x7F) or 0x80)
                    v = v ushr 7
                }
            }
        }

        /**
         * Encodes a 32-bit integer as varint bytes into a ByteArray.
         */
        fun encodeVarint32(value: Int): ByteArray {
            val list = mutableListOf<Byte>()
            var v = value
            while (true) {
                if ((v and 0x7F.inv()) == 0) {
                    list.add(v.toByte())
                    break
                } else {
                    list.add(((v and 0x7F) or 0x80).toByte())
                    v = v ushr 7
                }
            }
            return list.toByteArray()
        }
    }
}
