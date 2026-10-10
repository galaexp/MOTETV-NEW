package com.gala.motetv.launcher

import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.remote.RemoteState
import com.gala.motetv.remote.TvRemoteManager
import com.google.android.apps.tv.remote.protocol.Direction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLauncherTest {

    private class FakeTvRemoteManager(
        initialState: RemoteState = RemoteState.READY
    ) : TvRemoteManager {
        val launchedLinks = mutableListOf<String>()
        val sentKeys = mutableListOf<Pair<Int, Direction>>()

        private val _remoteState = MutableStateFlow(initialState)
        override val remoteState: StateFlow<RemoteState> = _remoteState

        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Ready(TvDevice("1", "Mi TV", "192.168.1.100")))
        override val connectionState: StateFlow<ConnectionState> = _connectionState

        override val currentDevice: StateFlow<TvDevice?> = MutableStateFlow(null)
        override val imeActive: StateFlow<Boolean> = MutableStateFlow(false)
        override val lastImeText: StateFlow<String> = MutableStateFlow("")

        override fun connect(device: TvDevice) {}
        override fun disconnect() {}
        override fun sendKey(keyCode: Int, direction: Direction) {
            sentKeys.add(keyCode to direction)
        }
        override fun sendImeText(text: String) {}
        override fun sendImeTextWithFallback(text: String, useKeyCodesFallback: Boolean) {}
        override fun sendChar(char: Char) {}

        override fun launchAppLink(appLink: String) {
            launchedLinks.add(appLink)
        }
    }

    @Test
    fun testYouTubeLaunchStrategiesPriority() {
        val yt = DefaultApps.YOUTUBE
        assertEquals("com.google.android.youtube.tv", yt.packageId)
        assertEquals("https://www.youtube.com", yt.primaryDeepLink)

        val uris = yt.getAllLaunchUris()
        assertTrue(uris.isNotEmpty())
        assertEquals("https://www.youtube.com", uris[0])
        assertTrue(uris.contains("vnd.youtube.launch://"))
        assertTrue(uris.contains("vnd.youtube://"))
        assertTrue(uris.contains("market://launch?id=com.google.android.youtube.tv"))
    }

    @Test
    fun testDefaultAppsCatalogCompleteness() {
        val apps = DefaultApps.ALL_DEFAULT_APPS
        assertTrue(apps.any { it.id == "youtube" })
        assertTrue(apps.any { it.id == "netflix" })
        assertTrue(apps.any { it.id == "prime_video" })
        assertTrue(apps.any { it.id == "disney_plus" })
        assertTrue(apps.any { it.id == "plex" })
        assertTrue(apps.any { it.id == "twitch" })
        assertTrue(apps.any { it.id == "spotify" })
        assertTrue(apps.any { it.id == "kodi" })
    }

    @Test
    fun testLaunchAppSuccessWhenRemoteReady() {
        val fakeManager = FakeTvRemoteManager(RemoteState.READY)
        val result = AppLauncher.launch(DefaultApps.YOUTUBE, fakeManager, strategyIndex = 0)

        assertTrue(result is LaunchResult.Success)
        val success = result as LaunchResult.Success
        assertEquals("YouTube", success.appName)
        assertEquals("https://www.youtube.com", success.uriUsed)
        assertEquals(LaunchMethod.PROTOCOL_DEEP_LINK, success.method)
        assertEquals(listOf("https://www.youtube.com"), fakeManager.launchedLinks)
    }

    @Test
    fun testLaunchAlternativeStrategy() {
        val fakeManager = FakeTvRemoteManager(RemoteState.READY)
        // strategy index 1 is alternative deep link
        val result = AppLauncher.launch(DefaultApps.YOUTUBE, fakeManager, strategyIndex = 1)

        assertTrue(result is LaunchResult.Success)
        val success = result as LaunchResult.Success
        assertEquals("vnd.youtube.launch://", success.uriUsed)
        assertEquals(LaunchMethod.FALLBACK_DEEP_LINK, success.method)
        assertEquals(listOf("vnd.youtube.launch://"), fakeManager.launchedLinks)
    }

    @Test
    fun testLaunchAppFailsGracefullyWhenNotConnected() {
        val fakeManager = FakeTvRemoteManager(RemoteState.DISCONNECTED)
        val result = AppLauncher.launch(DefaultApps.YOUTUBE, fakeManager)

        assertTrue(result is LaunchResult.Failure)
        val failure = result as LaunchResult.Failure
        assertEquals("YouTube", failure.appName)
        assertTrue(failure.reason.contains("not connected"))
        assertTrue(fakeManager.launchedLinks.isEmpty())
    }

    @Test
    fun testCustomAppLaunchStrategy() {
        val customApp = AppDefinition(
            id = "vlc",
            name = "VLC",
            packageId = "org.videolan.vlc",
            primaryDeepLink = "vlc://",
            isCustom = true
        )
        val fakeManager = FakeTvRemoteManager(RemoteState.READY)
        val result = AppLauncher.launch(customApp, fakeManager)

        assertTrue(result is LaunchResult.Success)
        assertEquals(listOf("vlc://"), fakeManager.launchedLinks)
    }
}
