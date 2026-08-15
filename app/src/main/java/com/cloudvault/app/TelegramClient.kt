package com.cloudvault.app

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val _fileUpdates = MutableSharedFlow<TdApi.File>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val fileUpdates: SharedFlow<TdApi.File> = _fileUpdates.asSharedFlow()

    private val _messageUpdates = MutableSharedFlow<TdApi.Object>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messageUpdates: SharedFlow<TdApi.Object> = _messageUpdates.asSharedFlow()

    private var client: Client? = null
    private var isLibraryLoaded = false
    private var libraryLoadError: String? = null
    @Volatile private var currentTdlibAuthState: TdApi.AuthorizationState? = null

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
        if (client != null) {
            sendTdlibParameters(context)
            return
        }
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
                currentTdlibAuthState = update.authorizationState
                when (update.authorizationState) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters(context)
                    is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramAuthState.WaitPhoneNumber
                    is TdApi.AuthorizationStateWaitCode -> _authState.value = TelegramAuthState.WaitCode
                    is TdApi.AuthorizationStateWaitPassword -> _authState.value = TelegramAuthState.WaitPassword
                    is TdApi.AuthorizationStateReady -> {
                        _authState.value = TelegramAuthState.Ready
                        TdlibManager.setSessionActive(context, true)
                        // Auto-load vault items automatically upon connection
                        scope.launch {
                            TelegramRepository.loadVaultItems()
                        }
                    }
                    is TdApi.AuthorizationStateClosed -> {
                        client = null
                        currentTdlibAuthState = null
                        _authState.value = TelegramAuthState.Idle
                    }
                }
            }
            is TdApi.UpdateFile -> {
                _fileUpdates.tryEmit(update.file)
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                _messageUpdates.tryEmit(update)
                scope.launch {
                    TelegramRepository.loadVaultItems()
                }
            }
            is TdApi.UpdateMessageSendFailed -> {
                _messageUpdates.tryEmit(update)
            }
            is TdApi.UpdateNewMessage, is TdApi.UpdateMessageContent -> {
                _messageUpdates.tryEmit(update)
                // Auto-load when new photos, videos, or files are sent/received
                scope.launch {
                    TelegramRepository.loadVaultItems()
                }
            }
        }
    }

    fun sendTdlibParameters(context: Context) {
        val inputApiId = TdlibManager.getApiId(context)
        val inputApiHash = TdlibManager.getApiHash(context)

        if (inputApiId <= 0 || inputApiHash.isBlank()) {
            _authState.value = TelegramAuthState.WaitTdlibParameters
            return
        }

        val state = currentTdlibAuthState
        if (state != null && state !is TdApi.AuthorizationStateWaitTdlibParameters) {
            when (state) {
                is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramAuthState.WaitPhoneNumber
                is TdApi.AuthorizationStateWaitCode -> _authState.value = TelegramAuthState.WaitCode
                is TdApi.AuthorizationStateWaitPassword -> _authState.value = TelegramAuthState.WaitPassword
                is TdApi.AuthorizationStateReady -> _authState.value = TelegramAuthState.Ready
                else -> {}
            }
            return
        }

        _authState.value = TelegramAuthState.Initializing

        val parameters = TdApi.SetTdlibParameters().apply {
            apiId = inputApiId
            apiHash = inputApiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = "1.0.0"
            databaseDirectory = File(context.filesDir, "tdlib_db").absolutePath
            filesDirectory = File(context.cacheDir, "tdlib_files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useMessageDatabase = true
            useSecretChats = false
        }

        client?.send(parameters) { result ->
            if (result is TdApi.Error) {
                if (result.message.contains("Unexpected setTdlibParameters", ignoreCase = true)) {
                    Log.d(TAG, "TDLib parameters already accepted, ignoring duplicate error")
                } else {
                    Log.e(TAG, "SetTdlibParameters failed: ${result.message}")
                    _authState.value = TelegramAuthState.Error(result.message)
                }
            }
        }
    }

    fun setPhoneNumber(phone: String) {
        var cleanPhone = phone.replace(" ", "").replace("-", "").replace("(", "").replace(")", "").trim()
        if (!cleanPhone.startsWith("+") && cleanPhone.isNotBlank()) {
            cleanPhone = "+$cleanPhone"
        }
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            isCurrentPhoneNumber = false
            allowFlashCall = false
            allowMissedCall = false
            allowSmsRetrieverApi = false
        }
        client?.send(TdApi.SetAuthenticationPhoneNumber(cleanPhone, settings)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetAuthenticationPhoneNumber failed [${result.code}]: ${result.message}")
                _authState.value = TelegramAuthState.Error("Phone error [${result.code}]: ${result.message}")
            }
        }
    }

    fun resendCode() {
        client?.send(TdApi.ResendAuthenticationCode(null)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "ResendAuthenticationCode failed [${result.code}]: ${result.message}")
                _authState.value = TelegramAuthState.Error("Resend code error [${result.code}]: ${result.message}")
            }
        }
    }

    fun checkCode(code: String) {
        val cleanCode = code.trim()
        client?.send(TdApi.CheckAuthenticationCode(cleanCode)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "CheckAuthenticationCode failed [${result.code}]: ${result.message}")
                _authState.value = TelegramAuthState.Error("Code error [${result.code}]: ${result.message}")
            }
        }
    }

    fun checkPassword(password: String) {
        val cleanPassword = password.trim()
        client?.send(TdApi.CheckAuthenticationPassword(cleanPassword)) { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "CheckAuthenticationPassword failed [${result.code}]: ${result.message}")
                _authState.value = TelegramAuthState.Error("2FA error [${result.code}]: ${result.message}")
            }
        }
    }

    fun logOut() {
        client?.send(TdApi.LogOut()) { result ->
            if (result is TdApi.Ok) {
                Log.d(TAG, "Logged out successfully")
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

    suspend fun downloadFileAndWait(fileId: Int, priority: Int = 32, timeoutMs: Long = 30000L): TdApi.File? {
        if (fileId <= 0) return null
        return withContext(Dispatchers.IO) {
            try {
                // 1. Check if already downloaded
                var file: TdApi.File? = try {
                    sendRequest(TdApi.GetFile(fileId))
                } catch (e: Throwable) {
                    null
                }

                if (file != null && file.local.isDownloadingCompleted && file.local.path.isNotBlank() && File(file.local.path).exists()) {
                    return@withContext file
                }

                // If TDLib cached that the file was completed or local path exists, but it was deleted on disk (e.g. by user),
                // tell TDLib to clear its local cache entry so it can download cleanly from Telegram Cloud!
                if (file != null && (file.local.isDownloadingCompleted || file.local.path.isNotBlank()) && (file.local.path.isBlank() || !File(file.local.path).exists())) {
                    try {
                        sendRequest(TdApi.DeleteFile(fileId))
                    } catch (_: Throwable) {}
                }

                // 2. Request download
                try {
                    sendRequest(TdApi.DownloadFile(fileId, priority, 0L, 0L, false))
                } catch (e: Throwable) {
                    Log.d(TAG, "DownloadFile($fileId) note: ${e.message}")
                }

                // 3. Wait for completion reactively + gentle fallback polling
                withTimeoutOrNull(timeoutMs) {
                    file = try {
                        sendRequest(TdApi.GetFile(fileId))
                    } catch (_: Throwable) { null }

                    val current = file
                    if (current != null && current.local.isDownloadingCompleted && current.local.path.isNotBlank() && File(current.local.path).exists()) {
                        return@withTimeoutOrNull current
                    }

                    val updateJob = launch {
                        _fileUpdates.collect { updatedFile ->
                            if (updatedFile.id == fileId && updatedFile.local.isDownloadingCompleted && File(updatedFile.local.path).exists()) {
                                file = updatedFile
                            }
                        }
                    }

                    while (isActive) {
                        val check = file
                        if (check != null && check.local.isDownloadingCompleted && check.local.path.isNotBlank() && File(check.local.path).exists()) {
                            break
                        }
                        delay(600)
                        try {
                            val polled: TdApi.File = sendRequest(TdApi.GetFile(fileId))
                            file = polled
                            if (polled.local.isDownloadingCompleted && polled.local.path.isNotBlank() && File(polled.local.path).exists()) {
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                    updateJob.cancel()
                    file
                }

                val finalFile = file
                if (finalFile != null && finalFile.local.isDownloadingCompleted && finalFile.local.path.isNotBlank() && File(finalFile.local.path).exists()) {
                    finalFile
                } else {
                    null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "downloadFileAndWait failed for fileId=$fileId", e)
                null
            }
        }
    }
}
