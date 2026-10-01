package com.gala.motetv

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.DeviceStatus
import com.gala.motetv.core.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase1Test {

    @Before
    fun setUp() {
        TvLogger.clearLogs()
    }

    @Test
    fun testTvDeviceDefaultPortsAndService() {
        val device = TvDevice(
            id = "test-device-id",
            name = "Living Room Mi Stick",
            host = "192.168.1.120",
            manufacturer = "Xiaomi",
            model = "Mi TV Stick"
        )

        assertEquals("test-device-id", device.id)
        assertEquals("Living Room Mi Stick", device.name)
        assertEquals("192.168.1.120", device.host)
        // Android TV Remote v2 Pairing Port must be 6467
        assertEquals(6467, device.pairingPort)
        // Android TV Remote v2 Remote Port must be 6466
        assertEquals(6466, device.remotePort)
        assertEquals("androidtv-remote", device.serviceName)
        assertFalse(device.isPaired)
    }

    @Test
    fun testDeviceStatusValues() {
        val statuses = DeviceStatus.values()
        assertTrue(statuses.contains(DeviceStatus.Available))
        assertTrue(statuses.contains(DeviceStatus.Connecting))
        assertTrue(statuses.contains(DeviceStatus.PairingRequired))
        assertTrue(statuses.contains(DeviceStatus.Connected))
        assertTrue(statuses.contains(DeviceStatus.Offline))
        assertTrue(statuses.contains(DeviceStatus.Paired))
    }

    @Test
    fun testConnectionStateHierarchy() {
        val states: List<ConnectionState> = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Discovering,
            ConnectionState.Connecting("192.168.1.50", 6467),
            ConnectionState.Pairing("WaitingForUserPin"),
            ConnectionState.Authenticating,
            ConnectionState.Ready(
                TvDevice(
                    id = "id1",
                    name = "Mi TV",
                    host = "192.168.1.50",
                    isPaired = true
                )
            ),
            ConnectionState.Reconnecting(attempt = 1, nextDelayMs = 1000L),
            ConnectionState.Failed("TLS_FAILED", "Failed TLS handshake")
        )

        assertEquals(8, states.size)
        assertTrue(states[0] is ConnectionState.Disconnected)
        assertTrue(states[1] is ConnectionState.Discovering)
        val connecting = states[2] as ConnectionState.Connecting
        assertEquals(6467, connecting.port)
        val ready = states[5] as ConnectionState.Ready
        assertTrue(ready.device.isPaired)
    }

    @Test
    fun testTvLoggerStructuredLogging() {
        TvLogger.protocolLoggingEnabled = true
        TvLogger.i(TvLogger.TAG_DISCOVERY, "Discovered device at 192.168.1.50")
        TvLogger.d(TvLogger.TAG_PROTO, "Sent PairingRequest")
        TvLogger.w(TvLogger.TAG_PAIR, "Waiting for user PIN")

        val logs = TvLogger.getLogs()
        assertEquals(3, logs.size)
        assertEquals("Discovery", logs[0].tag)
        assertEquals("INFO", logs[0].level)
        assertTrue(logs[0].message.contains("192.168.1.50"))

        val formatted = logs[0].format()
        assertTrue(formatted.contains("[MoteTV][Discovery] INFO:"))
    }
}
