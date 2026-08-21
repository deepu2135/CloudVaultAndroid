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

    private val _audios = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val audios: StateFlow<List<VaultMediaItem>> = _audios.asStateFlow()

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

    private var lastVaultLoadTime = 0L

    fun getCachedTargetChatId(): Long? = cachedSavedMessagesChatId

    fun addOrUpdateMessage(msg: TdApi.Message) {
        if (msg.sendingState is TdApi.MessageSendingStateFailed) return
        val targetChatId = cachedSavedMessagesChatId
        // STRICT CHECK: Reject any message from other chats, groups, or channels!
        if (targetChatId != null && targetChatId != 0L && msg.chatId != targetChatId) {
            return
        }
        val photoList = _photos.value.toMutableList()
        val videoList = _videos.value.toMutableList()
        val audioList = _audios.value.toMutableList()
        val fileList = _files.value.toMutableList()
        val initialCounts = listOf(photoList.size, videoList.size, audioList.size, fileList.size)

        parseAndClassifyMessage(msg, photoList, videoList, audioList, fileList)

        if (photoList.size != initialCounts[0]) {
            _photos.value = photoList.distinctBy { it.id }.sortedByDescending { it.dateAdded }
        }
        if (videoList.size != initialCounts[1]) {
            _videos.value = videoList.distinctBy { it.id }.sortedByDescending { it.dateAdded }
        }
        if (audioList.size != initialCounts[2]) {
            _audios.value = audioList.distinctBy { it.id }.sortedByDescending { it.dateAdded }
        }
        if (fileList.size != initialCounts[3]) {
            _files.value = fileList.distinctBy { it.id }.sortedByDescending { it.dateAdded }
        }
    }

    private val vaultLoadMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun loadVaultItems(chatId: Long = 0L, force: Boolean = false) {
        if (!vaultLoadMutex.tryLock()) {
            // Already actively loading vault history, avoid duplicate concurrent full scans
            return
        }
        _isLoadingVault.value = true
        try {
            val targetChatId = if (chatId != 0L) chatId else getSavedMessagesChatId() ?: run {
                TeleflixLogger.log(TAG, "Cannot load vault items: targetChatId is null", isError = true)
                return
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastVaultLoadTime < 3_000L && (_photos.value.isNotEmpty() || _videos.value.isNotEmpty() || _audios.value.isNotEmpty() || _files.value.isNotEmpty())) {
                return
            }
            lastVaultLoadTime = now

            // Ensure chat is loaded & actively open chat in TDLib
            try {
                TelegramClient.sendRequest(TdApi.GetChat(targetChatId))
            } catch (e: Throwable) {
                Log.d(TAG, "GetChat note: ${e.message}")
            }
            try {
                TelegramClient.sendRequest(TdApi.OpenChat(targetChatId))
            } catch (e: Throwable) {
                Log.d(TAG, "OpenChat note: ${e.message}")
            }

            val photoList = mutableListOf<VaultMediaItem>()
            val videoList = mutableListOf<VaultMediaItem>()
            val audioList = mutableListOf<VaultMediaItem>()
            val fileList = mutableListOf<VaultMediaItem>()
            val seenMessageIds = mutableSetOf<Long>()

            var fromMessageId = 0L
            var consecutiveEmptyBatches = 0
            val batchSize = 100
            val maxMessagesToScan = 50000

            var scannedCount = 0
            while (scannedCount < maxMessagesToScan) {
                val history = try {
                    TelegramClient.sendRequest(
                        TdApi.GetChatHistory(targetChatId, fromMessageId, 0, batchSize, false)
                    ) as? TdApi.Messages
                } catch (e: Throwable) {
                    Log.w(TAG, "GetChatHistory error at fromMessageId=$fromMessageId: ${e.message}")
                    null
                }

                if (history == null || history.messages.isEmpty()) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches < 4) {
                        kotlinx.coroutines.delay(300L)
                        continue
                    }
                    // End of history reached
                    break
                }

                consecutiveEmptyBatches = 0
                for (msg in history.messages) {
                    if (seenMessageIds.add(msg.id)) {
                        parseAndClassifyMessage(msg, photoList, videoList, audioList, fileList)
                    }
                }
                scannedCount += history.messages.size
                val lastId = history.messages.last().id
                if (lastId == fromMessageId) {
                    // Prevent infinite loop if ID didn't change
                    break
                }
                fromMessageId = lastId

                // Progressively update UI state flows as batches load
                if (scannedCount % 200 == 0 || scannedCount <= 100) {
                    _photos.value = photoList.sortedByDescending { it.dateAdded }
                    _videos.value = videoList.sortedByDescending { it.dateAdded }
                    _audios.value = audioList.sortedByDescending { it.dateAdded }
                    _files.value = fileList.sortedByDescending { it.dateAdded }
                }
            }

            // Final state flow update
            _photos.value = photoList.sortedByDescending { it.dateAdded }
            _videos.value = videoList.sortedByDescending { it.dateAdded }
            _audios.value = audioList.sortedByDescending { it.dateAdded }
            _files.value = fileList.sortedByDescending { it.dateAdded }

            TeleflixLogger.log(TAG, "Vault loaded: ${photoList.size} photos, ${videoList.size} videos, ${audioList.size} audios, ${fileList.size} files (scanned $scannedCount messages from chat $targetChatId)")

        } catch (e: Exception) {
            TeleflixLogger.log(TAG, "Failed to load vault items: ${e.message}", isError = true)
        } finally {
            _isLoadingVault.value = false
            vaultLoadMutex.unlock()
        }
    }

    private fun parseAndClassifyMessage(
        msg: TdApi.Message,
        photoList: MutableList<VaultMediaItem>,
        videoList: MutableList<VaultMediaItem>,
        audioList: MutableList<VaultMediaItem>,
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

                val isAudio = !isVideo && !isPhoto && (
                        mime.startsWith("audio/") ||
                        name.endsWith(".mp3") ||
                        name.endsWith(".m4a") ||
                        name.endsWith(".wav") ||
                        name.endsWith(".flac") ||
                        name.endsWith(".aac") ||
                        name.endsWith(".ogg") ||
                        name.endsWith(".oga") ||
                        name.endsWith(".opus") ||
                        name.endsWith(".wma") ||
                        name.endsWith(".amr") ||
                        name.endsWith(".alac") ||
                        name.endsWith(".aiff") ||
                        name.endsWith(".mid") ||
                        name.endsWith(".midi")
                )

                val itemType = when {
                    isVideo -> MediaType.VIDEO
                    isPhoto -> MediaType.PHOTO
                    isAudio -> MediaType.AUDIO
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
                    MediaType.AUDIO -> audioList.add(item)
                    MediaType.DOCUMENT -> fileList.add(item)
                }
            }
            is TdApi.MessageAnimation -> {
                val anim = content.animation
                val caption = content.caption?.text.orEmpty().trim()
                val animTitle = if (anim.fileName.isNotBlank()) anim.fileName else if (caption.isNotBlank()) caption else "GIF_${msg.date}.mp4"
                videoList.add(
                    VaultMediaItem(
                        id = "anim_${msg.id}",
                        title = animTitle,
                        caption = caption,
                        sizeBytes = anim.animation.size.toLong(),
                        formattedSize = formatSize(anim.animation.size.toLong()),
                        mimeType = anim.mimeType.ifBlank { "video/mp4" },
                        type = MediaType.VIDEO,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        fileId = anim.animation.id,
                        thumbnailFileId = anim.thumbnail?.file?.id ?: 0,
                        dateAdded = msg.date.toLong(),
                        durationSeconds = anim.duration
                    )
                )
            }
            is TdApi.MessageAudio -> {
                val audio = content.audio
                val caption = content.caption?.text.orEmpty().trim()
                val audioTitle = if (audio.fileName.isNotBlank()) audio.fileName else if (audio.title.isNotBlank()) audio.title else "Audio_${msg.date}.mp3"
                audioList.add(
                    VaultMediaItem(
                        id = "audio_${msg.id}",
                        title = audioTitle,
                        caption = caption,
                        sizeBytes = audio.audio.size.toLong(),
                        formattedSize = formatSize(audio.audio.size.toLong()),
                        mimeType = audio.mimeType.ifBlank { "audio/mpeg" },
                        type = MediaType.AUDIO,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        fileId = audio.audio.id,
                        thumbnailFileId = audio.albumCoverThumbnail?.file?.id ?: 0,
                        dateAdded = msg.date.toLong(),
                        durationSeconds = audio.duration
                    )
                )
            }
            is TdApi.MessageVoiceNote -> {
                val voice = content.voiceNote
                val caption = content.caption?.text.orEmpty().trim()
                audioList.add(
                    VaultMediaItem(
                        id = "voice_${msg.id}",
                        title = if (caption.isNotBlank()) caption else "Voice_${msg.date}.ogg",
                        caption = caption,
                        sizeBytes = voice.voice.size.toLong(),
                        formattedSize = formatSize(voice.voice.size.toLong()),
                        mimeType = voice.mimeType.ifBlank { "audio/ogg" },
                        type = MediaType.AUDIO,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        fileId = voice.voice.id,
                        thumbnailFileId = 0,
                        dateAdded = msg.date.toLong(),
                        durationSeconds = voice.duration
                    )
                )
            }
            is TdApi.MessageVideoNote -> {
                val videoNote = content.videoNote
                videoList.add(
                    VaultMediaItem(
                        id = "videonote_${msg.id}",
                        title = "VideoNote_${msg.date}.mp4",
                        caption = "",
                        sizeBytes = videoNote.video.size.toLong(),
                        formattedSize = formatSize(videoNote.video.size.toLong()),
                        mimeType = "video/mp4",
                        type = MediaType.VIDEO,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        fileId = videoNote.video.id,
                        thumbnailFileId = videoNote.thumbnail?.file?.id ?: 0,
                        dateAdded = msg.date.toLong(),
                        durationSeconds = videoNote.duration
                    )
                )
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

        val chatId = if (targetChatId != 0L) targetChatId else getSavedMessagesChatId() ?: return@withContext false
        val formattedCaption = TdApi.FormattedText(captionText, emptyArray())

        var actualMediaType = mediaType
        var photoWidth = 0
        var photoHeight = 0
        var finalUploadPath = localPath
        var isCompressedTemp = false

        if (actualMediaType == MediaType.PHOTO) {
            val (preparedFile, isTemp) = ImageUtils.preparePhotoForTelegramUpload(CloudVaultApp.instance, localPath, maxDimension = 2560)
            finalUploadPath = preparedFile.absolutePath
            isCompressedTemp = isTemp

            try {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(finalUploadPath, options)
                photoWidth = options.outWidth
                photoHeight = options.outHeight

                val preparedSize = File(finalUploadPath).length()
                if (photoWidth <= 0 || photoHeight <= 0 || preparedSize > 10485760L || photoWidth + photoHeight > 10000 || photoWidth > 10000 || photoHeight > 10000) {
                    actualMediaType = MediaType.DOCUMENT
                    TeleflixLogger.log(TAG, "Photo exceeds limits or invalid (size: $preparedSize, dim: ${photoWidth}x${photoHeight}), sending as document")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not decode photo bounds", e)
                actualMediaType = MediaType.DOCUMENT
            }
        }

        val totalFileSize = File(finalUploadPath).length()
        val inputFile = TdApi.InputFileLocal(finalUploadPath)

        val inputContent: TdApi.InputMessageContent = when (actualMediaType) {
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
            MediaType.AUDIO -> TdApi.InputMessageAudio().apply {
                val inputAudio = TdApi.InputAudio().apply {
                    audio = inputFile
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(localPath)
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val titleStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        val artistStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)

                        duration = (durationStr?.toLongOrNull() ?: 0L).let { (it / 1000).toInt() }
                        title = titleStr?.ifBlank { targetFile.name } ?: targetFile.name
                        performer = artistStr ?: ""
                        retriever.release()
                    } catch (e: Throwable) {
                        title = targetFile.name
                    }
                }
                audio = inputAudio
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
                is TdApi.MessageAudio -> c.audio.audio.id
                is TdApi.MessageVoiceNote -> c.voiceNote.voice.id
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
                TeleflixLogger.log(TAG, "uploadFile completed successfully to Telegram Cloud! tempMsgId=$tempMsgId")
                true
            } else {
                TeleflixLogger.log(TAG, "uploadFile failed or timed out for $localPath", isError = true)
                false
            }
        } catch (e: Exception) {
            TeleflixLogger.log(TAG, "Failed to upload file to Telegram cloud: ${e.message}", isError = true)
            false
        } finally {
            if (isCompressedTemp) {
                runCatching { File(finalUploadPath).delete() }
            }
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
            _audios.value = _audios.value.filterNot { deletedIds.contains(it.id) }
            _files.value = _files.value.filterNot { deletedIds.contains(it.id) }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete media items", e)
            false
        }
    }

    private var cachedSavedMessagesChatId: Long? = null

    suspend fun getSavedMessagesChatId(): Long? {
        if (cachedSavedMessagesChatId != null && cachedSavedMessagesChatId != 0L) {
            return cachedSavedMessagesChatId
        }
        return try {
            val me = TelegramClient.sendRequest(TdApi.GetMe()) as? TdApi.User
            if (me != null) {
                val myUserId = me.id
                var resolvedChatId = myUserId
                try {
                    val chat = TelegramClient.sendRequest(TdApi.CreatePrivateChat(myUserId, false)) as? TdApi.Chat
                    if (chat != null && chat.id != 0L) {
                        resolvedChatId = chat.id
                    }
                } catch (e: Throwable) {
                    try {
                        val chat = TelegramClient.sendRequest(TdApi.GetChat(myUserId)) as? TdApi.Chat
                        if (chat != null && chat.id != 0L) {
                            resolvedChatId = chat.id
                        }
                    } catch (_: Throwable) {
                        // fallback to myUserId
                    }
                }
                cachedSavedMessagesChatId = resolvedChatId
                TeleflixLogger.log(TAG, "Resolved Saved Messages chat ID: $resolvedChatId (myUserId=$myUserId)")
                resolvedChatId
            } else {
                TeleflixLogger.log(TAG, "GetMe returned non-user or null", isError = true)
                null
            }
        } catch (e: Exception) {
            TeleflixLogger.log(TAG, "Failed to resolve Saved Messages chat ID: ${e.message}", isError = true)
            null
        }
    }
}
