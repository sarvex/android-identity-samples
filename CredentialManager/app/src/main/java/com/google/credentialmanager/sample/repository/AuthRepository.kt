/*
 * Copyright 2021 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.credentialmanager.sample.repository

import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.credentialmanager.sample.api.ApiException
import com.google.credentialmanager.sample.api.ApiResult
import com.google.credentialmanager.sample.api.AuthApi
import com.google.credentialmanager.sample.api.Credential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works with the API, the local data store
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope
) {

    private companion object {
        const val TAG = "AuthRepository"

        // Keys for SharedPreferences
        val USERNAME = stringPreferencesKey("username")
        val SESSION_ID = stringPreferencesKey("session_id")
        val CREDENTIALS = stringSetPreferencesKey("credentials")
        val LOCAL_CREDENTIAL_ID = stringPreferencesKey("local_credential_id")

        suspend fun <T> DataStore<Preferences>.read(key: Preferences.Key<T>): T? {
            return data.map { it[key] }.first()
        }
    }

    private val signInStateMutable = MutableSharedFlow<SignInState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            val username = dataStore.read(USERNAME)
            val sessionId = dataStore.read(SESSION_ID)
            val initialState = when {
                username.isNullOrBlank() -> SignInState.SignedOut
                sessionId.isNullOrBlank() -> SignInState.SigningIn(username)
                else -> SignInState.SignedIn(username)
            }
            signInStateMutable.emit(initialState)
            if (initialState is SignInState.SignedIn) {
                refreshCredentials()
            }
        }
    }

    /**
     * Sends the username to the server. If it succeeds, the sign-in state will proceed to
     * [SignInState.SigningIn].
     */
    suspend fun sendUsername(username: String): Boolean {
        return when (val result = api.username(username)) {
            ApiResult.SignedOutFromServer -> {
                forceSignOut()
                false
            }
            is ApiResult.Success<*> -> {
                dataStore.edit { prefs ->
                    prefs[USERNAME] = username
                    prefs[SESSION_ID] = result.sessionId!!
                }
                signInStateMutable.emit(SignInState.SigningIn(username))
                true
            }
        }
    }

    /**
     * Signs in with a password. This should be called only when the sign-in state is
     * [SignInState.SigningIn]. If it succeeds, the sign-in state will proceed to
     * [SignInState.SignedIn].
     */
    suspend fun password(password: String): Boolean {
        val username = dataStore.read(USERNAME)
        val sessionId = dataStore.read(SESSION_ID)
        if (!username.isNullOrEmpty() && !sessionId.isNullOrEmpty()) {
            try {
                when (val result = api.password(sessionId, password)) {
                    ApiResult.SignedOutFromServer -> {
                        forceSignOut()
                        return false
                    }
                    is ApiResult.Success<*> -> {
                        if (result.sessionId != null) {
                            dataStore.edit { prefs ->
                                prefs[SESSION_ID] = result.sessionId
                            }
                        }
                        signInStateMutable.emit(SignInState.SignedIn(username))
                        refreshCredentials()
                        return true
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Invalid login credentials", e)

                // start login over again
                dataStore.edit { prefs ->
                    prefs.remove(USERNAME)
                    prefs.remove(SESSION_ID)
                    prefs.remove(CREDENTIALS)
                }

                signInStateMutable.emit(
                    SignInState.SignInError(e.message ?: "Invalid login credentials")
                )
            }
        } else {
            Log.e(TAG, "Please check if username and session id is present in your datastore")
        }
        return false
    }

    private suspend fun refreshCredentials() {
        val sessionId = dataStore.read(SESSION_ID)!!
        when (val result = api.getKeys(sessionId)) {
            ApiResult.SignedOutFromServer -> forceSignOut()
            is ApiResult.Success -> {
                dataStore.edit { prefs ->
                    result.sessionId?.let { prefs[SESSION_ID] = it }
                    prefs[CREDENTIALS] = result.data.toStringSet()
                }
            }
        }
    }

    private fun List<Credential>.toStringSet(): Set<String> {
        return mapIndexed { index, credential ->
            "$index;${credential.id};${credential.publicKey}"
        }.toSet()
    }

    private fun parseCredentials(set: Set<String>): List<Credential> {
        return set.map { s ->
            val (index, id, publicKey) = s.split(";")
            index to Credential(id, publicKey)
        }.sortedBy { (index, _) -> index }
            .map { (_, credential) -> credential }
    }

    /**
     * Clears the credentials. The sign-in state will proceed to [SignInState.SigningIn].
     */
    suspend fun clearCredentials() {
        val username = dataStore.read(USERNAME)!!
        dataStore.edit { prefs ->
            prefs.remove(CREDENTIALS)
        }
        signInStateMutable.emit(SignInState.SigningIn(username))
    }

    /**
     * Clears all the sign-in information. The sign-in state will proceed to
     * [SignInState.SignedOut].
     */
    suspend fun signOut() {
        dataStore.edit { prefs ->
            prefs.remove(USERNAME)
            prefs.remove(SESSION_ID)
            prefs.remove(CREDENTIALS)
        }
        signInStateMutable.emit(SignInState.SignedOut)
    }

    private suspend fun forceSignOut() {
        dataStore.edit { prefs ->
            prefs.remove(USERNAME)
            prefs.remove(SESSION_ID)
            prefs.remove(CREDENTIALS)
        }
        signInStateMutable.emit(SignInState.SignInError("Signed out by server"))
    }

    /**
     * Starts to register a new credential to the server. This should be called only when the
     * sign-in state is [SignInState.SignedIn].
     */
    suspend fun registerRequest(): JSONObject? {
      // TODO: Add an ability to create a passkey: Obtain the challenge and other options from the server endpoint.
        return null
    }

    /**
     * Finishes registering a new credential to the server. This should only be called after
     * a call to [registerRequest] and a local  API for public key generation.
     */
    suspend fun registerResponse(credentialResponse: CreatePublicKeyCredentialResponse): Boolean {
        //TODO : Finishes registering a new credential to the server.
        return false
    }

    /**
     * Starts to sign in with a credential. This should only be called when the sign-in state
     * is [SignInState.SigningIn].
     */
    suspend fun signinRequest(): JSONObject? {
        // TODO: Obtain the challenge and other options from the server endpoint.

        return null
    }

    /**
     * Finishes to signing in with a  credential. This should only be called after a call to
     * [signinRequest] and a local API for key assertion.
     */
    suspend fun signinResponse(credentialResponse: GetCredentialResponse): Boolean {
        //TODO : Finishes signing in with a  credential
        return false
    }

    suspend fun isSignedIn(): Boolean {
        val username = dataStore.read(USERNAME)
        val sessionId = dataStore.read(SESSION_ID)
        return when {
            username.isNullOrBlank() -> false
            sessionId.isNullOrBlank() -> false
            else -> true
        }
    }

    suspend fun getUserName(): String {
        val username = dataStore.read(USERNAME)
        return when {
            username.isNullOrBlank() -> ""
            else -> username
        }
    }

    /**
     * Removes a credential registered on the server.
     */
    suspend fun removeKey(credentialId: String) {
        try {
            val sessionId = dataStore.read(SESSION_ID)!!
            when (api.removeKey(sessionId, credentialId)) {
                ApiResult.SignedOutFromServer -> forceSignOut()
                is ApiResult.Success -> refreshCredentials()
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Cannot call removeKey", e)
        }
    }
}

