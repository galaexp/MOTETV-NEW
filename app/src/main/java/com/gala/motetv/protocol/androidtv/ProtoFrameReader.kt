package com.gala.motetv.protocol.androidtv

import java.io.EOFException
import java.io.InputStream
import java.io.IOException

class ProtoFrameReader(
    private val inputStream: InputStream,
    private val maxFrameSizeBytes: Int = MAX_ALLOWED_FRAME_SIZE
) {
    /**
     * Reads a length-prefixed protobuf frame from the stream.
     * The length prefix is encoded as a protobuf varint32.
     *
     * @return Byte array containing the serialized protobuf message.
     * @throws EOFException if stream ends before frame starts or during frame.
     * @throws IOException if frame size exceeds safety bounds or read fails.
     */
    @Throws(IOException::class)
    fun readNextFrame(): ByteArray {
        val length = readVarint32(inputStream)
        if (length < 0) {
            throw IOException("Malformed frame length: $length")
        }
        if (length == 0) {
            return ByteArray(0)
        }
        if (length > maxFrameSizeBytes) {
            throw IOException("Frame size $length exceeds maximum allowed size $maxFrameSizeBytes")
        }

        val buffer = ByteArray(length)
        readFully(inputStream, buffer, 0, length)
        return buffer
    }

    companion object {
        const val MAX_ALLOWED_FRAME_SIZE = 64 * 1024 // 64 KB safety limit

        /**
         * Reads exactly [length] bytes into [buffer] starting at [offset].
         * Handles partial TCP packets correctly without assuming a single read returns all bytes.
         */
        @Throws(IOException::class)
        fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
            var totalRead = 0
            while (totalRead < length) {
                val bytesRead = inputStream.read(buffer, offset + totalRead, length - totalRead)
                if (bytesRead < 0) {
                    throw EOFException("Unexpected EOF after reading $totalRead of $length bytes")
                }
                totalRead += bytesRead
            }
        }

        /**
         * Reads a 32-bit integer encoded as a protobuf varint.
         */
        @Throws(IOException::class)
        fun readVarint32(inputStream: InputStream): Int {
            var result = 0
            var shift = 0
            while (shift < 32) {
                val b = inputStream.read()
                if (b < 0) {
                    if (shift == 0) {
                        throw EOFException("Stream closed (EOF) before frame length")
                    } else {
                        throw EOFException("Encountered EOF while reading varint length")
                    }
                }
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
            throw IOException("Varint32 exceeds maximum bit length (malformed)")
        }
    }
}
