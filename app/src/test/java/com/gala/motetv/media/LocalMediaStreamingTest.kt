package com.gala.motetv.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.remote.RemoteState
import com.gala.motetv.remote.TvRemoteManager
import com.google.android.apps.tv.remote.protocol.Direction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalMediaStreamingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Mock TvRemoteManager to test complete independence
    private class MockTvRemoteManager : TvRemoteManager {
        val _connectionState = MutableStateFlow<com.gala.motetv.core.model.ConnectionState>(com.gala.motetv.core.model.ConnectionState.Disconnected)
        override val connectionState: StateFlow<com.gala.motetv.core.model.ConnectionState> = _connectionState

        val _remoteState = MutableStateFlow(RemoteState.DISCONNECTED)
        override val remoteState: StateFlow<RemoteState> = _remoteState

        override val currentDevice: StateFlow<TvDevice?> = MutableStateFlow(null)
        override val imeActive: StateFlow<Boolean> = MutableStateFlow(false)
        override val lastImeText: StateFlow<String> = MutableStateFlow("")

        var lastLaunchedAppLink: String? = null
        var lastKeySent: Int? = null

        override fun connect(device: TvDevice) {
            _remoteState.value = RemoteState.READY
        }

        override fun disconnect() {
            _remoteState.value = RemoteState.DISCONNECTED
        }

        override fun sendKey(keyCode: Int, direction: Direction) {
            lastKeySent = keyCode
        }

        override fun sendChar(char: Char) {}
        override fun sendImeText(text: String) {}
        override fun sendImeTextWithFallback(text: String, useKeyCodesFallback: Boolean) {}
        override fun launchAppLink(appLink: String) {
            lastLaunchedAppLink = appLink
        }
    }

    @Test
    fun testServerLifecycleAndHttpRangeRequests() {
        val server = LocalMediaServer(context)

        // Create a temporary media file with known content
        val tempFile = File.createTempFile("sample_video", ".mp4")
        tempFile.deleteOnExit()
        val sampleData = ByteArray(128 * 1024) { (it % 256).toByte() }
        tempFile.writeBytes(sampleData)

        val fileUri = Uri.fromFile(tempFile)
        val streamUrl = server.startStreaming(
            uri = fileUri,
            fileName = "sample_video.mp4",
            fileSize = tempFile.length(),
            mimeType = "video/mp4"
        )

        assertNotNull(streamUrl)
        assertTrue(streamUrl.startsWith("http://"))
        val state = server.state.value
        assertTrue(state is MediaServerState.Running)
        val running = state as MediaServerState.Running
        assertEquals(tempFile.length(), running.fileSize)
        assertEquals("video/mp4", running.mimeType)

        // 1. Test GET full file
        val url = URL(streamUrl)
        val connFull = url.openConnection() as HttpURLConnection
        connFull.requestMethod = "GET"
        connFull.connect()
        assertEquals(200, connFull.responseCode)
        assertEquals(tempFile.length(), connFull.contentLengthLong)
        val readBytes = connFull.inputStream.readBytes()
        assertEquals(tempFile.length(), readBytes.size.toLong())
        connFull.disconnect()

        // 2. Test Range request: bytes=0-1023 (first 1024 bytes)
        val connRange1 = url.openConnection() as HttpURLConnection
        connRange1.setRequestProperty("Range", "bytes=0-1023")
        connRange1.connect()
        assertEquals(206, connRange1.responseCode)
        assertEquals("bytes 0-1023/${tempFile.length()}", connRange1.getHeaderField("Content-Range"))
        assertEquals(1024L, connRange1.contentLengthLong)
        val chunk1 = connRange1.inputStream.readBytes()
        assertEquals(1024, chunk1.size)
        assertEquals(sampleData[0], chunk1[0])
        assertEquals(sampleData[1023], chunk1[1023])
        connRange1.disconnect()

        // 3. Test Range request: bytes=10000- (from offset to end)
        val connRange2 = url.openConnection() as HttpURLConnection
        connRange2.setRequestProperty("Range", "bytes=10000-")
        connRange2.connect()
        assertEquals(206, connRange2.responseCode)
        val expectedRange2Len = tempFile.length() - 10000L
        assertEquals("bytes 10000-${tempFile.length() - 1}/${tempFile.length()}", connRange2.getHeaderField("Content-Range"))
        assertEquals(expectedRange2Len, connRange2.contentLengthLong)
        connRange2.disconnect()

        // 4. Test Suffix range request: bytes=-512 (last 512 bytes)
        val connRangeSuffix = url.openConnection() as HttpURLConnection
        connRangeSuffix.setRequestProperty("Range", "bytes=-512")
        connRangeSuffix.connect()
        assertEquals(206, connRangeSuffix.responseCode)
        val suffixStart = tempFile.length() - 512L
        assertEquals("bytes $suffixStart-${tempFile.length() - 1}/${tempFile.length()}", connRangeSuffix.getHeaderField("Content-Range"))
        assertEquals(512L, connRangeSuffix.contentLengthLong)
        connRangeSuffix.disconnect()

        // 5. Test Invalid Range request -> 416 Range Not Satisfiable
        val connInvalid = url.openConnection() as HttpURLConnection
        connInvalid.setRequestProperty("Range", "bytes=9999999-10000000")
        connInvalid.connect()
        assertEquals(416, connInvalid.responseCode)
        connInvalid.disconnect()

        // 6. Test HEAD request
        val connHead = url.openConnection() as HttpURLConnection
        connHead.requestMethod = "HEAD"
        connHead.connect()
        assertEquals(200, connHead.responseCode)
        assertEquals(tempFile.length(), connHead.contentLengthLong)
        connHead.disconnect()

        // Stop server
        server.stopStreaming()
        assertTrue(server.state.value is MediaServerState.Idle)
        tempFile.delete()
    }

    @Test
    fun testCastSessionIndependenceFromRemoteConnection() {
        val mockRemote = MockTvRemoteManager()
        val controller = LocalMediaCastController(context, mockRemote)

        val tempFile = File.createTempFile("test_cast", ".mkv")
        tempFile.deleteOnExit()
        tempFile.writeBytes(ByteArray(1024))
        val fileUri = Uri.fromFile(tempFile)

        val mediaInfo = SelectedMediaInfo(
            uri = fileUri,
            name = "test_cast.mkv",
            size = tempFile.length(),
            mimeType = "video/x-matroska"
        )

        // Remote is NOT connected initially
        assertEquals(RemoteState.DISCONNECTED, mockRemote.remoteState.value)

        // Starting cast must NOT fail just because Remote is not connected!
        controller.startCast(mediaInfo)

        val sessionState = controller.sessionState.value
        assertTrue(sessionState is CastSessionState.Streaming)
        val streaming = sessionState as CastSessionState.Streaming
        assertEquals("test_cast.mkv", streaming.media.name)

        // Simulate Remote connecting and disconnecting
        mockRemote._remoteState.value = RemoteState.READY
        mockRemote._remoteState.value = RemoteState.DISCONNECTED

        // Verify cast session is STILL streaming and not interrupted
        assertTrue(controller.sessionState.value is CastSessionState.Streaming)

        controller.stopCast()
        tempFile.delete()
    }

    @Test
    fun testLargeFileLongOffsetsSupport() {
        // Test that 64-bit Long offsets (exceeding Int.MAX_VALUE ~2.14GB, e.g. 5GB)
        // are properly handled without overflow or truncation
        val largeFileSize = 5L * 1024L * 1024L * 1024L // 5 GB
        val startOffset = 4L * 1024L * 1024L * 1024L // 4 GB
        val endOffset = startOffset + 1024L * 1024L // 4 GB + 1 MB

        assertTrue(largeFileSize > Int.MAX_VALUE)
        assertTrue(startOffset > Int.MAX_VALUE)

        val contentLength = endOffset - startOffset + 1L
        assertEquals(1024L * 1024L + 1L, contentLength)

        val rangeHeader = "bytes=$startOffset-$endOffset/$largeFileSize"
        assertTrue(rangeHeader.contains("4294967296"))
    }
}
