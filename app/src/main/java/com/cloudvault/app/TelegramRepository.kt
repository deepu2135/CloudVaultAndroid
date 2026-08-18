package com.cloudvault.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream

object TelegramRepository {
    private const val TAG = "TelegramRepository"

    private val _photos = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val photos: StateFlow<List<VaultMediaItem>> = _photos.asStateFlow()

    private val _videos = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val videos: StateFlow<List<VaultMediaItem>> = _videos.asStateFlow()

    private val _files = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val files: StateFlow<List<VaultMediaItem>> = _files.asStateFlow()

    private val _isLoadingVault = MutableStateFlow(false)
    val isLoadingVault: StateFlow<Boolean> = _isLoadingVault.asStateFlow()

    fun isZipArchiveFilename(name: String): Boolean {
        return name.endsWith(".zip", ignoreCase = true) ||
                name.endsWith(".7z", ignoreCase = true) ||
                name.endsWith(".rar", ignoreCase = true) ||
                name.endsWith(".tar", ignoreCase = true) ||
                name.endsWith(".gz", ignoreCase = true)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    suspend fun loadVaultItems(chatId: Long = 0L) {
        val targetChatId = if (chatId != 0L) chatId else getSavedMessagesChatId() ?: return
        _isLoadingVault.value = true

        try {
            val photoList = mutableListOf<VaultMediaItem>()
            val videoList = mutableListOf<VaultMediaItem>()
            val fileList = mutableListOf<VaultMediaItem>()
            val seenMessageIds = mutableSetOf<Long>()

            var fromMessageId = 0L
            var consecutiveEmptyBatches = 0
            val batchSize = 100
            val maxMessagesToScan = 20000

            var scannedCount = 0
            while (scannedCount < maxMessagesToScan) {
                val history = try {
                    TelegramClient.sendRequest(
                        TdApi.GetChatHistory(targetChatId, fromMessageId, 0, batchSize, false)
                    ) as? TdApi.Messages
                } catch (e: Throwable) {
                    Log.w(TAG, "GetChatHistory error at fromMessageId=$fromMessageId", e)
                    null
                }

                if (history == null || history.messages.isEmpty()) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches >= 1) break
                } else {
                    consecutiveEmptyBatches = 0
                    for (msg in history.messages) {
                        if (seenMessageIds.add(msg.id)) {
                            parseAndClassifyMessage(msg, photoList, videoList, fileList)
                        }
                    }
                    scannedCount += history.messages.size
                    fromMessageId = history.messages.last().id

                    if (history.messages.size < batchSize) {
                        break
                    }
                }
            }

            _photos.value = photoList.sortedByDescending { it.dateAdded }
            _videos.value = videoList.sortedByDescending { it.dateAdded }
            _files.value = fileList.sortedByDescending { it.dateAdded }

            Log.d(TAG, "Vault loaded: ${photoList.size} photos, ${videoList.size} videos, ${fileList.size} files (scanned $scannedCount messages)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vault items", e)
        } finally {
            _isLoadingVault.value = false
        }
    }

    private fun parseAndClassifyMessage(
        msg: TdApi.Message,
        photoList: MutableList<VaultMediaItem>,
        videoList: MutableList<VaultMediaItem>,
        fileList: MutableList<VaultMediaItem>
    ) {
        if (msg.sendingState is TdApi.MessageSendingStateFailed) {
            return
        }
        when (val content = msg.content) {
            is TdApi.MessagePhoto -> {
                val sizes = content.photo.sizes
                if (sizes.isEmpty()) return

                // Full photo: highest resolution size
                val fullPhoto = sizes.maxByOrNull {
                    if (it.photo.size > 0) it.photo.size.toLong() else (it.width.toLong() * it.height)
                } ?: sizes.last()

                // Thumbnail photo: prefer medium thumbnail for fast grid display
                val thumbPhoto = sizes.find { it.type == "m" }
                    ?: sizes.find { it.type == "s" }
                    ?: sizes.find { it.type == "x" }
                    ?: sizes.filter { it.photo.id != fullPhoto.photo.id }.minByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                    ?: fullPhoto

                val photoSize = if (fullPhoto.photo.size > 0) fullPhoto.photo.size.toLong() else fullPhoto.photo.expectedSize.toLong()
                val caption = content.caption?.text.orEmpty().trim()
                val photoTitle = if (caption.isNotBlank()) caption else "Photo_${msg.date}.jpg"

                photoList.add(
                    VaultMediaItem(
                        id = "photo_${msg.id}",
                        title = photoTitle,
                        caption = caption,
                        sizeBytes = photoSize,
                        formattedSize = formatSize(photoSize),
                        mimeType = "image/jpeg",
                        type = MediaType.PHOTO,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        fileId = fullPhoto.photo.id,
                        thumbnailFileId = thumbPhoto.photo.id,
                        dateAdded = msg.date.toLong()
                    )
                )
            }
            is TdApi.MessageVideo -> {
                val caption = content.caption?.text.orEmpty().trim()
                val videoTitle = if (content.video.fileName.isNotBlank()) {
                    content.video.fileName
                } else if (caption.isNotBlank()) {
                    caption
                } else {
                    "Video_${msg.date}.mp4"
                }

                videoList.add(
                    VaultMediaItem(
                        id = "video_${msg.id}",
                        title = videoTitle,
                        caption = caption,
                        sizeBytes = content.video.video.size.toLong(),
                        formattedSize = formatSize(content.video.video.size.toLong()),
                        mimeType = content.video.mimeType.ifBlank { "video/mp4" },
                        type = MediaType.VIDEO,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        fileId = content.video.video.id,
                        thumbnailFileId = content.video.thumbnail?.file?.id ?: 0,
                        dateAdded = msg.date.toLong(),
                        durationSeconds = content.video.duration
                    )
                )
            }
            is TdApi.MessageDocument -> {
                val doc = content.document
                val mime = doc.mimeType.ifBlank { "application/octet-stream" }.lowercase()
                val name = doc.fileName.lowercase()
                val caption = content.caption?.text.orEmpty().trim()

                val isVideo = mime.startsWith("video/") ||
                        mime.contains("matroska") ||
                        mime.contains("mp4") ||
                        mime.contains("webm") ||
                        name.endsWith(".mkv") ||
                        name.endsWith(".mp4") ||
                        name.endsWith(".webm") ||
                        name.endsWith(".avi") ||
                        name.endsWith(".mov") ||
                        name.endsWith(".flv") ||
                        name.endsWith(".ts") ||
                        name.endsWith(".m4v") ||
                        name.endsWith(".wmv") ||
                        name.endsWith(".3gp") ||
                        name.endsWith(".m2ts")

                val isPhoto = !isVideo && (
                        mime.startsWith("image/") ||
                        name.endsWith(".jpg") ||
                        name.endsWith(".jpeg") ||
                        name.endsWith(".png") ||
                        name.endsWith(".webp") ||
                        name.endsWith(".heic") ||
                        name.endsWith(".bmp") ||
                        name.endsWith(".gif")
                )

                val itemType = when {
                    isVideo -> MediaType.VIDEO
                    isPhoto -> MediaType.PHOTO
                    else -> MediaType.DOCUMENT
                }

                val docTitle = if (doc.fileName.isNotBlank()) {
                    doc.fileName
                } else if (caption.isNotBlank()) {
                    caption
                } else {
                    "File_${msg.date}"
                }

                val item = VaultMediaItem(
                    id = "doc_${msg.id}",
                    title = docTitle,
                    caption = caption,
                    sizeBytes = doc.document.size.toLong(),
                    formattedSize = formatSize(doc.document.size.toLong()),
                    mimeType = doc.mimeType.ifBlank { "application/octet-stream" },
                    type = itemType,
                    chatId = msg.chatId,
                    messageId = msg.id,
                    fileId = doc.document.id,
                    thumbnailFileId = doc.thumbnail?.file?.id ?: 0,
                    dateAdded = msg.date.toLong()
                )

                when (itemType) {
                    MediaType.VIDEO -> videoList.add(item)
                    MediaType.PHOTO -> photoList.add(item)
                    MediaType.DOCUMENT -> fileList.add(item)
                }
            }
        }
    }

    suspend fun uploadFile(
        localPath: String,
        mediaType: MediaType,
        captionText: String = "",
        targetChatId: Long = 0L,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(localPath)
        if (!targetFile.exists() || targetFile.length() <= 0L) {
            Log.e(TAG, "uploadFile failed: file does not exist or is empty at $localPath")
            return@withContext false
        }
        val totalFileSize = targetFile.length()

        val chatId = if (targetChatId != 0L) targetChatId else getSavedMessagesChatId() ?: return@withContext false
        val inputFile = TdApi.InputFileLocal(localPath)
        val formattedCaption = TdApi.FormattedText(captionText, emptyArray())

        var actualMediaType = mediaType
        var photoWidth = 0
        var photoHeight = 0
        
        if (actualMediaType == MediaType.PHOTO) {
            try {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(localPath, options)
                photoWidth = options.outWidth
                photoHeight = options.outHeight
                
                if (photoWidth <= 0 || photoHeight <= 0 || totalFileSize > 10485760L || photoWidth + photoHeight > 10000 || photoWidth > 10000 || photoHeight > 10000) {
                    actualMediaType = MediaType.DOCUMENT
                    TeleflixLogger.log(TAG, "Photo exceeds limits or invalid (size: $totalFileSize, dim: ${photoWidth}x${photoHeight}), sending as document")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not decode photo bounds", e)
                actualMediaType = MediaType.DOCUMENT
            }
        }

        val inputContent: TdApi.InputMessageContent = when (val finalMediaType = actualMediaType) {
            MediaType.PHOTO -> TdApi.InputMessagePhoto().apply {
                val inputPhoto = TdApi.InputPhoto().apply {
                    photo = inputFile
                    width = photoWidth
                    height = photoHeight
                }
                photo = inputPhoto
                caption = formattedCaption
            }
            MediaType.VIDEO -> TdApi.InputMessageVideo().apply {
                val inputVideo = TdApi.InputVideo().apply {
                    video = inputFile
                    supportsStreaming = true

                    // Extract thumbnail and metadata from video
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(localPath)
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val widthStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val heightStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

                        duration = (durationStr?.toLongOrNull() ?: 0L).let { (it / 1000).toInt() }
                        width = widthStr?.toIntOrNull() ?: 0
                        height = heightStr?.toIntOrNull() ?: 0

                        val frame = retriever.getFrameAtTime(1000000) ?: retriever.frameAtTime
                        if (frame != null) {
                            val thumbDir = File(targetFile.parentFile ?: File(localPath).parentFile, "thumbs").apply { if (!exists()) mkdirs() }
                            val thumbFile = File(thumbDir, "thumb_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(thumbFile).use { out ->
                                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            thumbnail = TdApi.InputThumbnail(TdApi.InputFileLocal(thumbFile.absolutePath), frame.width, frame.height)
                        }
                        retriever.release()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to extract video thumbnail", e)
                    }
                }
                video = inputVideo
                caption = formattedCaption
            }
            MediaType.DOCUMENT -> TdApi.InputMessageDocument().apply {
                document = TdApi.InputDocument().apply { document = inputFile }
                caption = formattedCaption
            }
        }

        try {
            val sendMsg = TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = inputContent
            }
            val sentMsg = TelegramClient.sendRequest(sendMsg) as? TdApi.Message
                ?: throw IllegalStateException("SendMessage returned null")

            val tempMsgId = sentMsg.id
            TeleflixLogger.log(TAG, "uploadFile dispatched SendMessage tempMsgId=$tempMsgId, waiting for TDLib upload...")

            if (sentMsg.sendingState == null) {
                // Immediate success (e.g. instantly cached)
                onProgress?.invoke(totalFileSize, totalFileSize)
                loadVaultItems(chatId)
                return@withContext true
            }

            if (sentMsg.sendingState is TdApi.MessageSendingStateFailed) {
                TeleflixLogger.log(TAG, "uploadFile: message failed immediately", isError = true)
                return@withContext false
            }

            val uploadingFileId: Int = when (val c = sentMsg.content) {
                is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.photo.size }?.photo?.id
                    ?: (c.photo.sizes.lastOrNull()?.photo?.id ?: 0)
                is TdApi.MessageVideo -> c.video.video.id
                is TdApi.MessageDocument -> c.document.document.id
                else -> 0
            }

            val timeoutMs = ((totalFileSize / 25_000L) * 1000L).coerceIn(180_000L, 3_600_000L)
            val uploadSuccess = withTimeoutOrNull(timeoutMs) {
                var isDone = false
                var isError = false

                val fileCollector = launch {
                    TelegramClient.fileUpdates.collect { file ->
                        if (file.id == uploadingFileId) {
                            val uploaded = file.remote.uploadedSize
                            val total = if (file.size > 0) file.size else (if (file.expectedSize > 0) file.expectedSize else totalFileSize)
                            onProgress?.invoke(uploaded, total)
                            if (file.remote.isUploadingCompleted) {
                                isDone = true
                            }
                        }
                    }
                }

                val msgCollector = launch {
                    TelegramClient.messageUpdates.collect { update ->
                        when (update) {
                            is TdApi.UpdateMessageSendSucceeded -> {
                                if (update.oldMessageId == tempMsgId || update.message.id == tempMsgId) {
                                    TeleflixLogger.log(TAG, "uploadFile: received UpdateMessageSendSucceeded for tempMsgId=$tempMsgId")
                                    isDone = true
                                }
                            }
                            is TdApi.UpdateMessageSendFailed -> {
                                if (update.oldMessageId == tempMsgId || update.message.id == tempMsgId) {
                                    val errCode = update.error?.code ?: 0
                                    val errMsg = update.error?.message ?: "unknown"
                                    TeleflixLogger.log(TAG, "uploadFile: received UpdateMessageSendFailed [$errCode]: $errMsg", isError = true)
                                    isError = true
                                }
                            }
                        }
                    }
                }

                try {
                    while (isActive && !isDone && !isError) {
                        delay(1000L)

                        // Fallback polling check
                        try {
                            if (uploadingFileId > 0) {
                                val checkFile = TelegramClient.sendRequest(TdApi.GetFile(uploadingFileId)) as? TdApi.File
                                if (checkFile != null) {
                                    val uploaded = checkFile.remote.uploadedSize
                                    val total = if (checkFile.size > 0) checkFile.size else (if (checkFile.expectedSize > 0) checkFile.expectedSize else totalFileSize)
                                    onProgress?.invoke(uploaded, total)
                                    if (checkFile.remote.isUploadingCompleted) {
                                        isDone = true
                                        break
                                    }
                                }
                            }

                            val checkMsg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, tempMsgId)) as? TdApi.Message
                            if (checkMsg != null) {
                                if (checkMsg.sendingState == null) {
                                    isDone = true
                                    break
                                } else if (checkMsg.sendingState is TdApi.MessageSendingStateFailed) {
                                    isError = true
                                    break
                                }
                            }
                        } catch (_: Throwable) {
                            // If tempMsgId is no longer found, it might have been replaced with server id
                        }
                    }
                } finally {
                    fileCollector.cancel()
                    msgCollector.cancel()
                }

                isDone && !isError
            } ?: false

            if (uploadSuccess) {
                onProgress?.invoke(totalFileSize, totalFileSize)
                loadVaultItems(chatId)
                TeleflixLogger.log(TAG, "uploadFile completed successfully to Telegram Cloud! tempMsgId=$tempMsgId")
                true
            } else {
                TeleflixLogger.log(TAG, "uploadFile failed or timed out for $localPath", isError = true)
                false
            }
        } catch (e: Exception) {
            TeleflixLogger.log(TAG, "Failed to upload file to Telegram cloud: ${e.message}", isError = true)
            false
        }
    }

    suspend fun deleteMediaItems(items: List<VaultMediaItem>): Boolean {
        if (items.isEmpty()) return true
        return try {
            val byChat = items.groupBy { it.chatId }
            for ((chatId, chatItems) in byChat) {
                val targetChatId = if (chatId != 0L) chatId else getSavedMessagesChatId() ?: continue
                val msgIds = chatItems.map { it.messageId }.filter { it != 0L }.toLongArray()
                if (msgIds.isNotEmpty()) {
                    TelegramClient.sendRequest(TdApi.DeleteMessages(targetChatId, msgIds, true))
                }
            }

            // Immediately update reactive StateFlows in memory
            val deletedIds = items.map { it.id }.toSet()
            _photos.value = _photos.value.filterNot { deletedIds.contains(it.id) }
            _videos.value = _videos.value.filterNot { deletedIds.contains(it.id) }
            _files.value = _files.value.filterNot { deletedIds.contains(it.id) }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete media items", e)
            false
        }
    }

    private suspend fun getSavedMessagesChatId(): Long? {
        return try {
            val me = TelegramClient.sendRequest(TdApi.GetMe()) as TdApi.User
            val chat = TelegramClient.sendRequest(TdApi.CreatePrivateChat(me.id, false)) as TdApi.Chat
            chat.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Saved Messages chat ID", e)
            null
        }
    }
}
