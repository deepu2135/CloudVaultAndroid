package com.cloudvault.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

object TelegramRepository {
    private const val TAG = "TelegramRepository"

    private val _photos = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val photos: StateFlow<List<VaultMediaItem>> = _photos.asStateFlow()

    private val _videos = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val videos: StateFlow<List<VaultMediaItem>> = _videos.asStateFlow()

    private val _files = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val files: StateFlow<List<VaultMediaItem>> = _files.asStateFlow()

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    suspend fun loadVaultItems(chatId: Long = 0L) {
        val targetChatId = if (chatId != 0L) chatId else getSavedMessagesChatId() ?: return
        try {
            val history = TelegramClient.sendRequest(
                TdApi.GetChatHistory(targetChatId, 0, 0, 100, false)
            ) as TdApi.Messages

            val photoList = mutableListOf<VaultMediaItem>()
            val videoList = mutableListOf<VaultMediaItem>()
            val fileList = mutableListOf<VaultMediaItem>()

            for (msg in history.messages) {
                when (val content = msg.content) {
                    is TdApi.MessagePhoto -> {
                        val fullPhoto = content.photo.sizes.lastOrNull() ?: continue
                        val thumbPhoto = content.photo.sizes.firstOrNull()
                        photoList.add(
                            VaultMediaItem(
                                id = "photo_${msg.id}",
                                title = "Photo_${msg.date}.jpg",
                                sizeBytes = fullPhoto.photo.size.toLong(),
                                formattedSize = formatSize(fullPhoto.photo.size.toLong()),
                                mimeType = "image/jpeg",
                                type = MediaType.PHOTO,
                                chatId = msg.chatId,
                                messageId = msg.id,
                                fileId = fullPhoto.photo.id,
                                thumbnailFileId = thumbPhoto?.photo?.id ?: fullPhoto.photo.id,
                                dateAdded = msg.date.toLong()
                            )
                        )
                    }
                    is TdApi.MessageVideo -> {
                        videoList.add(
                            VaultMediaItem(
                                id = "video_${msg.id}",
                                title = content.video.fileName.ifBlank { "Video_${msg.date}.mp4" },
                                sizeBytes = content.video.video.size.toLong(),
                                formattedSize = formatSize(content.video.video.size.toLong()),
                                mimeType = content.video.mimeType.ifBlank { "video/mp4" },
                                type = MediaType.VIDEO,
                                chatId = msg.chatId,
                                messageId = msg.id,
                                fileId = content.video.video.id,
                                thumbnailFileId = content.video.thumbnail?.file?.id ?: 0,
                                dateAdded = msg.date.toLong()
                            )
                        )
                    }
                    is TdApi.MessageDocument -> {
                        val doc = content.document
                        val mime = doc.mimeType.ifBlank { "application/octet-stream" }
                        val isVideoDoc = mime.startsWith("video/", ignoreCase = true) ||
                                doc.fileName.endsWith(".mp4", ignoreCase = true) ||
                                doc.fileName.endsWith(".mkv", ignoreCase = true) ||
                                doc.fileName.endsWith(".mov", ignoreCase = true) ||
                                doc.fileName.endsWith(".avi", ignoreCase = true)

                        val item = VaultMediaItem(
                            id = "doc_${msg.id}",
                            title = doc.fileName.ifBlank { "File_${msg.date}" },
                            sizeBytes = doc.document.size.toLong(),
                            formattedSize = formatSize(doc.document.size.toLong()),
                            mimeType = mime,
                            type = if (isVideoDoc) MediaType.VIDEO else MediaType.DOCUMENT,
                            chatId = msg.chatId,
                            messageId = msg.id,
                            fileId = doc.document.id,
                            thumbnailFileId = doc.thumbnail?.file?.id ?: 0,
                            dateAdded = msg.date.toLong()
                        )

                        if (isVideoDoc) {
                            videoList.add(item)
                        } else {
                            fileList.add(item)
                        }
                    }
                }
            }

            _photos.value = photoList
            _videos.value = videoList
            _files.value = fileList

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vault items", e)
        }
    }

    suspend fun uploadFile(localPath: String, mediaType: MediaType, captionText: String = "", targetChatId: Long = 0L): Boolean {
        val chatId = if (targetChatId != 0L) targetChatId else getSavedMessagesChatId() ?: return false
        val inputFile = TdApi.InputFileLocal(localPath)
        val formattedCaption = TdApi.FormattedText(captionText, emptyArray())

        val inputContent: TdApi.InputMessageContent = when (mediaType) {
            MediaType.PHOTO -> TdApi.InputMessagePhoto().apply {
                val inputPhoto = TdApi.InputPhoto().apply {
                    photo = inputFile
                    try {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(localPath, options)
                        width = options.outWidth
                        height = options.outHeight
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not decode photo bounds", e)
                    }
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
                            val localFile = java.io.File(localPath)
                            val thumbDir = java.io.File(localFile.parentFile ?: java.io.File("/tmp"), "thumbs").apply { if (!exists()) mkdirs() }
                            val thumbFile = java.io.File(thumbDir, "thumb_${System.currentTimeMillis()}.jpg")
                            java.io.FileOutputStream(thumbFile).use { out ->
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

        return try {
            val sendMsg = TdApi.SendMessage().apply {
                this.chatId = chatId
                this.inputMessageContent = inputContent
            }
            TelegramClient.sendRequest(sendMsg)
            loadVaultItems(chatId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload file to Telegram cloud", e)
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

