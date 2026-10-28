package com.gala.motetv.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
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

        // Give reader thread time to connect and get headers
        readerThread.join(2000)

        assertNotNull("Content-Type header should be present", contentTypeHeader)
        assertTrue("Content-Type must be multipart/x-mixed-replace", contentTypeHeader!!.contains("multipart/x-mixed-replace"))
        assertTrue("Boundary parameter must be boundary=MoteTvFrameBoundary (RFC 2046 compliant, no leading --)", contentTypeHeader!!.contains("boundary=MoteTvFrameBoundary"))
        assertFalse("Boundary parameter MUST NOT contain leading --", contentTypeHeader!!.contains("boundary=--"))

        validConn.disconnect()
        server.stop()
    }

    @Test
    fun testServerRejectsUnusableNetworkAddresses() {
        val server = ScreenMirrorServer(requestedPort = 8900)
        // 127.0.0.1 must be rejected by getLocalIpAddress
        val localIp = server.getLocalIpAddress()
        if (localIp != null) {
            assertFalse("getLocalIpAddress must never return 127.0.0.1", localIp == "127.0.0.1")
            assertFalse("getLocalIpAddress must never return link-local address", localIp.startsWith("169.254."))
        }
    }

    @Test
    fun testServerGeneratesUniqueTokensPerSession() {
        val server1 = ScreenMirrorServer(requestedPort = 8901)
        val server2 = ScreenMirrorServer(requestedPort = 8902)
        
        val token1 = server1.sessionToken
        val token2 = server2.sessionToken

        assertNotNull(token1)
        assertNotNull(token2)
        assertTrue(token1 != token2)
    }
}
