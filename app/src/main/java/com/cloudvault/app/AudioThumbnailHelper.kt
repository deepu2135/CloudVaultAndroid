package com.cloudvault.app

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AudioThumbnailHelper {

    suspend fun getThumbnailBitmap(item: VaultMediaItem): Bitmap? = withContext(Dispatchers.IO) {
        val targetId = if (item.thumbnailFileId > 0) item.thumbnailFileId else 0
        if (targetId <= 0) return@withContext null

        val cached = MediaGridAdapter.bitmapCache.get(targetId)
        if (cached != null) return@withContext cached

        try {
            val tdFile = TelegramClient.downloadFileAndWait(targetId, priority = 32, timeoutMs = 6000L)
            if (tdFile != null && tdFile.local.path.isNotBlank() && File(tdFile.local.path).exists()) {
                val decoded = ImageUtils.decodeOrientedBitmap(tdFile.local.path, maxDimension = 512)
                if (decoded != null) {
                    MediaGridAdapter.bitmapCache.put(targetId, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Throwable) {
        }
        null
    }
}
