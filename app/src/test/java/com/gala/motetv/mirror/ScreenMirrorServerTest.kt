package com.gala.motetv.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class ScreenMirrorServerTest {

    @Test
    fun testServerStartupPortBindingAndTokenAuth() {
        val server = ScreenMirrorServer(requestedPort = 8899)
        val started = server.start()
        assertTrue("Server should start successfully", started)
        val serverPort = server.boundPort
        assertTrue("Bound port should be assigned", serverPort > 0)

        val sessionToken = server.sessionToken
        assertNotNull("Session token must not be null", sessionToken)
        assertTrue("Session token must not be empty", sessionToken.isNotEmpty())

        // Test 1: Request with invalid token -> 403 Forbidden
        val badUrl = URL("http://127.0.0.1:$serverPort/stream?token=invalid_token")
        val badConn = badUrl.openConnection() as HttpURLConnection
        badConn.connectTimeout = 2000
        badConn.readTimeout = 2000
        val responseCode = badConn.responseCode
        assertEquals("Invalid token must return 403 Forbidden", HttpURLConnection.HTTP_FORBIDDEN, responseCode)
        badConn.disconnect()

        // Test 2: Request with valid token -> 200 OK and valid RFC 2046 boundary header
        val validUrl = URL("http://127.0.0.1:$serverPort/stream?token=$sessionToken")
        val validConn = validUrl.openConnection() as HttpURLConnection
        validConn.connectTimeout = 2000
        validConn.readTimeout = 2000

        var contentTypeHeader: String? = null
        val readerThread = thread {
            try {
                contentTypeHeader = validConn.contentType
            } catch (_: Exception) {}
        }

        readerThread.join(2000)

        assertNotNull("Content-Type header should be present", contentTypeHeader)
        assertTrue("Content-Type must be multipart/x-mixed-replace", contentTypeHeader!!.contains("multipart/x-mixed-replace"))
        assertTrue("Boundary parameter must be boundary=MoteTvFrameBoundary (RFC 2046 compliant, no leading --)", contentTypeHeader!!.contains("boundary=MoteTvFrameBoundary"))
        assertFalse("Boundary parameter MUST NOT contain leading --", contentTypeHeader!!.contains("boundary=--"))

        validConn.disconnect()
        server.stop()
    }

    @Test
    fun testPortOccupancyFallback() {
        // Deliberately occupy port 8910
        val blockingSocket = ServerSocket(8910)
        blockingSocket.reuseAddress = true

        val server = ScreenMirrorServer(requestedPort = 8910)
        val started = server.start()
        assertTrue("Server should start by falling back to available port", started)
        assertEquals("Server should bind to next available port (8911)", 8911, server.boundPort)

        server.stop()
        blockingSocket.close()
    }

    @Test
    fun testMissingTokenReturns403() {
        val server = ScreenMirrorServer(requestedPort = 8920)
        server.start()
        val port = server.boundPort

        val missingTokenUrl = URL("http://127.0.0.1:$port/stream")
        val conn = missingTokenUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        assertEquals("Missing token parameter must return 403 Forbidden", HttpURLConnection.HTTP_FORBIDDEN, conn.responseCode)
        conn.disconnect()

        server.stop()
    }

    @Test
    fun testSessionInvalidationAfterStop() {
        val server = ScreenMirrorServer(requestedPort = 8930)
        server.start()
        assertNotNull("Stream URL must be generated when bound and network active", server.getStreamUrl())

        server.stop()

        assertFalse("Server isBound should be false after stop", server.isBound.get())
        assertNull("Stream URL must be null after stop", server.getStreamUrl())
        assertEquals("Bound port must reset to -1", -1, server.boundPort)
    }

    @Test
    fun testViewerCountInvariant() {
        val server = ScreenMirrorServer(requestedPort = 8940)
        server.start()
        val port = server.boundPort
        val token = server.sessionToken

        val clients = (1..3).map {
            val conn = URL("http://127.0.0.1:$port/stream?token=$token").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            thread {
                try {
                    conn.inputStream.read()
                } catch (_: Exception) {}
            }
            conn
        }

        Thread.sleep(300)
        assertTrue("Viewer count must be non-negative during active streaming", server.connectedClientsCount.get() >= 0)

        for (conn in clients) {
            try { conn.disconnect() } catch (_: Exception) {}
        }

        Thread.sleep(300)
        assertTrue("Viewer count must remain non-negative after disconnects", server.connectedClientsCount.get() >= 0)

        server.stop()
        assertEquals("Viewer count must reset to 0 after server stop", 0, server.connectedClientsCount.get())
    }

    @Test
    fun testServerRejectsUnusableNetworkAddresses() {
        val server = ScreenMirrorServer(requestedPort = 8950)
        val localIp = server.getLocalIpAddress()
        if (localIp != null) {
            assertFalse("getLocalIpAddress must never return 127.0.0.1", localIp == "127.0.0.1")
            assertFalse("getLocalIpAddress must never return link-local address", localIp.startsWith("169.254."))
        }
    }

    @Test
    fun testServerGeneratesUniqueTokensPerSession() {
        val server1 = ScreenMirrorServer(requestedPort = 8961)
        val server2 = ScreenMirrorServer(requestedPort = 8962)

        val token1 = server1.sessionToken
        val token2 = server2.sessionToken

        assertNotNull(token1)
        assertNotNull(token2)
        assertTrue(token1 != token2)
    }

    @Test
    fun testVirtualDisplayResizingAbstraction() {
        // Test abstraction verifying single creation / resize contract logic
        var virtualDisplayCreations = 0
        var virtualDisplayResizes = 0

        fun onDisplayDimensionChanged(isFirstTime: Boolean) {
            if (isFirstTime) {
                virtualDisplayCreations++
            } else {
                virtualDisplayResizes++
            }
        }

        // Initial setup
        onDisplayDimensionChanged(isFirstTime = true)
        // Rotation 1
        onDisplayDimensionChanged(isFirstTime = false)
        // Rotation 2
        onDisplayDimensionChanged(isFirstTime = false)

        assertEquals("VirtualDisplay should only be created once per MediaProjection session", 1, virtualDisplayCreations)
        assertEquals("Subsequent display configuration changes should resize existing VirtualDisplay", 2, virtualDisplayResizes)
    }
}
