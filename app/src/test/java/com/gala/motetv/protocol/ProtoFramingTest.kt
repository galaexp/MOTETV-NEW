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
    fun testPartialTcpChunkReads() {
        val payload = ByteArray(500) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(out)
        writer.writeFrame(payload)

        val fullBytes = out.toByteArray()

        // Simulate fragmented network stream returning 3 bytes per read()
        val chunkedStream = object : InputStream() {
            private var index = 0
            override fun read(): Int {
                return if (index < fullBytes.size) fullBytes[index++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= fullBytes.size) return -1
                val toRead = minOf(len, 3, fullBytes.size - index)
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
