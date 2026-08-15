package com.cloudvault.app

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

object CacheManager {

    private const val TAG = "CacheManager"
    private const val PREFS_NAME = "cloudvault_cache_prefs"
    private const val KEY_KEEP_MEDIA_DAYS = "keep_media_days"
    private const val KEY_MAX_CACHE_MB = "max_cache_mb"

    data class CacheStats(
        val videoBytes: Long,
        val documentBytes: Long,
        val photoBytes: Long,
        val otherBytes: Long,
        val totalBytes: Long,
        val deviceUsagePercent: Int
    )

    fun getKeepMediaDays(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_KEEP_MEDIA_DAYS, -1) // -1 means Forever
    }

    fun setKeepMediaDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_KEEP_MEDIA_DAYS, days).apply()
    }

    fun getMaxCacheSizeMb(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_MAX_CACHE_MB, 0L) // 0 means No Limit
    }

    fun setMaxCacheSizeMb(context: Context, mb: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_MAX_CACHE_MB, mb).apply()
    }

    suspend fun calculateCacheStats(context: Context): CacheStats = withContext(Dispatchers.IO) {
        var tdVideos = 0L
        var tdDocs = 0L
        var tdPhotos = 0L
        var tdOthers = 0L

        // 1. Fetch TDLib storage statistics
        try {
            val tdStats = TelegramClient.sendRequest(TdApi.GetStorageStatistics(100)) as? TdApi.StorageStatistics
            if (tdStats != null && tdStats.byChat != null) {
                for (chatStats in tdStats.byChat) {
                    if (chatStats.byFileType == null) continue
                    for (fileTypeStats in chatStats.byFileType) {
                        val size = fileTypeStats.size
                        when (fileTypeStats.fileType) {
                            is TdApi.FileTypeVideo,
                            is TdApi.FileTypeVideoNote,
                            is TdApi.FileTypeVideoStory,
                            is TdApi.FileTypeLivePhotoVideo,
                            is TdApi.FileTypeSelfDestructingVideo,
                            is TdApi.FileTypeSelfDestructingVideoNote -> tdVideos += size

                            is TdApi.FileTypeDocument,
                            is TdApi.FileTypeAudio,
                            is TdApi.FileTypeVoiceNote,
                            is TdApi.FileTypeSelfDestructingVoiceNote -> tdDocs += size

                            is TdApi.FileTypePhoto,
                            is TdApi.FileTypePhotoStory,
                            is TdApi.FileTypeThumbnail,
                            is TdApi.FileTypeProfilePhoto,
                            is TdApi.FileTypeSelfDestructingPhoto,
                            is TdApi.FileTypeSecretThumbnail -> tdPhotos += size

                            else -> tdOthers += size
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get TDLib storage statistics", e)
        }

        // 2. Scan App Cache Directory for thumbnails, temp streams & proxy files
        val localCacheSize = getFolderSize(context.cacheDir) + getFolderSize(context.externalCacheDir)
        tdOthers += localCacheSize

        // 3. Scan TDLib directory if TDLib didn't report everything
        val filesDir = context.filesDir
        val tdlibDir = File(filesDir, "tdlib")
        if (tdlibDir.exists() && (tdVideos + tdDocs + tdPhotos + tdOthers) == 0L) {
            tdOthers += getFolderSize(tdlibDir)
        }

        val totalCache = tdVideos + tdDocs + tdPhotos + tdOthers

        // 4. Calculate Device Storage Percentage
        var percentUsed = 0
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalDeviceBytes = stat.blockCountLong * stat.blockSizeLong
            if (totalDeviceBytes > 0L) {
                percentUsed = ((totalCache.toDouble() / totalDeviceBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to calculate device storage percent", e)
        }

        CacheStats(
            videoBytes = tdVideos,
            documentBytes = tdDocs,
            photoBytes = tdPhotos,
            otherBytes = tdOthers,
            totalBytes = totalCache,
            deviceUsagePercent = percentUsed
        )
    }

    suspend fun clearSelectedCache(
        context: Context,
        clearVideos: Boolean,
        clearDocuments: Boolean,
        clearPhotos: Boolean,
        clearOther: Boolean
    ): CacheStats = withContext(Dispatchers.IO) {
        val selectedTypes = mutableListOf<TdApi.FileType>()

        if (clearVideos) {
            selectedTypes.addAll(
                listOf(
                    TdApi.FileTypeVideo(),
                    TdApi.FileTypeVideoNote(),
                    TdApi.FileTypeVideoStory(),
                    TdApi.FileTypeLivePhotoVideo(),
                    TdApi.FileTypeSelfDestructingVideo(),
                    TdApi.FileTypeSelfDestructingVideoNote()
                )
            )
        }

        if (clearDocuments) {
            selectedTypes.addAll(
                listOf(
                    TdApi.FileTypeDocument(),
                    TdApi.FileTypeAudio(),
                    TdApi.FileTypeVoiceNote(),
                    TdApi.FileTypeSelfDestructingVoiceNote()
                )
            )
        }

        if (clearPhotos) {
            selectedTypes.addAll(
                listOf(
                    TdApi.FileTypePhoto(),
                    TdApi.FileTypePhotoStory(),
                    TdApi.FileTypeThumbnail(),
                    TdApi.FileTypeProfilePhoto(),
                    TdApi.FileTypeSelfDestructingPhoto(),
                    TdApi.FileTypeSecretThumbnail()
                )
            )
        }

        if (clearOther) {
            selectedTypes.addAll(
                listOf(
                    TdApi.FileTypeSticker(),
                    TdApi.FileTypeAnimation(),
                    TdApi.FileTypeWallpaper(),
                    TdApi.FileTypeNotificationSound(),
                    TdApi.FileTypeNone(),
                    TdApi.FileTypeUnknown()
                )
            )
        }

        if (selectedTypes.isNotEmpty()) {
            try {
                TelegramClient.sendRequest(
                    TdApi.OptimizeStorage(
                        0L,
                        -1,
                        -1,
                        -1,
                        selectedTypes.toTypedArray(),
                        LongArray(0),
                        LongArray(0),
                        false,
                        100
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "TDLib OptimizeStorage call failed", e)
            }
        }

        // Clean local cache folders if other is checked
        if (clearOther || (clearVideos && clearDocuments && clearPhotos)) {
            try {
                deleteDirContents(context.cacheDir)
                context.externalCacheDir?.let { deleteDirContents(it) }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed cleaning local cache dir", e)
            }
        }

        // Recalculate stats
        calculateCacheStats(context)
    }

    private fun getFolderSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    private fun deleteDirContents(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return true
        val files = dir.listFiles() ?: return true
        var success = true
        for (file in files) {
            if (file.isDirectory) {
                success = success && deleteDirContents(file)
            }
            success = success && file.delete()
        }
        return success
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val unit = 1024.0
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit)).toInt().coerceIn(0, 4)
        val pre = "KMGT"[maxOf(0, exp - 1)]
        return if (exp == 0) "$bytes B" else String.format(java.util.Locale.US, "%.2f %cB", bytes / Math.pow(unit, exp.toDouble()), pre)
    }
}
