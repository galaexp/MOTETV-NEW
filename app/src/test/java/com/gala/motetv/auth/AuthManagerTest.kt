package com.gala.motetv.auth

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthManagerTest {

    @Test
    fun testGuestModeStateTransitions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val authManager = AuthManager(context)

        // Initially guest mode should be false
        assertFalse("Guest mode should be initially false", authManager.isGuestMode.value)

        // Setting guest mode to true
        authManager.setGuestMode(true)
        assertTrue("Guest mode should be true after setGuestMode(true)", authManager.isGuestMode.value)

        // Signing out should set guest mode to true so local controls remain accessible
        authManager.signOut()
        assertTrue("Guest mode should remain active on sign out", authManager.isGuestMode.value)
    }

    @Test
    fun testGoogleSignInFailsGracefullyWhenUnconfigured() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val authManager = AuthManager(context)

        // When default_web_client_id is empty or missing, sign-in should return false and set actionable error message
        kotlinx.coroutines.runBlocking {
            val success = authManager.signInWithGoogle(context)
            assertFalse("Sign in should fail when unconfigured", success)
            assertNotNull("Error message should be set", authManager.errorMessage.value)
        }
    }
}
