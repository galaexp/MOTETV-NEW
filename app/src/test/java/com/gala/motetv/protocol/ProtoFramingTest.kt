package com.gala.motetv.protocol

import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.protocol.androidtv.ProtoFrameWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class ProtoFramingTest {

    @Test
    fun testVarint32EncodingAndDecoding() {
        val testValues = listOf(0, 1, 127, 128, 300, 16384, 65535, 1000000)
        for (v in testValues) {
            val out = ByteArrayOutputStream()
            ProtoFrameWriter.writeVarint32(out, v)
            val bytes = out.toByteArray()

            val `in` = ByteArrayInputStream(bytes)
            val decoded = ProtoFrameReader.readVarint32(`in`)
            assertEquals("Failed for value $v", v, decoded)
        }
    }

    @Test
    fun testFrameWriteAndReadRoundTrip() {
        val payload = "Hello Android TV Remote v2!".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(out)
        writer.writeFrame(payload)

        val `in` = ByteArrayInputStream(out.toByteArray())
        val reader = ProtoFrameReader(`in`)
        val readPayload = reader.readNextFrame()

        assertArrayEquals(payload, readPayload)
    }

    @Test
    fun testZeroLengthFrame() {
        val payload = ByteArray(0)
        val out = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(out)
        writer.writeFrame(payload)

        val `in` = ByteArrayInputStream(out.toByteArray())
        val reader = ProtoFrameReader(`in`)
        val readPayload = reader.readNextFrame()

        assertEquals(0, readPayload.size)
    }

    @Test
    fun testTwoConsecutiveFrames() {
        val payload1 = "First Frame".toByteArray(Charsets.UTF_8)
        val payload2 = "Second Frame".toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(out)
        writer.writeFrame(payload1)
        writer.writeFrame(payload2)

        val `in` = ByteArrayInputStream(out.toByteArray())
        val reader = ProtoFrameReader(`in`)

        val read1 = reader.readNextFrame()
        val read2 = reader.readNextFrame()

        assertArrayEquals(payload1, read1)
        assertArrayEquals(payload2, read2)
    }

    @Test
    fun testPartialTcpChunkReads() {
        val payload = ByteArray(500) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(out)
        writer.writeFrame(payload)

        val fullBytes = out.toByteArray()

        // Simulate fragmented network stream returning 1-3 bytes per read()
        val chunkedStream = object : InputStream() {
            private var index = 0
            override fun read(): Int {
                return if (index < fullBytes.size) fullBytes[index++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= fullBytes.size) return -1
                val toRead = minOf(len, 2, fullBytes.size - index)
                System.arraycopy(fullBytes, index, b, off, toRead)
                index += toRead
                return toRead
            }
        }

        val reader = ProtoFrameReader(chunkedStream)
        val readPayload = reader.readNextFrame()
        assertArrayEquals(payload, readPayload)
    }

    @Test
    fun testSplitVarintAcrossReads() {
        val payload = ByteArray(300) { 0x42 }
        val out = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(out)
        writer.writeFrame(payload)

        val fullBytes = out.toByteArray()

        // Stream that returns strictly 1 byte per read to test multi-byte varint splits
        val singleByteStream = object : InputStream() {
            private var index = 0
            override fun read(): Int {
                return if (index < fullBytes.size) fullBytes[index++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= fullBytes.size) return -1
                b[off] = fullBytes[index++]
                return 1
            }
        }

        val reader = ProtoFrameReader(singleByteStream)
        val readPayload = reader.readNextFrame()
        assertArrayEquals(payload, readPayload)
    }

    @Test
    fun testEofThrowsEofException() {
        val `in` = ByteArrayInputStream(byteArrayOf())
        val reader = ProtoFrameReader(`in`)
        assertThrows(EOFException::class.java) {
            reader.readNextFrame()
        }
    }

    @Test
    fun testTruncatedPayloadThrowsEofException() {
        val out = ByteArrayOutputStream()
        ProtoFrameWriter.writeVarint32(out, 50) // indicates 50 bytes
        out.write(ByteArray(20)) // only write 20 bytes

        val `in` = ByteArrayInputStream(out.toByteArray())
        val reader = ProtoFrameReader(`in`)
        assertThrows(EOFException::class.java) {
            reader.readNextFrame()
        }
    }

    @Test
    fun testEofDuringMultiByteVarintThrowsEofException() {
        val `in` = ByteArrayInputStream(byteArrayOf(0x80.toByte())) // Continuation bit set, but stream ends
        val reader = ProtoFrameReader(`in`)
        assertThrows(EOFException::class.java) {
            reader.readNextFrame()
        }
    }

    @Test
    fun testOversizedFrameThrowsIOException() {
        val out = ByteArrayOutputStream()
        ProtoFrameWriter.writeVarint32(out, 128 * 1024) // 128 KB > 64 KB limit

        val `in` = ByteArrayInputStream(out.toByteArray())
        val reader = ProtoFrameReader(`in`, maxFrameSizeBytes = 64 * 1024)
        assertThrows(IOException::class.java) {
            reader.readNextFrame()
        }
    }
}
