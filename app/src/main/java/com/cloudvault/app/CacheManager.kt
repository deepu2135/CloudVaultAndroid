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

    enum class FileCategory {
        VIDEO,
        DOCUMENT,
        PHOTO,
        OTHER
    }

    private fun readHeaderBytes(file: File): ByteArray? {
        return try {
            java.io.FileInputStream(file).use { input ->
                val buffer = ByteArray(16)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectCategoryFromHeader(bytes: ByteArray): FileCategory? {
        if (bytes.size < 4) return null

        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return FileCategory.PHOTO
        }
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return FileCategory.PHOTO
        }
        // GIF: 47 49 46 ('GIF')
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
            return FileCategory.PHOTO
        }
        // BMP: 42 4D ('BM')
        if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) {
            return FileCategory.PHOTO
        }
        // WebP / AVI / WAV: RIFF ...
        if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) {
            val isWebp = bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
            val isAvi = bytes[8] == 0x41.toByte() && bytes[9] == 0x56.toByte() && bytes[10] == 0x49.toByte()
            val isWave = bytes[8] == 0x57.toByte() && bytes[9] == 0x41.toByte() && bytes[10] == 0x56.toByte() && bytes[11] == 0x45.toByte()
            if (isWebp) return FileCategory.PHOTO
            if (isAvi) return FileCategory.VIDEO
            if (isWave) return FileCategory.DOCUMENT
        }
        // Matroska / WebM: 1A 45 DF A3
        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) {
            return FileCategory.VIDEO
        }
        // MP4 / MOV / HEIF: bytes 4..7 == 'ftyp'
        if (bytes.size >= 8 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) {
            if (bytes.size >= 12) {
                val brand = String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).lowercase()
                if (brand.startsWith("heic") || brand.startsWith("mif1") || brand.startsWith("msf1") || brand.startsWith("hevc")) {
                    return FileCategory.PHOTO
                }
            }
            return FileCategory.VIDEO
        }
        // PDF: %PDF (25 50 44 46)
        if (bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) {
            return FileCategory.DOCUMENT
        }
        // ZIP / APK / Office XML: PK.. (50 4B 03 04)
        if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) {
            return FileCategory.DOCUMENT
        }
        // ID3 (MP3): 49 44 33
        if (bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) {
            return FileCategory.DOCUMENT
        }
        // OGG: OggS (4F 67 67 53)
        if (bytes[0] == 0x4F.toByte() && bytes[1] == 0x67.toByte() && bytes[2] == 0x67.toByte() && bytes[3] == 0x53.toByte()) {
            return FileCategory.DOCUMENT
        }
        // FLAC: fLaC (66 4C 61 43)
        if (bytes[0] == 0x66.toByte() && bytes[1] == 0x4C.toByte() && bytes[2] == 0x61.toByte() && bytes[3] == 0x43.toByte()) {
            return FileCategory.DOCUMENT
        }

        return null
    }

    private fun detectFileCategory(file: File): FileCategory {
        val path = file.absolutePath.lowercase()
        val name = file.name.lowercase()

        // 1. Folder path based classification (highest confidence from TDLib directory structure)
        if (path.contains("/photos/") || path.contains("/thumbnails/") || path.contains("/thumbs/") ||
            path.contains("/profile_photos/") || path.contains("/wallpapers/") ||
            path.contains("/autobackup_compressed/")
        ) {
            return FileCategory.PHOTO
        }

        if (path.contains("/videos/") || path.contains("/video_notes/") ||
            path.contains("/video_stories/") || path.contains("/live_photos/")
        ) {
            return FileCategory.VIDEO
        }

        if (path.contains("/documents/") || path.contains("/voice/") || path.contains("/music/") ||
            path.contains("/audios/")
        ) {
            return FileCategory.DOCUMENT
        }

        // 2. Extension based classification
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".heif") ||
            name.endsWith(".bmp") || name.endsWith(".gif") || name.endsWith(".svg") ||
            name.endsWith(".ico") || name.endsWith(".jfif") || name.endsWith(".tif") ||
            name.endsWith(".tiff") || name.endsWith(".raw") || name.endsWith(".dng") ||
            name.startsWith("thumb_") || name.startsWith("photo_") || name.startsWith("img_") ||
            name.startsWith("opt_") || name.startsWith("avatar_")
        ) {
            return FileCategory.PHOTO
        }

        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
            name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".ts") ||
            name.endsWith(".3gp") || name.endsWith(".wmv") || name.endsWith(".m4v") ||
            name.endsWith(".flv") || name.endsWith(".m2ts") || name.startsWith("vid_") ||
            name.startsWith("video_")
        ) {
            return FileCategory.VIDEO
        }

        if (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") ||
            name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".ppt") ||
            name.endsWith(".pptx") || name.endsWith(".txt") || name.endsWith(".rtf") ||
            name.endsWith(".csv") || name.endsWith(".zip") || name.endsWith(".rar") ||
            name.endsWith(".7z") || name.endsWith(".tar") || name.endsWith(".gz") ||
            name.endsWith(".bz2") || name.endsWith(".xz") || name.endsWith(".apk") ||
            name.endsWith(".xapk") || name.endsWith(".apkm") || name.endsWith(".mp3") ||
            name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".flac") ||
            name.endsWith(".aac") || name.endsWith(".m4a") || name.endsWith(".wma") ||
            name.endsWith(".amr") || name.endsWith(".opus") || name.endsWith(".plugin") ||
            name.endsWith(".iso") || name.endsWith(".exe") || name.endsWith(".dmg")
        ) {
            return FileCategory.DOCUMENT
        }

        // 3. Magic Header Sniffing for extensionless or temp files (e.g. in tdlib_files/temp/ or uploads/)
        if (file.length() >= 4) {
            val header = readHeaderBytes(file)
            if (header != null && header.isNotEmpty()) {
                val magicCat = detectCategoryFromHeader(header)
                if (magicCat != null) return magicCat
            }
        }

        return FileCategory.OTHER
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

            // Never include permanent TDLib auth/chat database in clearable cache calculations
            if (file.absolutePath.contains("tdlib_db")) return

            when (detectFileCategory(file)) {
                FileCategory.VIDEO -> videoBytes += length
                FileCategory.DOCUMENT -> documentBytes += length
                FileCategory.PHOTO -> photoBytes += length
                FileCategory.OTHER -> otherBytes += length
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
        purgeStaleTempUploads(File(context.cacheDir, "autobackup_compressed"))

        // Scan all clearable app cache locations on device (excluding permanent database)
        scanDirectory(context.cacheDir)
        scanDirectory(context.externalCacheDir)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            scanDirectory(context.codeCacheDir)
        }

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
                    if ((clearVideos && (dirName == "videos" || dirName == "video_notes" || dirName == "video_stories")) ||
                        (clearPhotos && (dirName == "photos" || dirName == "thumbnails" || dirName == "thumbs" || dirName == "profile_photos" || dirName == "wallpapers" || dirName == "autobackup_compressed")) ||
                        (clearDocuments && (dirName == "documents" || dirName == "voice" || dirName == "music" || dirName == "audios")) ||
                        (clearOther && (dirName == "animations" || dirName == "stickers" || dirName == "temp" || dirName == "uploads" || dirName == "autobackup_temp"))
                    ) {
                        deleteDirContents(file)
                    } else {
                        cleanMatchingFiles(file)
                    }
                } else {
                    if (file.absolutePath.contains("tdlib_db")) return
                    val category = detectFileCategory(file)
                    val shouldDelete = when (category) {
                        FileCategory.VIDEO -> clearVideos
                        FileCategory.DOCUMENT -> clearDocuments
                        FileCategory.PHOTO -> clearPhotos
                        FileCategory.OTHER -> clearOther
                    }
                    if (shouldDelete) {
                        try { file.delete() } catch (_: Throwable) {}
                    }
                }
            }
        }

        cleanMatchingFiles(context.cacheDir)
        cleanMatchingFiles(context.externalCacheDir)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cleanMatchingFiles(context.codeCacheDir)
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
