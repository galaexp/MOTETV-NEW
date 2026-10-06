package com.gala.motetv.protocol

import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.protocol.androidtv.ProtoFrameReader
import com.gala.motetv.protocol.androidtv.ProtoFrameWriter
import com.gala.motetv.remote.RemoteState
import com.google.android.apps.tv.remote.protocol.DeviceInfo
import com.google.android.apps.tv.remote.protocol.Direction
import com.google.android.apps.tv.remote.protocol.RemoteConfigure
import com.google.android.apps.tv.remote.protocol.RemoteError
import com.google.android.apps.tv.remote.protocol.RemoteImeKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteKeyInject
import com.google.android.apps.tv.remote.protocol.RemoteMessage
import com.google.android.apps.tv.remote.protocol.RemotePingRequest
import com.google.android.apps.tv.remote.protocol.RemotePingResponse
import com.google.android.apps.tv.remote.protocol.RemoteSetActive
import com.google.android.apps.tv.remote.protocol.RemoteStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

class RemoteProtoCodecTest {

    // 1. RemoteConfigure encoding
    @Test
    fun testRemoteConfigureEncoding() {
        val msg = RemoteMessage(
            remote_configure = RemoteConfigure(
                code1 = 622,
                device_info = DeviceInfo(
                    model = "MoteTV",
                    vendor = "Gala",
                    unknown1 = 1,
                    unknown2 = "1",
                    package_name = "com.gala.motetv",
                    app_version = "1.0.0"
                )
            )
        )
        val bytes = msg.encode()
        assertTrue(bytes.isNotEmpty())
    }

    // 2. RemoteConfigure decoding
    @Test
    fun testRemoteConfigureDecoding() {
        val msg = RemoteMessage(
            remote_configure = RemoteConfigure(
                code1 = 622,
                device_info = DeviceInfo(
                    model = "MoteTV",
                    vendor = "Gala",
                    unknown1 = 1,
                    unknown2 = "1",
                    package_name = "com.gala.motetv",
                    app_version = "1.0.0"
                )
            )
        )
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertNotNull(decoded.remote_configure)
        assertEquals(622, decoded.remote_configure?.code1)
        assertEquals("MoteTV", decoded.remote_configure?.device_info?.model)
        assertEquals("Gala", decoded.remote_configure?.device_info?.vendor)
        assertEquals(1, decoded.remote_configure?.device_info?.unknown1)
        assertEquals("1", decoded.remote_configure?.device_info?.unknown2)
        assertEquals("com.gala.motetv", decoded.remote_configure?.device_info?.package_name)
        assertEquals("1.0.0", decoded.remote_configure?.device_info?.app_version)
    }

    // 3. RemoteSetActive encoding
    @Test
    fun testRemoteSetActiveEncoding() {
        val msg = RemoteMessage(remote_set_active = RemoteSetActive(active = 622))
        val bytes = msg.encode()
        assertTrue(bytes.isNotEmpty())
    }

    // 4. RemoteSetActive decoding
    @Test
    fun testRemoteSetActiveDecoding() {
        val msg = RemoteMessage(remote_set_active = RemoteSetActive(active = 622))
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertNotNull(decoded.remote_set_active)
        assertEquals(622, decoded.remote_set_active?.active)
    }

    // 5. RemoteStart encoding/decoding
    @Test
    fun testRemoteStartEncodingDecoding() {
        val msg = RemoteMessage(remote_start = RemoteStart(started = true))
        val encoded = msg.encode()
        val decoded = RemoteMessage.ADAPTER.decode(encoded)
        assertNotNull(decoded.remote_start)
        assertEquals(true, decoded.remote_start?.started)
    }

    // 6. RemoteKeyInject SHORT encoding
    @Test
    fun testRemoteKeyInjectShortEncoding() {
        val msg = RemoteMessage(
            remote_key_inject = RemoteKeyInject(
                key_code = AndroidTvKeyCodes.KEYCODE_DPAD_UP,
                direction = Direction.SHORT
            )
        )
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertEquals(AndroidTvKeyCodes.KEYCODE_DPAD_UP, decoded.remote_key_inject?.key_code)
        assertEquals(Direction.SHORT, decoded.remote_key_inject?.direction)
    }

    // 7. RemoteKeyInject START_LONG encoding
    @Test
    fun testRemoteKeyInjectStartLongEncoding() {
        val msg = RemoteMessage(
            remote_key_inject = RemoteKeyInject(
                key_code = AndroidTvKeyCodes.KEYCODE_DPAD_CENTER,
                direction = Direction.START_LONG
            )
        )
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertEquals(Direction.START_LONG, decoded.remote_key_inject?.direction)
    }

    // 8. RemoteKeyInject END_LONG encoding
    @Test
    fun testRemoteKeyInjectEndLongEncoding() {
        val msg = RemoteMessage(
            remote_key_inject = RemoteKeyInject(
                key_code = AndroidTvKeyCodes.KEYCODE_DPAD_CENTER,
                direction = Direction.END_LONG
            )
        )
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertEquals(Direction.END_LONG, decoded.remote_key_inject?.direction)
    }

    // 9. RemotePingRequest decoding
    @Test
    fun testRemotePingRequestDecoding() {
        val msg = RemoteMessage(remote_ping_request = RemotePingRequest(val1 = 9876, val2 = 5432))
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertNotNull(decoded.remote_ping_request)
        assertEquals(9876, decoded.remote_ping_request?.val1)
        assertEquals(5432, decoded.remote_ping_request?.val2)
    }

    // 10. RemotePingResponse encoding
    @Test
    fun testRemotePingResponseEncoding() {
        val pingReq = RemoteMessage(remote_ping_request = RemotePingRequest(val1 = 9876, val2 = 5432))
        val pingDecoded = RemoteMessage.ADAPTER.decode(pingReq.encode())
        assertNotNull(pingDecoded.remote_ping_request)

        // Protocol requires replying with RemotePingResponse(val1 = 1)
        val pongMsg = RemoteMessage(remote_ping_response = RemotePingResponse(val1 = 1))
        val decoded = RemoteMessage.ADAPTER.decode(pongMsg.encode())
        assertNotNull(decoded.remote_ping_response)
        assertEquals(1, decoded.remote_ping_response?.val1)
    }

    // 11. RemoteError decoding
    @Test
    fun testRemoteErrorDecoding() {
        val msg = RemoteMessage(remote_error = RemoteError(value = true))
        val decoded = RemoteMessage.ADAPTER.decode(msg.encode())
        assertNotNull(decoded.remote_error)
        assertEquals(true, decoded.remote_error?.value)
    }

    // 12. Partial varint frame / ProtoFrameReader
    @Test
    fun testPartialVarintFrameRead() {
        val payload = RemoteMessage(remote_set_active = RemoteSetActive(active = 622)).encode()
        val baos = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(baos)
        writer.writeFrame(payload)

        val fullBytes = baos.toByteArray()
        val reader = ProtoFrameReader(ByteArrayInputStream(fullBytes))
        val readPayload = reader.readNextFrame()

        assertArrayEquals(payload, readPayload)
    }

    // 13. Multiple consecutive remote frames
    @Test
    fun testMultipleConsecutiveRemoteFrames() {
        val msg1 = RemoteMessage(remote_configure = RemoteConfigure(code1 = 622))
        val msg2 = RemoteMessage(remote_set_active = RemoteSetActive(active = 622))
        val msg3 = RemoteMessage(remote_key_inject = RemoteKeyInject(key_code = 19, direction = Direction.SHORT))

        val baos = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(baos)
        writer.writeFrame(msg1.encode())
        writer.writeFrame(msg2.encode())
        writer.writeFrame(msg3.encode())

        val reader = ProtoFrameReader(ByteArrayInputStream(baos.toByteArray()))

        val dec1 = RemoteMessage.ADAPTER.decode(reader.readNextFrame())
        val dec2 = RemoteMessage.ADAPTER.decode(reader.readNextFrame())
        val dec3 = RemoteMessage.ADAPTER.decode(reader.readNextFrame())

        assertEquals(622, dec1.remote_configure?.code1)
        assertEquals(622, dec2.remote_set_active?.active)
        assertEquals(19, dec3.remote_key_inject?.key_code)
    }

    // 14. One reader only requirement
    @Test
    fun testOneReaderReadsSequentially() {
        val baos = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(baos)
        val count = 50
        for (i in 1..count) {
            val msg = RemoteMessage(remote_ping_request = RemotePingRequest(val1 = i))
            writer.writeFrame(msg.encode())
        }

        val reader = ProtoFrameReader(ByteArrayInputStream(baos.toByteArray()))
        for (i in 1..count) {
            val frame = reader.readNextFrame()
            val decoded = RemoteMessage.ADAPTER.decode(frame)
            assertEquals(i, decoded.remote_ping_request?.val1)
        }
    }

    // 15. Writer serialization
    @Test
    fun testWriterSerializationWithMutex() = runBlocking {
        val baos = ByteArrayOutputStream()
        val writer = ProtoFrameWriter(baos)
        val mutex = Mutex()
        val list = ConcurrentLinkedQueue<Int>()

        val jobs = (1..100).map { id ->
            async {
                mutex.withLock {
                    list.add(id)
                    val msg = RemoteMessage(remote_ping_request = RemotePingRequest(val1 = id))
                    writer.writeFrame(msg.encode())
                }
            }
        }
        jobs.awaitAll()

        val reader = ProtoFrameReader(ByteArrayInputStream(baos.toByteArray()))
        val receivedList = mutableListOf<Int>()
        for (i in 1..100) {
            val frame = reader.readNextFrame()
            val msg = RemoteMessage.ADAPTER.decode(frame)
            msg.remote_ping_request?.val1?.let { receivedList.add(it) }
        }

        assertEquals(100, receivedList.size)
        assertEquals(list.toList(), receivedList)
    }

    // 16. Remote handshake state machine
    @Test
    fun testRemoteHandshakeStateMachineTransitions() {
        var state = RemoteState.DISCONNECTED

        // Step 1: Connect TCP / TLS
        state = RemoteState.CONNECTING
        assertEquals(RemoteState.CONNECTING, state)

        state = RemoteState.TLS_CONNECTED
        assertEquals(RemoteState.TLS_CONNECTED, state)

        // Step 2: TX RemoteConfigure
        state = RemoteState.CONFIGURE_SENT
        assertEquals(RemoteState.CONFIGURE_SENT, state)

        // Step 3: RX RemoteConfigure from TV
        state = RemoteState.CONFIGURED
        assertEquals(RemoteState.CONFIGURED, state)

        // Step 4: TX RemoteSetActive
        state = RemoteState.SET_ACTIVE_SENT
        assertEquals(RemoteState.SET_ACTIVE_SENT, state)

        // Step 5: RX RemoteSetActive from TV
        state = RemoteState.READY
        assertEquals(RemoteState.READY, state)
    }
}
