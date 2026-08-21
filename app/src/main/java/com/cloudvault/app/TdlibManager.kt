package com.cloudvault.app

import android.content.Context
import android.content.SharedPreferences

data class VaultMediaItem(
    val id: String,
    val title: String,
    val caption: String = "",
    val sizeBytes: Long,
    val formattedSize: String,
    val mimeType: String,
    val type: MediaType,
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val thumbnailFileId: Int = 0,
    val dateAdded: Long,
    val durationSeconds: Int = 0
)

enum class MediaType {
    PHOTO, VIDEO, AUDIO, DOCUMENT
}

object TdlibManager {
    private const val PREFS_NAME = "cloudvault_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiId(context: Context): Int {
        return getPrefs(context).getInt("api_id", 0)
    }

    fun saveApiId(context: Context, apiId: Int) {
        getPrefs(context).edit().putInt("api_id", apiId).apply()
    }

    fun getApiHash(context: Context): String {
        return getPrefs(context).getString("api_hash", "") ?: ""
    }

    fun saveApiHash(context: Context, apiHash: String) {
        getPrefs(context).edit().putString("api_hash", apiHash.trim()).apply()
    }

    fun isCredentialsConfigured(context: Context): Boolean {
        return getApiId(context) > 0 && getApiHash(context).isNotBlank()
    }

    fun getUserPhone(context: Context): String {
        return getPrefs(context).getString("user_phone", "") ?: ""
    }

    fun setSessionActive(context: Context, active: Boolean, phone: String = "") {
        getPrefs(context).edit()
            .putBoolean("session_active", active)
            .putString("user_phone", phone)
            .apply()
    }

    fun isSessionActive(context: Context): Boolean {
        return getPrefs(context).getBoolean("session_active", false)
    }

    fun getStorageChannel(context: Context): String {
        return getPrefs(context).getString("storage_channel", "Saved Messages") ?: "Saved Messages"
    }

    fun setStorageChannel(context: Context, channel: String) {
        getPrefs(context).edit().putString("storage_channel", channel.trim()).apply()
    }
}
