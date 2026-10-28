package com.gala.motetv.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.example.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
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

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _currentUser.value = firebaseAuth.currentUser
    }

    init {
        try {
            auth?.addAuthStateListener(authStateListener)
        } catch (_: Exception) {}
    }

    suspend fun signInWithGoogle(contextForCredential: Context): Boolean {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val webClientId = try {
                contextForCredential.getString(R.string.default_web_client_id)
            } catch (e: Exception) {
                "1098385313983-j7rk9t0e8ltitmggaek972v1khuh0bn5.apps.googleusercontent.com"
            }

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
                
                if (auth != null) {
                    try {
                        // Explicitly sign out to clear any potentially conflicting stale sessions
                        // before attempting a fresh sign-in with the new credential.
                        auth.signOut()
                        auth.signInWithCredential(firebaseCredential).await()
                    } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                        android.util.Log.e("AuthManager", "Account collision: ${e.message}", e)
                        throw Exception("An account already exists with this email address, but it uses a different sign-in method. Please sign in using your original method.", e)
                    } catch (e: Exception) {
                        android.util.Log.e("AuthManager", "Firebase Sign-In failed", e)
                        throw e
                    }
                }
                _isLoading.value = false
                return true
            } else {
                throw IllegalStateException("Unexpected credential type received")
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthManager", "Google Sign-In failed: ${e.message}", e)
            _errorMessage.value = "Sign-in failed: ${e.localizedMessage ?: "Unknown error"}"
            _isLoading.value = false
            return false
        }
    }

    fun signOut() {
        try {
            auth?.signOut()
        } catch (_: Exception) {}
        _currentUser.value = null
    }
}
