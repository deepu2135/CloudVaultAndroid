package com.cloudvault.app

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TelegramClient {
    private const val TAG = "TelegramClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private var client: Client? = null
    private var isLibraryLoaded = false
    private var libraryLoadError: String? = null

    var isAvailable: Boolean = false
        private set

    private fun loadNativeLibrary(): Boolean {
        if (libraryLoadError != null) return false
        if (isLibraryLoaded) return true

        try {
            System.loadLibrary("tdjni")
            isLibraryLoaded = true
            isAvailable = true
            Log.d(TAG, "Native library libtdjni.so loaded successfully!")
            return true
        } catch (e: Throwable) {
            val err = "Failed to load libtdjni.so: ${e.message}"
            Log.e(TAG, err, e)
            libraryLoadError = err
            return false
        }
    }

    fun initialize(context: Context) {
        if (client != null) return
        _authState.value = TelegramAuthState.Initializing
        if (!loadNativeLibrary()) {
            _authState.value = TelegramAuthState.Error(libraryLoadError ?: "TDLib native library missing")
            return
        }

        scope.launch {
            try {
                val dbDir = File(context.filesDir, "tdlib_db")
                val filesDir = File(context.cacheDir, "tdlib_files")
                if (!dbDir.exists()) dbDir.mkdirs()
                if (!filesDir.exists()) filesDir.mkdirs()

                client = Client.create(
                    { update -> handleUpdate(context, update) },
                    { e -> Log.e(TAG, "TDLib update exception", e) },
                    { e -> Log.e(TAG, "TDLib default exception", e) }
                )
            } catch (e: Throwable) {
                Log.e(TAG, "TDLib Client creation failed", e)
                _authState.value = TelegramAuthState.Error("TDLib init failed: ${e.message}")
            }
        }
    }

    private fun handleUpdate(context: Context, update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                when (update.authorizationState) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters(context)
                    is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramAuthState.WaitPhoneNumber
                    is TdApi.AuthorizationStateWaitCode -> _authState.value = TelegramAuthState.WaitCode
                    is TdApi.AuthorizationStateWaitPassword -> _authState.value = TelegramAuthState.WaitPassword
                    is TdApi.AuthorizationStateReady -> {
                        _authState.value = TelegramAuthState.Ready
                        TdlibManager.setSessionActive(context, true)
                    }
                    is TdApi.AuthorizationStateClosed -> {
                        client = null
                        _authState.value = TelegramAuthState.Idle
                    }
                }
            }
        }
    }

    private fun sendTdlibParameters(context: Context) {
        val apiId = TdlibManager.getApiId(context)
        val apiHash = TdlibManager.getApiHash(context)

        if (apiId <= 0 || apiHash.isBlank()) {
            _authState.value = TelegramAuthState.Error("API ID & Hash missing. Set them in Settings.")
            return
        }

        val parameters = TdApi.SetTdlibParameters().apply {
            apiId = apiId
            apiHash = apiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = "1.0.0"
            databaseDirectory = File(context.filesDir, "tdlib_db").absolutePath
            filesDirectory = File(context.cacheDir, "tdlib_files").absolutePath
            useMessageDatabase = true
            useSecretChats = false
        }

        client?.send(parameters) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetTdlibParameters failed: ${result.message}")
                _authState.value = TelegramAuthState.Error(result.message)
            } else {
                client?.send(TdApi.CheckDatabaseEncryptionKey()) {}
            }
        }
    }

    fun setPhoneNumber(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { result ->
            if (result is TdApi.Error) {
                _authState.value = TelegramAuthState.Error("Phone error: ${result.message}")
            }
        }
    }

    fun checkCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            if (result is TdApi.Error) {
                _authState.value = TelegramAuthState.Error("Invalid code: ${result.message}")
            }
        }
    }

    fun checkPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            if (result is TdApi.Error) {
                _authState.value = TelegramAuthState.Error("Invalid password: ${result.message}")
            }
        }
    }

    suspend fun <T : TdApi.Object> sendRequest(query: TdApi.Function<T>): T {
        val activeClient = client ?: throw IllegalStateException("TDLib client not initialized")
        return suspendCancellableCoroutine { continuation ->
            activeClient.send(query) { result ->
                if (result is TdApi.Error) {
                    continuation.resumeWithException(Exception("TDLib Error [${result.code}]: ${result.message}"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as T)
                }
            }
        }
    }
}
