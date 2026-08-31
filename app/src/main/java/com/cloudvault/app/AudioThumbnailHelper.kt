package com.cloudvault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AudioThumbnailHelper {

    suspend fun getThumbnailBitmap(item: VaultMediaItem): Bitmap? = withContext(Dispatchers.IO) {
        // 1. Check in-memory cache first
        val targetId = if (item.thumbnailFileId > 0) item.thumbnailFileId else item.fileId
        if (targetId > 0) {
            val cached = MediaGridAdapter.bitmapCache.get(targetId)
                ?: (if (item.fileId > 0) MediaGridAdapter.bitmapCache.get(item.fileId) else null)
            if (cached != null) return@withContext cached
        }

        // 2. Try TDLib Telegram thumbnail if available
        if (item.thumbnailFileId > 0) {
            try {
                val tdFile = TelegramClient.downloadFileAndWait(item.thumbnailFileId, priority = 32, timeoutMs = 6000L)
                if (tdFile != null && tdFile.local.path.isNotBlank() && File(tdFile.local.path).exists()) {
                    val decoded = ImageUtils.decodeOrientedBitmap(tdFile.local.path, maxDimension = 512)
                    if (decoded != null) {
                        MediaGridAdapter.bitmapCache.put(item.thumbnailFileId, decoded)
                        if (item.fileId > 0) MediaGridAdapter.bitmapCache.put(item.fileId, decoded)
                        return@withContext decoded
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 3. Fallback: Extract embedded ID3 album art cover using MediaMetadataRetriever from streaming proxy
        if (item.fileId > 0) {
            val retriever = MediaMetadataRetriever()
            try {
                val streamUrl = TelegramStreamingProxy.getUrl(item.fileId, item.title, item.sizeBytes, item.chatId, item.messageId)
                retriever.setDataSource(streamUrl, HashMap())
                val pictureBytes = retriever.embeddedPicture
                if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size)
                    if (bmp != null) {
                        MediaGridAdapter.bitmapCache.put(item.fileId, bmp)
                        if (item.thumbnailFileId > 0) MediaGridAdapter.bitmapCache.put(item.thumbnailFileId, bmp)
                        return@withContext bmp
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { retriever.release() }
            }
        }

        null
    }
}
