package com.cloudvault.app

sealed class TelegramAuthState {
    object Idle : TelegramAuthState()
    object Initializing : TelegramAuthState()
    object WaitTdlibParameters : TelegramAuthState()
    object WaitPhoneNumber : TelegramAuthState()
    object WaitCode : TelegramAuthState()
    object WaitPassword : TelegramAuthState()
    object Ready : TelegramAuthState()
    data class Error(val message: String) : TelegramAuthState()
}
