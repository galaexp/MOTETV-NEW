package com.gala.motetv.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.example.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class AuthManager(private val context: Context) {
    private val auth: FirebaseAuth? = try {
        FirebaseAuth.getInstance()
    } catch (e: Exception) {
        android.util.Log.e("AuthManager", "Error initializing FirebaseAuth", e)
        null
    }

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth?.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Explicit guest mode: Allows full local TV control (remote, mirror, cast) without requiring cloud login
    private val _isGuestMode = MutableStateFlow(false)
    val isGuestMode: StateFlow<Boolean> = _isGuestMode.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _currentUser.value = firebaseAuth.currentUser
    }

    init {
        try {
            auth?.addAuthStateListener(authStateListener)
        } catch (_: Exception) {}
    }

    fun setGuestMode(enabled: Boolean) {
        _isGuestMode.value = enabled
        _errorMessage.value = null
    }

    suspend fun signInWithGoogle(contextForCredential: Context): Boolean {
        _isLoading.value = true
        _errorMessage.value = null

        // Validate Firebase availability
        if (auth == null) {
            val msg = "Firebase Authentication is not available on this device."
            android.util.Log.e("AuthManager", msg)
            _errorMessage.value = msg
            _isLoading.value = false
            return false
        }

        // Validate Web Client ID from resources without hardcoded fallback disguise
        val webClientId = try {
            contextForCredential.getString(R.string.default_web_client_id).trim()
        } catch (e: Exception) {
            ""
        }

        if (webClientId.isEmpty()) {
            val msg = "Google Sign-In is not configured: default_web_client_id is missing in strings.xml."
            android.util.Log.e("AuthManager", msg)
            _errorMessage.value = msg
            _isLoading.value = false
            return false
        }

        try {
            val googleIdOption = GetSignInWithGoogleOption.Builder(
                serverClientId = webClientId
            ).build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(contextForCredential)
            val result = credentialManager.getCredential(contextForCredential, request)
            val credential = result.credential

            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val googleIdToken = googleIdTokenCredential.idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)

                // Sign in to Firebase with the credential (without unnecessary prior signOut)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                val user = authResult.user

                if (user == null) {
                    val msg = "Authentication failed: No user account was returned by Firebase."
                    _errorMessage.value = msg
                    _isLoading.value = false
                    return false
                }

                _currentUser.value = user
                _isGuestMode.value = false
                _isLoading.value = false
                return true
            } else {
                val msg = "Unexpected credential type received: ${credential::class.java.simpleName}"
                _errorMessage.value = msg
                _isLoading.value = false
                return false
            }
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            android.util.Log.d("AuthManager", "User cancelled Google Sign-In")
            _errorMessage.value = "Sign-in was cancelled."
            _isLoading.value = false
            return false
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            android.util.Log.e("AuthManager", "Account collision: ${e.message}", e)
            _errorMessage.value = "An account already exists with this email using a different sign-in method."
            _isLoading.value = false
            return false
        } catch (e: Exception) {
            android.util.Log.e("AuthManager", "Google Sign-In failed: ${e.message}", e)
            val rawMsg = e.localizedMessage ?: "Unknown authentication error"
            _errorMessage.value = if (rawMsg.contains("16") || rawMsg.contains("Account reauth failed")) {
                "Account verification failed (16). Please check Google Play Services or network connection."
            } else {
                "Sign-in failed: $rawMsg"
            }
            _isLoading.value = false
            return false
        }
    }

    /**
     * Signs out of Firebase without tearing down any active local Android TV remote connection.
     * Transitions user state to guest mode so local controls remain accessible.
     */
    fun signOut() {
        try {
            auth?.signOut()
        } catch (e: Exception) {
            android.util.Log.e("AuthManager", "Error signing out: ${e.message}")
        }
        _currentUser.value = null
        _isGuestMode.value = true
        _errorMessage.value = null
    }
}
