package com.cloudvault.app

import android.content.Context
import android.content.SharedPreferences

object PlayerPreferences {
    private const val PREFS_NAME = "cloudvault_player_prefs"

    private const val KEY_AUTO_RESUME = "auto_resume_playback"
    private const val KEY_BUFFER_SIZE_MB = "video_buffer_size_mb"
    private const val KEY_NETWORK_CACHING_MS = "video_network_caching_ms"

    const val DEFAULT_BUFFER_SIZE_MB = 32
    const val DEFAULT_NETWORK_CACHING_MS = 2000

    val BUFFER_SIZE_OPTIONS = arrayOf(16, 32, 64, 128, 256)
    val BUFFER_SIZE_LABELS = arrayOf("16 MB (Low Data)", "32 MB (Standard)", "64 MB (High)", "128 MB (Ultra)", "256 MB (Max Cache)")

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoResumeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_RESUME, true)
    }

    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }

    fun getBufferSizeMb(context: Context): Int {
        return getPrefs(context).getInt(KEY_BUFFER_SIZE_MB, DEFAULT_BUFFER_SIZE_MB)
    }

    fun setBufferSizeMb(context: Context, sizeMb: Int) {
        getPrefs(context).edit().putInt(KEY_BUFFER_SIZE_MB, sizeMb).apply()
    }

    fun getNetworkCachingMs(context: Context): Int {
        return getPrefs(context).getInt(KEY_NETWORK_CACHING_MS, DEFAULT_NETWORK_CACHING_MS)
    }

    fun setNetworkCachingMs(context: Context, cachingMs: Int) {
        getPrefs(context).edit().putInt(KEY_NETWORK_CACHING_MS, cachingMs).apply()
    }

    fun savePlaybackPosition(context: Context, fileId: Int, positionMs: Long, durationMs: Long) {
        if (fileId <= 0) return
        val prefs = getPrefs(context)
        
        // Only save valid position if duration is known (> 15s) and position is meaningful (> 5s and < 90% of duration)
        if (durationMs > 15_000L) {
            if (positionMs < 5_000L || positionMs >= durationMs - 10_000L || (positionMs.toFloat() / durationMs.toFloat()) >= 0.90f) {
                clearPlaybackPosition(context, fileId)
                return
            }
            prefs.edit()
                .putLong("pos_$fileId", positionMs)
                .putLong("dur_$fileId", durationMs)
                .putLong("time_$fileId", System.currentTimeMillis())
                .apply()
        } else {
            // For files without known duration or very short clips, do not save arbitrary offsets
            clearPlaybackPosition(context, fileId)
        }
    }

    fun getSavedPlaybackPosition(context: Context, fileId: Int, durationMs: Long = 0L): Long {
        if (fileId <= 0 || !isAutoResumeEnabled(context)) return 0L
        val prefs = getPrefs(context)
        val pos = prefs.getLong("pos_$fileId", 0L)
        val savedDur = prefs.getLong("dur_$fileId", 0L)
        val effectiveDur = if (durationMs > 0L) durationMs else savedDur

        // Discard invalid / legacy positions (less than 5s, greater than 24h, or duration unknown)
        if (pos < 5000L || pos > 86_400_000L) {
            clearPlaybackPosition(context, fileId)
            return 0L
        }
        if (effectiveDur <= 0L) {
            return 0L
        }
        if (pos >= effectiveDur - 10000L || (pos.toFloat() / effectiveDur.toFloat()) >= 0.90f) {
            clearPlaybackPosition(context, fileId)
            return 0L
        }
        return pos
    }

    fun clearPlaybackPosition(context: Context, fileId: Int) {
        if (fileId <= 0) return
        getPrefs(context).edit()
            .remove("pos_$fileId")
            .remove("dur_$fileId")
            .remove("time_$fileId")
            .apply()
    }
}
