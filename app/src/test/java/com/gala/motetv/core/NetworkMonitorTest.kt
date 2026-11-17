package com.gala.motetv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {

    @Test
    fun ignoresSecondaryCellularCallbacksWhileWifiIsActive() {
        var now = 1_000L
        val tracker = ActiveNetworkTracker<String>(timeProvider = { now })

        // Initial state: Wi-Fi net-1 is active
        tracker.syncFromSystem(activeNetwork = "wifi-1", hasInternet = true, hasWifi = true)
        assertTrue(tracker.isConnected)
        assertTrue(tracker.isWifi)

        // Secondary cellular net-2 becomes available while wifi-1 remains system active
        val secondaryAvail = tracker.onAvailable(
            network = "cell-2",
            systemActiveNetwork = "wifi-1",
            hasInternet = true,
            hasWifi = false
        )
        assertFalse("Secondary network availability must be ignored", secondaryAvail.accepted)
        assertTrue(tracker.isWifi)

        // Secondary cellular capabilities change must NOT overwrite _isWifi to false
        val secondaryCaps = tracker.onCapabilitiesChanged(
            network = "cell-2",
            systemActiveNetwork = "wifi-1",
            hasInternet = true,
            hasWifi = false
        )
        assertFalse("Secondary network capability change must be ignored", secondaryCaps.accepted)
        assertTrue("Active network must still report Wi-Fi", tracker.isWifi)
        assertTrue(tracker.isConnected)

        // Secondary network lost must NOT disconnect active session
        val secondaryLost = tracker.onLost(
            network = "cell-2",
            systemActiveNetwork = "wifi-1",
            systemHasInternet = true,
            systemHasWifi = true
        )
        assertFalse("Secondary network loss must be ignored", secondaryLost.accepted)
        assertFalse(secondaryLost.triggerLost)
        assertTrue(tracker.isWifi)
    }

    @Test
    fun detectsActiveWifiLossAndSubsequentRestoration() {
        var now = 1_000L
        val tracker = ActiveNetworkTracker<String>(timeProvider = { now })

        tracker.syncFromSystem(activeNetwork = "wifi-1", hasInternet = true, hasWifi = true)

        val lost = tracker.onLost(
            network = "wifi-1",
            systemActiveNetwork = null,
            systemHasInternet = false,
            systemHasWifi = false
        )
        assertTrue(lost.accepted)
        assertTrue(lost.triggerLost)
        assertFalse(lost.isConnected)
        assertFalse(lost.isWifi)

        now += 1_000L
        val restored = tracker.onAvailable(
            network = "wifi-2",
            systemActiveNetwork = "wifi-2",
            hasInternet = true,
            hasWifi = true
        )
        assertTrue(restored.accepted)
        assertTrue(restored.triggerAvailable)
        assertTrue(restored.isConnected)
        assertTrue(restored.isWifi)
    }

    @Test
    fun debouncesDuplicateCallbacksOnSameActiveNetwork() {
        var now = 10_000L
        val tracker = ActiveNetworkTracker<String>(timeProvider = { now }, debounceMs = 500L)

        val first = tracker.onAvailable("wifi-1", "wifi-1", hasInternet = true, hasWifi = true)
        assertTrue(first.accepted)

        now += 100L
        val rapidDuplicate = tracker.onAvailable("wifi-1", "wifi-1", hasInternet = true, hasWifi = true)
        assertFalse("Rapid duplicate callback within debounce window should be ignored", rapidDuplicate.accepted)

        now += 600L
        val afterWindow = tracker.onAvailable("wifi-1", "wifi-1", hasInternet = true, hasWifi = true)
        assertTrue(afterWindow.accepted)
    }
}
