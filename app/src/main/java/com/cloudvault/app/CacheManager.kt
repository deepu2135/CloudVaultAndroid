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
        var videoBytes = 0L
        var documentBytes = 0L
        var photoBytes = 0L
        var otherBytes = 0L

        fun classifyFile(file: File) {
            if (!file.exists() || file.isDirectory) return
            val length = file.length()
            if (length <= 0L) return

            val path = file.absolutePath.lowercase()
            val name = file.name.lowercase()

            when {
                // Videos
                path.contains("/videos/") || path.contains("/video_notes/") ||
                        name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                        name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".ts") ||
                        name.endsWith(".3gp") || name.endsWith(".wmv") || name.endsWith(".m4v") ||
                        name.endsWith(".flv") || name.endsWith(".m2ts") -> {
                    videoBytes += length
                }

                // Photos & Thumbnails
                path.contains("/photos/") || path.contains("/thumbnails/") || path.contains("/thumbs/") ||
                        path.contains("/profile_photos/") ||
                        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                        name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".bmp") ||
                        name.endsWith(".gif") -> {
                    photoBytes += length
                }

                // Documents & Audio & Archives
                path.contains("/documents/") || path.contains("/voice/") || path.contains("/music/") ||
                        name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") ||
                        name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".ppt") ||
                        name.endsWith(".pptx") || name.endsWith(".txt") || name.endsWith(".zip") ||
                        name.endsWith(".rar") || name.endsWith(".7z") || name.endsWith(".tar") ||
                        name.endsWith(".gz") || name.endsWith(".apk") || name.endsWith(".mp3") ||
                        name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".flac") ||
                        name.endsWith(".aac") || name.endsWith(".m4a") || name.endsWith(".plugin") ||
                        name.endsWith(".iso") || name.endsWith(".exe") || (name.endsWith(".bin") && !path.contains("tdlib_db")) -> {
                    documentBytes += length
                }

                // App Database, Cache, and Other internal files
                else -> {
                    otherBytes += length
                }
            }
        }

        fun scanDirectory(dir: File?) {
            if (dir == null || !dir.exists()) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    scanDirectory(f)
                } else {
                    classifyFile(f)
                }
            }
        }

        fun purgeStaleTempUploads(dir: File?) {
            if (dir == null || !dir.exists()) return
            val files = dir.listFiles() ?: return
            val now = System.currentTimeMillis()
            for (f in files) {
                if (f.isDirectory) {
                    deleteDirContents(f)
                } else if (now - f.lastModified() > 180_000L) { // older than 3 minutes
                    try { f.delete() } catch (_: Throwable) {}
                }
            }
        }

        // Clean any leftover uploads and backup temp files before measuring
        purgeStaleTempUploads(File(context.cacheDir, "uploads"))
        purgeStaleTempUploads(File(context.cacheDir, "autobackup_temp"))

        // Scan all app storage locations on device (each physical file is scanned exactly once)
        scanDirectory(context.cacheDir)
        scanDirectory(context.externalCacheDir)
        scanDirectory(context.filesDir)

        val totalCache = videoBytes + documentBytes + photoBytes + otherBytes

        // Calculate Device Storage Percentage
        var percentUsed = 0
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalDeviceBytes = stat.blockCountLong * stat.blockSizeLong
            if (totalDeviceBytes > 0L) {
                val pct = ((totalCache.toDouble() / totalDeviceBytes.toDouble()) * 100.0).toInt()
                percentUsed = pct.coerceIn(0, 100)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to calculate device storage percent", e)
        }

        CacheStats(
            videoBytes = videoBytes,
            documentBytes = documentBytes,
            photoBytes = photoBytes,
            otherBytes = otherBytes,
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

        // Delete physical files from cache based on selected categories
        fun cleanMatchingFiles(dir: File?) {
            if (dir == null || !dir.exists()) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    val dirName = file.name.lowercase()
                    if ((clearVideos && (dirName == "videos" || dirName == "video_notes")) ||
                        (clearPhotos && (dirName == "photos" || dirName == "thumbnails" || dirName == "thumbs" || dirName == "profile_photos")) ||
                        (clearDocuments && (dirName == "documents" || dirName == "voice" || dirName == "music")) ||
                        (clearOther && (dirName == "animations" || dirName == "stickers" || dirName == "temp" || dirName == "uploads" || dirName == "autobackup_temp"))
                    ) {
                        deleteDirContents(file)
                    } else {
                        cleanMatchingFiles(file)
                    }
                } else {
                    val path = file.absolutePath.lowercase()
                    val name = file.name.lowercase()
                    val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".mov") || name.endsWith(".avi")
                    val isPhoto = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
                    val isDoc = name.endsWith(".pdf") || name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".doc") || name.endsWith(".mp3")
                    
                    if ((clearVideos && isVideo) ||
                        (clearPhotos && isPhoto) ||
                        (clearDocuments && isDoc) ||
                        (clearOther && !isVideo && !isPhoto && !isDoc && !path.contains("tdlib_db"))
                    ) {
                        try { file.delete() } catch (_: Throwable) {}
                    }
                }
            }
        }

        cleanMatchingFiles(context.cacheDir)
        cleanMatchingFiles(context.externalCacheDir)

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
