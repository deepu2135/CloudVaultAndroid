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
                        val photo = content.photo.sizes.lastOrNull() ?: continue
                        photoList.add(
                            VaultMediaItem(
                                id = "photo_${msg.id}",
                                title = "Photo_${msg.date}.jpg",
                                sizeBytes = photo.photo.size.toLong(),
                                formattedSize = formatSize(photo.photo.size.toLong()),
                                mimeType = "image/jpeg",
                                type = MediaType.PHOTO,
                                chatId = msg.chatId,
                                messageId = msg.id,
                                fileId = photo.photo.id,
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
                                dateAdded = msg.date.toLong()
                            )
                        )
                    }
                    is TdApi.MessageDocument -> {
                        fileList.add(
                            VaultMediaItem(
                                id = "doc_${msg.id}",
                                title = content.document.fileName.ifBlank { "File_${msg.date}" },
                                sizeBytes = content.document.document.size.toLong(),
                                formattedSize = formatSize(content.document.document.size.toLong()),
                                mimeType = content.document.mimeType.ifBlank { "application/octet-stream" },
                                type = MediaType.DOCUMENT,
                                chatId = msg.chatId,
                                messageId = msg.id,
                                fileId = content.document.document.id,
                                dateAdded = msg.date.toLong()
                            )
                        )
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
                photo = inputFile
                caption = formattedCaption
            }
            MediaType.VIDEO -> TdApi.InputMessageVideo().apply {
                video = inputFile
                caption = formattedCaption
                supportsStreaming = true
            }
            MediaType.DOCUMENT -> TdApi.InputMessageDocument().apply {
                document = inputFile
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

