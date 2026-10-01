package com.gala.motetv.protocol.androidtv

import java.io.IOException
import java.io.OutputStream

class ProtoFrameWriter(
    private val outputStream: OutputStream
) {
    /**
     * Writes a length-prefixed protobuf frame to the stream.
     * The length prefix is encoded as a protobuf varint32.
     */
    @Throws(IOException::class)
    fun writeFrame(payload: ByteArray) {
        writeVarint32(outputStream, payload.size)
        outputStream.write(payload)
        outputStream.flush()
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
