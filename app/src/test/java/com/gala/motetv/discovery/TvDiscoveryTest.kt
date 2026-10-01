package com.gala.motetv.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gala.motetv.core.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvDiscoveryTest {

    private lateinit var context: Context
    private lateinit var discoveryManager: NsdTvDiscoveryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        discoveryManager = NsdTvDiscoveryManager(context)
    }

    @Test
    fun testServiceTypeConstant() {
        assertEquals("_androidtvremote2._tcp.", TvDevice.SERVICE_TYPE_V2)
    }

    @Test
    fun testAddManualDevice() {
        val device = discoveryManager.addManualDevice(
            name = "Bedroom Mi Stick",
            host = "192.168.1.155"
        )

        assertEquals("Bedroom Mi Stick", device.name)
        assertEquals("192.168.1.155", device.host)
        assertEquals(6467, device.pairingPort)
        assertEquals(6466, device.remotePort)
        assertEquals("androidtv-remote", device.serviceName)

        val devices = discoveryManager.discoveredDevices.value
        assertEquals(1, devices.size)
        assertEquals("Bedroom Mi Stick", devices[0].name)
    }

    @Test
    fun testAddMultipleDevicesAndDeduplication() {
        discoveryManager.addManualDevice("TV 1", "192.168.1.101")
        discoveryManager.addManualDevice("TV 2", "192.168.1.102")

        val devices = discoveryManager.discoveredDevices.value
        assertEquals(2, devices.size)

        // Adding same host again should update existing entry rather than duplicate
        discoveryManager.addManualDevice("TV 1 Updated", "192.168.1.101")
        val updatedDevices = discoveryManager.discoveredDevices.value
        assertEquals(2, updatedDevices.size)
    }

    @Test
    fun testClearDiscovered() {
        discoveryManager.addManualDevice("Living Room TV", "192.168.1.50")
        assertFalse(discoveryManager.discoveredDevices.value.isEmpty())

        discoveryManager.clearDiscovered()
        assertTrue(discoveryManager.discoveredDevices.value.isEmpty())
    }

    @Test
    fun testStartAndStopDiscoveryLifecycle() {
        discoveryManager.startDiscovery()
        // Discovery is triggered
        discoveryManager.stopDiscovery()
        assertFalse(discoveryManager.isDiscovering.value)
    }
}
