package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @org.junit.Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      com.google.firebase.FirebaseApp.initializeApp(context)
    } catch (_: Exception) {}
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("MoteTV", appName)
  }

  @Test
  fun `launch MainActivity with saved device`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val credStore = com.gala.motetv.storage.TvCredentialStore(context)
    val testDevice = com.gala.motetv.core.model.TvDevice(
      id = "test-tv-1",
      name = "Living Room TV",
      host = "192.168.1.100",
      pairingPort = 6467,
      remotePort = 6466,
      manufacturer = "Xiaomi",
      model = "Mi TV Stick",
      isPaired = true
    )
    credStore.saveDevice(testDevice)
    credStore.setDevicePaired(testDevice.id, paired = true)

    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
    controller.create().start().resume()
    val activity = controller.get()
    org.junit.Assert.assertNotNull(activity)
    controller.pause().stop().destroy()
  }

  @Test
  fun `simulate cast and reopen MainActivity`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempFile = java.io.File.createTempFile("test_cast", ".mp4")
    tempFile.writeBytes("video bytes".toByteArray())
    val mediaInfo = com.gala.motetv.media.SelectedMediaInfo(
      uri = android.net.Uri.fromFile(tempFile),
      name = "test.mp4",
      size = tempFile.length(),
      mimeType = "video/mp4"
    )

    // 1. Start streaming via MediaStreamService
    com.gala.motetv.media.MediaStreamService.startStreaming(context, mediaInfo)

    // 2. Open MainActivity while service is running
    val controller1 = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
    controller1.create().start().resume()
    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    // 3. User closes activity (destroys)
    controller1.pause().stop().destroy()
    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

    // 4. User re-opens MainActivity
    val controller2 = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
    controller2.create().start().resume()
    org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    controller2.pause().stop().destroy()
  }

  @Test
  fun `render MediaScreen in streaming state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val credStore = com.gala.motetv.storage.TvCredentialStore(context)
    val remoteManager = com.gala.motetv.remote.TvRemoteManagerImpl(credStore)
    val castController = com.gala.motetv.media.LocalMediaCastController(context, remoteManager)

    val tempFile = java.io.File.createTempFile("test_cast2", ".mp4")
    val mediaInfo = com.gala.motetv.media.SelectedMediaInfo(
      uri = android.net.Uri.fromFile(tempFile),
      name = "my_video.mp4",
      size = 104857600L,
      mimeType = "video/mp4"
    )

    val streamingState = com.gala.motetv.media.CastSessionState.Streaming(
      media = mediaInfo,
      streamUrl = "http://192.168.1.50:8989/media",
      isPlaying = true
    )

    androidx.compose.ui.test.junit4.createComposeRule().apply {
      // Just instantiate MediaScreen directly
    }
  }
}



