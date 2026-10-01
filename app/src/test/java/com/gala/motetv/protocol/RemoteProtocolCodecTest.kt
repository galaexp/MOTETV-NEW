package com.gala.motetv.protocol

import com.gala.motetv.protocol.androidtv.codec.RemoteProtoCodec
import com.gala.motetv.protocol.androidtv.model.AndroidTvKeyCodes
import com.gala.motetv.protocol.androidtv.model.KeyDirection
import com.gala.motetv.protocol.androidtv.model.RemoteConfigure
import com.gala.motetv.protocol.androidtv.model.RemoteImeKeyInject
import com.gala.motetv.protocol.androidtv.model.RemoteKeyInject
import com.gala.motetv.protocol.androidtv.model.RemoteMessage
import com.gala.motetv.protocol.androidtv.model.RemotePingRequest
import com.gala.motetv.protocol.androidtv.model.RemotePong
import com.gala.motetv.protocol.androidtv.model.RemoteSetActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RemoteProtocolCodecTest {

    @Test
    fun testRemoteKeyInjectEncodingDecoding() {
        val keyMsg = RemoteMessage(
            remoteKeyInject = RemoteKeyInject(
                keyCode = AndroidTvKeyCodes.KEYCODE_DPAD_CENTER,
                direction = KeyDirection.SHORT
            )
        )

        val encoded = RemoteProtoCodec.encode(keyMsg)
        val decoded = RemoteProtoCodec.decode(encoded)

        assertNotNull(decoded.remoteKeyInject)
        assertEquals(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, decoded.remoteKeyInject?.keyCode)
        assertEquals(KeyDirection.SHORT, decoded.remoteKeyInject?.direction)
    }

    @Test
    fun testRemoteConfigureAndSetActive() {
        val cfgMsg = RemoteMessage(
            remoteConfigure = RemoteConfigure(code1 = 622),
            remoteSetActive = RemoteSetActive(active = 1)
        )

        val encoded = RemoteProtoCodec.encode(cfgMsg)
        val decoded = RemoteProtoCodec.decode(encoded)

        assertNotNull(decoded.remoteConfigure)
        assertEquals(622, decoded.remoteConfigure?.code1)
        assertNotNull(decoded.remoteSetActive)
        assertEquals(1, decoded.remoteSetActive?.active)
    }

    @Test
    fun testPingPongCodec() {
        val pingMsg = RemoteMessage(
            remotePingRequest = RemotePingRequest(val1 = 42, val2 = 99)
        )
        val decodedPing = RemoteProtoCodec.decode(RemoteProtoCodec.encode(pingMsg))
        assertEquals(42, decodedPing.remotePingRequest?.val1)
        assertEquals(99, decodedPing.remotePingRequest?.val2)

        val pongMsg = RemoteMessage(
            remotePong = RemotePong(val1 = 42)
        )
        val decodedPong = RemoteProtoCodec.decode(RemoteProtoCodec.encode(pongMsg))
        assertEquals(42, decodedPong.remotePong?.val1)
    }

    @Test
    fun testImeKeyInjectCodec() {
        val imeMsg = RemoteMessage(
            remoteImeKeyInject = RemoteImeKeyInject(appInfo = 0, text = "Stranger Things")
        )
        val decodedIme = RemoteProtoCodec.decode(RemoteProtoCodec.encode(imeMsg))
        assertEquals("Stranger Things", decodedIme.remoteImeKeyInject?.text)
    }
}
