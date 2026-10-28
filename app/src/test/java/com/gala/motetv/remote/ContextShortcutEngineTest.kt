package com.gala.motetv.remote

import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextShortcutEngineTest {

    private val engine = ContextShortcutEngine()
    private val mockDevice = TvDevice(id = "tv_1", name = "Test TV", host = "192.168.1.100")

    @Test
    fun testDisconnectedContext() {
        val mode = engine.evaluateContext(ConnectionState.Disconnected, isMirroring = false, isMediaCasting = false)
        assertEquals(RemoteContextMode.DISCONNECTED, mode)

        val actions = engine.getRecommendedActions(mode)
        assertTrue("Disconnected mode should prioritize Connect TV action", actions.any { it.id == "connect" })
    }

    @Test
    fun testMirroringContext() {
        val mode = engine.evaluateContext(ConnectionState.Ready(mockDevice), isMirroring = true, isMediaCasting = false)
        assertEquals(RemoteContextMode.SCREEN_MIRRORING, mode)

        val actions = engine.getRecommendedActions(mode)
        assertTrue("Mirroring mode should prioritize Stop Mirroring action", actions.any { it.id == "stop_mirror" })
    }

    @Test
    fun testCastingContext() {
        val mode = engine.evaluateContext(ConnectionState.Ready(mockDevice), isMirroring = false, isMediaCasting = true)
        assertEquals(RemoteContextMode.MEDIA_CASTING, mode)

        val actions = engine.getRecommendedActions(mode)
        assertTrue("Casting mode should prioritize Play/Pause and seeking", actions.any { it.id == "play_pause" })
    }
}
