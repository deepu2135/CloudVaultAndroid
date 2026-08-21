package com.cloudvault.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object TelegramStreamingProxy {
    private const val TAG = "TelegramProxy"
    private const val CHUNK_SIZE = 512 * 1024         // 512 KB per socket chunk for maximum throughput & fast demuxing
    var prefetchSizeMb = 32L                             // Prefetch window sent to TDLib (dynamically configured)

    fun setPrefetchMb(mb: Long) {
        prefetchSizeMb = mb.coerceIn(4L, 512L)
    }
    private const val DOWNLOAD_TIMEOUT_MS = 60_000L
    private const val DOWNLOAD_PRIORITY = 32              // max TDLib priority
    private const val POLL_INTERVAL_MS = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var port: Int = 0
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val activeStreamRequests = java.util.concurrent.ConcurrentHashMap<Int, MutableSet<String>>()
    private val latestActiveStreamReqId = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val activeFileJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val activeDownloadWindows = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Long>>()
    private val lastDownloadRequestOffset = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val lastDownloadRequestTime = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val thumbnailMemoryCache = object : android.util.LruCache<Int, ByteArray>(30 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray): Int {
            return value.size
        }
    }
    private val messageThumbMap = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Int>()
    private val fileMutexes = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()
    private fun getFileMutex(fileId: Int): Mutex = fileMutexes.getOrPut(fileId) { Mutex() }
    private val fileToMessageMap = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Long>>()
    private val fileIdTranslationMap = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile private var lastStreamedFileId: Int? = null

    fun registerFileMessage(fileId: Int, chatId: Long, messageId: Long) {
        if (fileId != 0 && chatId != 0L && messageId != 0L) {
            fileToMessageMap[fileId] = Pair(chatId, messageId)
        }
    }

    fun resolveFileId(fileId: Int): Int {
        var curr = fileId
        var hops = 0
        while (fileIdTranslationMap.containsKey(curr) && hops < 5) {
            curr = fileIdTranslationMap[curr] ?: curr
            hops++
        }
        return curr
    }

    private val lastRefreshTimeMap = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    suspend fun refreshFileId(fileId: Int, force: Boolean = false): Int? {
        val target = resolveFileId(fileId)
        val pair = fileToMessageMap[target] ?: fileToMessageMap[fileId] ?: return null
        val (chatId, messageId) = pair
        if (chatId == 0L || messageId == 0L) return null
        val now = System.currentTimeMillis()
        val lastTime = lastRefreshTimeMap[target] ?: 0L
        if (!force && (now - lastTime) < 2000L) {
            return target
        }
        lastRefreshTimeMap[target] = now
        return try {
            val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message ?: return null
            val freshId = when (val content = msg.content) {
                is TdApi.MessageVideo -> content.video.video.id
                is TdApi.MessageDocument -> content.document.document.id
                is TdApi.MessageAudio -> content.audio.audio.id
                is TdApi.MessageVoiceNote -> content.voiceNote.voice.id
                is TdApi.MessageAnimation -> content.animation.animation.id
                is TdApi.MessageVideoNote -> content.videoNote.video.id
                else -> null
            }
            if (freshId != null && freshId != 0) {
                fileIdTranslationMap[fileId] = freshId
                fileIdTranslationMap[target] = freshId
                fileToMessageMap[freshId] = pair
                fileToMessageMap[fileId] = pair
                fileToMessageMap[target] = pair
                TeleflixLogger.log(TAG, "Refreshed fileId from TDLib message lookup: original=$fileId -> fresh=$freshId")
                freshId
            } else target
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                TeleflixLogger.log(TAG, "Failed refreshFileId for fileId $fileId (chatId=$chatId, msgId=$messageId): ${e.message}")
            }
            null
        }
    }

    data class StreamMetrics(
        val reqId: String = java.util.UUID.randomUUID().toString().substring(0, 6),
        val fileId: Int,
        val rangeHeader: String?,
        val startOffset: Long,
        val totalSize: Long,
        val startTimeMs: Long = System.currentTimeMillis(),
        var totalBytesServed: Long = 0L,
        var chunksOk: Int = 0,
        var chunksRetried: Int = 0,
        var chunksTimedOut: Int = 0,
        var totalQueueWaitMs: Long = 0L,
        var exitReason: String = "completed"
    ) {
        val requestType: String
            get() = when {
                totalSize > 0 && startOffset >= maxOf(0L, totalSize - 1_000_000L) -> "seek_probe"
                startOffset > 10_000_000L -> "seek_stream"
                else -> "normal_stream"
            }

        fun logStart() {
            TeleflixLogger.log("TelegramProxy", "Stream START id=$fileId range=${rangeHeader ?: "full"} reqId=$reqId type=$requestType prefetchMb=$prefetchSizeMb")
        }

        fun logEnd() {
            val durationMs = maxOf(1L, System.currentTimeMillis() - startTimeMs)
            val avgKbps = (totalBytesServed * 8L) / durationMs
            val totalMb = String.format(java.util.Locale.US, "%.2f MB", totalBytesServed.toDouble() / (1024.0 * 1024.0))
            TeleflixLogger.log("TelegramProxy", "Stream END id=$fileId reqId=$reqId type=$requestType bytesServed=$totalBytesServed ($totalMb) durationMs=$durationMs chunksFetched=$chunksOk queueWaitMs=$totalQueueWaitMs avgKbps=$avgKbps reason=$exitReason")
            
            val jsonLog = "{\"ts\":\"${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())}\",\"event\":\"stream_end\",\"fileId\":$fileId,\"reqId\":\"$reqId\",\"type\":\"$requestType\",\"bytesServed\":$totalBytesServed,\"durationMs\":$durationMs,\"chunksFetched\":$chunksOk,\"queueWaitMs\":$totalQueueWaitMs,\"avgKbps\":$avgKbps,\"reason\":\"$exitReason\"}"
            TeleflixLogger.log("TelegramProxyMetrics", jsonLog)
        }
    }

    private suspend fun triggerTdlibDownload(fileId: Int, offset: Long, limit: Long = 0L, force: Boolean = false, priority: Int = DOWNLOAD_PRIORITY) {
        val now = System.currentTimeMillis()
        val lastOffset = lastDownloadRequestOffset[fileId]
        val lastTime = lastDownloadRequestTime[fileId] ?: 0L

        val isOffsetJump = lastOffset != null && Math.abs(offset - lastOffset) > 500_000L

        // Rate limit: do NOT re-issue DownloadFile for the exact same offset within 2500ms unless forced or seeking
        if (!force && !isOffsetJump && lastOffset == offset && (now - lastTime) < 2500L) {
            return
        }

        lastDownloadRequestOffset[fileId] = offset
        lastDownloadRequestTime[fileId] = now

        withContext(NonCancellable) {
            try {
                val res = TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                    req.fileId = fileId
                    req.priority = priority
                    req.offset = offset
                    req.limit = limit
                    req.synchronous = false
                })
                if (res is TdApi.Error) {
                    TeleflixLogger.log(TAG, "[TDLib Error] DownloadFile fileId=$fileId offset=$offset limit=$limit: code=${res.code} message=${res.message}", isError = true)
                } else {
                    TeleflixLogger.log(TAG, "[TDLib OK] DownloadFile fileId=$fileId offset=$offset limit=$limit force=$force jump=$isOffsetJump priority=$priority")
                }
            } catch (e: Exception) {
                TeleflixLogger.log(TAG, "[TDLib Exception] DownloadFile fileId=$fileId: ${e.message}", isError = true)
            }
        }
    }
    private val authToken = java.util.UUID.randomUUID().toString()

    fun start() {
        if (serverSocket != null) return
        port = findFreePort()
        serverSocket = ServerSocket(port)
        running = true
        Log.d(TAG, "Streaming proxy starting on port $port")

        thread(name = "TelegramProxyListener") {
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(socket)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Error accepting client: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        activeFileJobs.values.forEach { runCatching { it.cancel() } }
        activeFileJobs.clear()
        lastStreamedFileId = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.d(TAG, "Streaming proxy stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            try { socket.sendBufferSize = 2097152 } catch (_: Exception) {}
            try { socket.receiveBufferSize = 2097152 } catch (_: Exception) {}
            socket.soTimeout = 60000
            val inputStream = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = inputStream.bufferedReader()

            while (running && !socket.isClosed) {
                val reqLine = try {
                    reader.readLine()
                } catch (e: Exception) {
                    null
                } ?: break

                if (reqLine.isBlank()) continue
                val parts = reqLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase()
                val path = parts[1] // /file/{fileId} or /thumbnail/{fileId}
                val isHead = (method == "HEAD")

                if (method == "OPTIONS") {
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Allow: GET, HEAD, OPTIONS\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: keep-alive\r\n\r\n"
                    output.write(response.toByteArray())
                    output.flush()
                    continue
                }

                val queryParams = path.substringAfter("?", "")
                val receivedToken = queryParams.split("&").find { it.startsWith("token=") }?.substringAfter("=")
                if (receivedToken != authToken) {
                    output.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\nAccess Denied: Missing or Invalid Proxy Token".toByteArray())
                    output.flush()
                    break
                }

                var fileId: Int? = null
                var isThumbnail = false
                var urlSize = 0L
                var fileName: String? = null
                var mergedFileIds: List<Int>? = null
                var mergedSizes: List<Long>? = null
                var zipInnerName: String? = null

                if (path.startsWith("/file/")) {
                    val segment = path.substringAfter("/file/").substringBefore("?")
                    fileId = segment.substringBefore("/").toIntOrNull()
                    val encodedName = segment.substringAfter("/", "").takeIf { it.isNotBlank() }
                    if (encodedName != null) {
                        fileName = java.net.URLDecoder.decode(encodedName, "UTF-8")
                    }
                    val queryStr = path.substringAfter("?", "")
                    if (queryStr.isNotBlank()) {
                        urlSize = queryStr.split("&").find { it.startsWith("size=") }?.substringAfter("=")?.toLongOrNull() ?: 0L
                    }
                } else if (path.startsWith("/thumbnail/")) {
                    val segment = path.substringAfter("/thumbnail/").substringBefore("?")
                    val thumbParts = segment.split("/")
                    if (thumbParts.size == 1) {
                        fileId = thumbParts[0].toIntOrNull()
                    } else if (thumbParts.size == 2) {
                        val chatId = thumbParts[0].toLongOrNull()
                        val messageId = thumbParts[1].toLongOrNull()
                        if (chatId != null && messageId != null) {
                            val key = Pair(chatId, messageId)
                            val cachedId = messageThumbMap[key]
                            if (cachedId != null) {
                                fileId = cachedId
                            } else {
                                try {
                                    val msg = TelegramClient.sendRequest(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                                    if (msg != null) {
                                        when (val content = msg.content) {
                                            is TdApi.MessageVideo -> fileId = content.video.thumbnail?.file?.id
                                            is TdApi.MessageDocument -> fileId = content.document.thumbnail?.file?.id
                                            is TdApi.MessageAudio -> fileId = content.audio.albumCoverThumbnail?.file?.id
                                        }
                                        if (fileId != null) {
                                            messageThumbMap[key] = fileId
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    isThumbnail = true
                } else if (path.startsWith("/zip/")) {
                    val segment = path.substringAfter("/zip/").substringBefore("?")
                    val slashParts = segment.split("/", limit = 2)
                    fileId = slashParts[0].toIntOrNull()
                    zipInnerName = if (slashParts.size > 1) java.net.URLDecoder.decode(slashParts[1], "UTF-8") else null
                    val queryStr = path.substringAfter("?", "")
                    urlSize = queryStr.split("&").find { it.startsWith("size=") }
                        ?.substringAfter("=")?.toLongOrNull() ?: 0L
                }

                if (path.contains("merged=")) {
                    val queryStr = path.substringAfter("?", "")
                    val mergedParam = queryStr.split("&").find { it.startsWith("merged=") }?.substringAfter("=")
                    if (mergedParam != null) {
                        mergedFileIds = mergedParam.split(",").mapNotNull { it.toIntOrNull() }
                    }
                    val sizesParam = queryStr.split("&").find { it.startsWith("sizes=") }?.substringAfter("=")
                    if (sizesParam != null) {
                        mergedSizes = sizesParam.split(",").mapNotNull { it.toLongOrNull() }
                    }
                }
                if (path.contains("zip_entry=")) {
                    val queryStr = path.substringAfter("?", "")
                    val zipParam = queryStr.split("&").find { it.startsWith("zip_entry=") }?.substringAfter("=")
                    if (zipParam != null) {
                        zipInnerName = java.net.URLDecoder.decode(zipParam, "UTF-8")
                    }
                }

                val queryStr = path.substringAfter("?", "")
                val queryPairs = queryStr.split("&").mapNotNull {
                    val p = it.split("=", limit = 2)
                    if (p.size == 2) p[0] to p[1] else null
                }.toMap()

                val reqChatId = queryPairs["chatId"]?.toLongOrNull() ?: 0L
                val reqMessageId = queryPairs["messageId"]?.toLongOrNull() ?: 0L
                val reqChats = queryPairs["chats"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
                val reqMessages = queryPairs["messages"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()

                if (fileId != null && reqChatId != 0L && reqMessageId != 0L) {
                    registerFileMessage(fileId, reqChatId, reqMessageId)
                }
                if (mergedFileIds != null) {
                    mergedFileIds.forEachIndexed { i, fId ->
                        val cId = reqChats.getOrNull(i) ?: reqChatId
                        val mId = reqMessages.getOrNull(i)
                        if (cId != 0L && mId != null && mId != 0L) {
                            registerFileMessage(fId, cId, mId)
                        }
                    }
                }

                if (fileId == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                    output.flush()
                    break
                }

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                TeleflixLogger.log(TAG, "HTTP $method $path | Range: ${rangeHeader ?: "full"}")

                if (isThumbnail) {
                    serveThumbnail(fileId, output, isHead)
                } else if (mergedFileIds != null && mergedSizes != null && mergedFileIds.size == mergedSizes.size) {
                    streamMergedFile(mergedFileIds, mergedSizes, fileName ?: zipInnerName, rangeHeader, output, isHead)
                } else if (zipInnerName != null) {
                    streamZipEntry(fileId, zipInnerName, rangeHeader, output, urlSize, isHead)
                } else if (fileName != null && TelegramRepository.isZipArchiveFilename(fileName)) {
                    streamZipEntryFromMergedOrSingle(listOf(fileId), listOf(if (urlSize > 0L) urlSize else getFileInfo(fileId)?.second ?: 0L), fileName, rangeHeader, output, isHead)
                } else {
                    streamFile(fileId, fileName, rangeHeader, output, urlSize, isHead)
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            TeleflixLogger.log(TAG, "Client stream cancelled")
        } catch (e: IOException) {
            TeleflixLogger.log(TAG, "Client disconnected: ${e.message}")
        } catch (e: Exception) {
            TeleflixLogger.log(TAG, "Error handling client: ${e.message}", isError = true)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun calculateSafeTdlibLimit(offset: Long, totalSize: Long, prefetchMb: Long, chunkSize: Int): Long {
        val rawPrefetch = when {
            prefetchMb >= 102400L || prefetchMb == -1L -> 0L // 0 in TDLib means unlimited (download to EOF)
            prefetchMb <= 0L -> chunkSize.toLong()
            else -> maxOf(chunkSize.toLong(), prefetchMb * 1024L * 1024L)
        }
        if (rawPrefetch == 0L) return 0L
        if (totalSize <= 0L) return rawPrefetch
        val remaining = maxOf(0L, totalSize - offset)
        return minOf(rawPrefetch, remaining)
    }

    private suspend fun streamFile(fileId: Int, fileName: String?, rangeHeader: String?, output: java.io.OutputStream, urlSize: Long, isHead: Boolean = false) {
        val (rangeStart, rangeEnd) = parseRange(rangeHeader)
        var metrics: StreamMetrics? = null
        var currentJobRegisteredKey: String? = null
        val currentJob = kotlin.coroutines.coroutineContext[Job]

        try {
            lastStreamedFileId = fileId
            lastDownloadRequestOffset.remove(fileId)
            lastDownloadRequestTime.remove(fileId)
            activeDownloadWindows.remove(fileId)

            // Pre-warm TDLib message reference in the background without blocking the HTTP response
            val targetFileId = resolveFileId(fileId)
            if (fileToMessageMap.containsKey(targetFileId) || fileToMessageMap.containsKey(fileId)) {
                scope.launch { runCatching { refreshFileId(targetFileId) } }
            }

            // Get file total size instantly from URL parameter if available, else query TDLib
            val totalSize: Long = if (urlSize > 0L) {
                urlSize
            } else {
                val fileInfo = getFileInfo(fileId)
                fileInfo?.second?.takeIf { it > 0 } ?: fileInfo?.third?.takeIf { it > 0 } ?: 0L
            }

            if (totalSize <= 0L) {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val start: Long
            var end: Long

            if (rangeStart == null && rangeEnd != null) {
                // Suffix byte range: e.g., bytes=-500 means the last 500 bytes
                start = maxOf(0L, totalSize - rangeEnd)
                end = totalSize - 1L
            } else {
                start = rangeStart ?: 0L
                end = rangeEnd ?: (totalSize - 1L)
            }

            end = minOf(end, totalSize - 1L)

            val m = StreamMetrics(
                fileId = fileId,
                rangeHeader = rangeHeader,
                startOffset = start,
                totalSize = totalSize
            )
            metrics = m
            activeStreamRequests.getOrPut(fileId) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }.add(m.reqId)

            val jobKey = "file_${fileId}_${m.reqId}"
            currentJobRegisteredKey = jobKey
            if (currentJob != null) {
                activeFileJobs[jobKey] = currentJob
            }
            m.logStart()

            if (start >= totalSize || start > end) {
                m.exitReason = "range_not_satisfiable"
                m.logEnd()
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalSize\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val length = end - start + 1

            val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() } ?: "mp4"
                
            val mimeType = getMimeType(ext)

            val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
            val safeFileName = fileName?.replace("\"", "\\\"") ?: "video.$ext"
            val headers = StringBuilder().apply {
                append("HTTP/1.1 $status\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (rangeHeader != null) {
                    append("Content-Range: bytes $start-$end/$totalSize\r\n")
                }
                append("Content-Type: $mimeType\r\n")
                append("Content-Disposition: inline; filename=\"$safeFileName\"\r\n")
                append("Connection: keep-alive\r\n")
                append("Keep-Alive: timeout=60, max=1000\r\n\r\n")
            }.toString()

            output.write(headers.toByteArray())
            output.flush()

            if (isHead) {
                m.exitReason = "head_request"
                m.logEnd()
                return
            }

            var activeDownloadEnd = -1L
            val isNormalOrSeek = (m.requestType == "normal_stream" || m.requestType == "seek_stream")

            var offset = start
            while (offset <= end && running) {
                val chunkSize = minOf(CHUNK_SIZE.toLong(), end - offset + 1).toInt()
                val alignedOffset = offset - (offset % (1024 * 1024))
                // Fast-start: request 4MB prefetch for initial chunks for instant playback, then continuous buffer
                val currentPrefetch = if (m.chunksOk < 2) minOf(4L, prefetchSizeMb) else prefetchSizeMb
                val safeLimit = calculateSafeTdlibLimit(alignedOffset, totalSize, currentPrefetch, chunkSize)

                if (activeDownloadEnd < 0L || offset >= activeDownloadEnd - maxOf(CHUNK_SIZE.toLong(), safeLimit / 4)) {
                    val isFirstTrigger = activeDownloadEnd < 0L
                    val p = if (m.requestType == "seek_probe") 32 else DOWNLOAD_PRIORITY
                    triggerTdlibDownload(fileId, alignedOffset, safeLimit, force = isFirstTrigger, priority = p)
                    activeDownloadEnd = if (safeLimit == 0L) totalSize else alignedOffset + safeLimit
                    activeDownloadWindows[fileId] = Pair(alignedOffset, activeDownloadEnd)
                }

                val bytes = downloadChunk(fileId, offset, chunkSize, m)
                if (bytes == null || bytes.isEmpty()) {
                    if (m.exitReason == "completed") {
                        m.exitReason = "read_timeout_or_empty"
                    }
                    break
                }
                try {
                    output.write(bytes)
                    output.flush()
                    offset += bytes.size
                    m.totalBytesServed += bytes.size
                    m.chunksOk++

                    // For large video files (>20MB), throttle socket bursts after fast-start (first 4MB) to prevent LibVLC demuxer queue overruns
                    if (isNormalOrSeek && totalSize > 20_000_000L && m.chunksOk > 8) {
                        delay(12L) // ~40 MB/s max socket throughput (8x real-time 1080p bitrate)
                    }
                } catch (e: Exception) {
                    m.exitReason = "client_disconnect"
                    break
                }
            }
            m.logEnd()
        } finally {
            if (currentJob != null && currentJobRegisteredKey != null) {
                activeFileJobs.remove(currentJobRegisteredKey, currentJob)
            }
            val reqId = metrics?.reqId
            if (reqId != null) {
                if (latestActiveStreamReqId[fileId] == reqId) {
                    latestActiveStreamReqId.remove(fileId)
                }
                val reqSet = activeStreamRequests[fileId]
                reqSet?.remove(reqId)
                if (reqSet == null || reqSet.isEmpty()) {
                    activeStreamRequests.remove(fileId)
                    // 5-second grace period: do NOT cancel TDLib download immediately.
                    // Media players often open header/probe requests that close right before starting full playback.
                    scope.launch {
                        delay(5000L)
                        val currentRequests = activeStreamRequests[fileId]
                        if ((currentRequests == null || currentRequests.isEmpty()) && !DownloadManager.isFileIdActive(fileId)) {
                            activeDownloadWindows.remove(fileId)
                            TeleflixLogger.log(TAG, "No active streams remaining for fileId=$fileId after 5s grace period, cancelling TDLib background download")
                            runCatching {
                                TelegramClient.sendRequest(TdApi.CancelDownloadFile(fileId, false))
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun readChunkFromMerged(
        fileIds: List<Int>,
        sizes: List<Long>,
        globalOffset: Long,
        limit: Int,
        metrics: StreamMetrics? = null
    ): ByteArray? {
        if (fileIds.isEmpty() || sizes.isEmpty()) return null
        val totalSize = sizes.sum()
        if (globalOffset >= totalSize) return null

        val partStartOffsets = LongArray(sizes.size)
        for (i in 1 until sizes.size) {
            partStartOffsets[i] = partStartOffsets[i - 1] + sizes[i - 1]
        }

        var partIndex = partStartOffsets.indexOfLast { it <= globalOffset }
        if (partIndex < 0) partIndex = 0

        val partFileId = fileIds[partIndex]
        val partOffset = globalOffset - partStartOffsets[partIndex]
        val partRemaining = sizes[partIndex] - partOffset
        val chunkSize = minOf(limit.toLong(), partRemaining).toInt()

        val partSize = sizes[partIndex]
        val alignedPartOffset = partOffset - (partOffset % (1024 * 1024))
        val safeLimit = calculateSafeTdlibLimit(alignedPartOffset, partSize, prefetchSizeMb, chunkSize)

        triggerTdlibDownload(partFileId, alignedPartOffset, safeLimit)

        // Proactively prefetch the NEXT part when approaching boundary (within 50MB)
        if (partIndex + 1 < fileIds.size && partRemaining < 50 * 1024 * 1024L) {
            val nextFileId = fileIds[partIndex + 1]
            val nextSize = sizes[partIndex + 1]
            val nextSafeLimit = calculateSafeTdlibLimit(0L, nextSize, prefetchSizeMb, chunkSize)
            triggerTdlibDownload(nextFileId, 0L, nextSafeLimit)
        }

        var chunk = downloadChunk(partFileId, partOffset, chunkSize, metrics)
        var retries = 0
        while ((chunk == null || chunk.isEmpty()) && retries < 10 && running) {
            if (metrics?.exitReason == "superseded") break
            val currentPartId = resolveFileId(partFileId)
            val hasRef = fileToMessageMap.containsKey(currentPartId) || fileToMessageMap.containsKey(partFileId)
            if (hasRef) {
                val refreshed = refreshFileId(currentPartId)
                if (refreshed != null && refreshed != 0) {
                    val otherIndex = fileIds.indexOf(refreshed)
                    if (otherIndex >= 0 && otherIndex != partIndex) {
                        TeleflixLogger.log(TAG, "refreshFileId mapped partFileId=$currentPartId (part $partIndex) to fileId=$refreshed which belongs to part $otherIndex - ignoring invalid translation", isError = true)
                        fileIdTranslationMap.remove(currentPartId)
                        fileIdTranslationMap.remove(partFileId)
                    }
                }
            } else {
                TeleflixLogger.log(TAG, "readChunkFromMerged: partFileId=$partFileId not found in TDLib and no message reference available, stopping retries", isError = true)
                break
            }
            triggerTdlibDownload(resolveFileId(partFileId), alignedPartOffset, safeLimit, force = true)
            kotlinx.coroutines.delay(300)
            chunk = downloadChunk(resolveFileId(partFileId), partOffset, chunkSize, metrics)
            retries++
        }
        return chunk
    }

    private suspend fun readBufferFromMerged(
        fileIds: List<Int>,
        sizes: List<Long>,
        globalOffset: Long,
        totalToRead: Int,
        metrics: StreamMetrics? = null
    ): ByteArray? {
        if (totalToRead <= 0) return ByteArray(0)
        val buffer = ByteArray(totalToRead)
        var bytesRead = 0
        while (bytesRead < totalToRead && running) {
            val readSize = minOf(CHUNK_SIZE, totalToRead - bytesRead)
            val chunk = readChunkFromMerged(fileIds, sizes, globalOffset + bytesRead, readSize, metrics)
            if (chunk == null || chunk.isEmpty()) break
            System.arraycopy(chunk, 0, buffer, bytesRead, chunk.size)
            bytesRead += chunk.size
        }
        return if (bytesRead == totalToRead) buffer else if (bytesRead > 0) buffer.copyOf(bytesRead) else null
    }

    private suspend fun streamMergedFile(
        fileIds: List<Int>,
        sizes: List<Long>,
        fileName: String?,
        rangeHeader: String?,
        output: java.io.OutputStream,
        isHead: Boolean = false
    ) {
        val isZip = fileName != null && TelegramRepository.isZipArchiveFilename(fileName)
        if (isZip) {
            streamZipEntryFromMergedOrSingle(fileIds, sizes, fileName, rangeHeader, output, isHead)
            return
        }
        streamMergedFileRaw(fileIds, sizes, fileName, rangeHeader, output, isHead)
    }

    private suspend fun streamMergedFileRaw(
        fileIds: List<Int>,
        sizes: List<Long>,
        fileName: String?,
        rangeHeader: String?,
        output: java.io.OutputStream,
        isHead: Boolean = false,
        payloadOffset: Long = 0L
    ) {
        val totalRawSize = sizes.sum()
        val effectiveSize = maxOf(0L, totalRawSize - payloadOffset)
        if (effectiveSize <= 0L || fileIds.isEmpty()) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        val primaryFileId = fileIds.first()
        val (rangeStart, rangeEnd) = parseRange(rangeHeader)
        val start: Long
        val end: Long

        if (rangeStart == null && rangeEnd != null) {
            start = maxOf(0L, effectiveSize - rangeEnd)
            end = effectiveSize - 1L
        } else {
            start = rangeStart ?: 0L
            end = rangeEnd ?: (effectiveSize - 1L)
        }
        val length = end - start + 1

        val m = StreamMetrics(
            fileId = primaryFileId,
            rangeHeader = rangeHeader,
            startOffset = start,
            totalSize = effectiveSize
        )
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        val currentJobRegisteredKeys = mutableListOf<String>()

        try {
            if (m.requestType != "seek_probe") {
                fileIds.forEach { fId ->
                    latestActiveStreamReqId[fId] = m.reqId
                    val jobKey = "file_$fId"
                    currentJobRegisteredKeys.add(jobKey)
                    if (currentJob != null) {
                        val oldJob = activeFileJobs.put(jobKey, currentJob)
                        if (oldJob != null && oldJob != currentJob && oldJob.isActive) {
                            TeleflixLogger.log(TAG, "Cancelling previous stream job for fileId=$fId due to new request $jobKey")
                            oldJob.cancel()
                        }
                    }
                }
            }
            m.logStart()

            val prefetchBytes = when {
                prefetchSizeMb == -1L -> 0L
                prefetchSizeMb <= 0L -> CHUNK_SIZE.toLong()
                else -> maxOf(CHUNK_SIZE.toLong(), prefetchSizeMb * 1024L * 1024L)
            }
            for (fId in fileIds) {
                runCatching {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fId
                        req.priority = DOWNLOAD_PRIORITY
                        req.offset = 0
                        req.limit = prefetchBytes
                        req.synchronous = false
                    })
                }
            }

            val cleanName = fileName?.removeSuffix(".zip") ?: "video.mkv"
            val ext = cleanName.substringAfterLast('.', "mkv").lowercase()
            val mimeType = getMimeType(ext)

            val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
            val safeFileName = cleanName.replace("\"", "\\\"")
            val headers = StringBuilder().apply {
                append("HTTP/1.1 $status\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (rangeHeader != null) {
                    append("Content-Range: bytes $start-$end/$effectiveSize\r\n")
                }
                append("Content-Type: $mimeType\r\n")
                append("Content-Disposition: inline; filename=\"$safeFileName\"\r\n")
                append("Connection: keep-alive\r\n")
                append("Keep-Alive: timeout=60, max=1000\r\n\r\n")
            }.toString()

            output.write(headers.toByteArray())
            output.flush()

            if (isHead) {
                m.exitReason = "head_request"
                m.logEnd()
                return
            }

            var offset = start
            while (offset <= end && running) {
                val chunkSize = minOf(CHUNK_SIZE.toLong(), end - offset + 1).toInt()
                val bytes = readChunkFromMerged(fileIds, sizes, payloadOffset + offset, chunkSize, m)
                if (bytes == null || bytes.isEmpty()) break
                output.write(bytes)
                output.flush()
                offset += bytes.size
            }
            m.logEnd()
        } finally {
            fileIds.forEach { fId ->
                activeStreamRequests[fId]?.remove(m.reqId)
            }
            currentJobRegisteredKeys.forEach { key ->
                if (activeFileJobs[key] == currentJob) {
                    activeFileJobs.remove(key)
                }
            }
        }
    }

    private suspend fun streamZipEntry(
        fileId: Int,
        innerFileName: String,
        rangeHeader: String?,
        output: java.io.OutputStream,
        zipSize: Long,
        isHead: Boolean = false
    ) {
        val totalZipSize = if (zipSize > 0L) zipSize else {
            val info = getFileInfo(fileId)
            info?.second?.takeIf { it > 0 } ?: info?.third?.takeIf { it > 0 } ?: 0L
        }
        streamZipEntryFromMergedOrSingle(listOf(fileId), listOf(totalZipSize), innerFileName, rangeHeader, output, isHead)
    }

    private suspend fun streamZipEntryFromMergedOrSingle(
        fileIds: List<Int>,
        sizes: List<Long>,
        requestedInnerName: String?,
        rangeHeader: String?,
        output: java.io.OutputStream,
        isHead: Boolean = false
    ) {
        val totalZipSize = sizes.sum()
        if (totalZipSize <= 0L || fileIds.isEmpty()) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }

        val primaryFileId = fileIds.first()
        val (initialStart, _) = parseRange(rangeHeader)
        val m = StreamMetrics(
            fileId = primaryFileId,
            rangeHeader = rangeHeader,
            startOffset = initialStart ?: 0L,
            totalSize = totalZipSize
        )
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        val currentJobRegisteredKeys = mutableListOf<String>()

        try {
            if (m.requestType != "seek_probe") {
                fileIds.forEach { fId ->
                    latestActiveStreamReqId[fId] = m.reqId
                    val jobKey = "file_$fId"
                    currentJobRegisteredKeys.add(jobKey)
                    if (currentJob != null) {
                        val oldJob = activeFileJobs.put(jobKey, currentJob)
                        if (oldJob != null && oldJob != currentJob && oldJob.isActive) {
                            TeleflixLogger.log(TAG, "Cancelling previous stream job for fileId=$fId due to new request $jobKey")
                            oldJob.cancel()
                        }
                    }
                }
            }
            m.logStart()

            val zipPrefetch = when {
                prefetchSizeMb >= 102400L || prefetchSizeMb == -1L -> 0L
                prefetchSizeMb <= 0L -> CHUNK_SIZE.toLong()
                else -> maxOf(CHUNK_SIZE.toLong(), prefetchSizeMb * 1024L * 1024L)
            }

            for (fId in fileIds) {
                runCatching {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fId
                        req.priority = DOWNLOAD_PRIORITY
                        req.offset = 0
                        req.limit = zipPrefetch
                        req.synchronous = false
                    })
                }
            }

            val eocdSearchSize = minOf(65557L, totalZipSize).toInt()
            val eocdOffset = totalZipSize - eocdSearchSize
            val eocdData = readBufferFromMerged(fileIds, sizes, eocdOffset, eocdSearchSize, m)
            
            var eocdPos = -1
            if (eocdData != null && eocdData.size >= 22) {
                for (i in eocdData.size - 22 downTo 0) {
                    if (eocdData[i] == 0x50.toByte() &&
                        eocdData[i + 1] == 0x4B.toByte() &&
                        eocdData[i + 2] == 0x05.toByte() &&
                        eocdData[i + 3] == 0x06.toByte()
                    ) {
                        eocdPos = i
                        break
                    }
                }
            }

            if (eocdData == null || eocdData.size < 22 || eocdPos < 0) {
                TeleflixLogger.log(TAG, "No valid ZIP EOCD signature found for '$requestedInnerName' - checking for Local File Header at offset 0")
                val startHeader = readBufferFromMerged(fileIds, sizes, 0L, 30, m)
                if (startHeader != null && startHeader.size >= 30 &&
                    startHeader[0] == 0x50.toByte() && startHeader[1] == 0x4B.toByte() &&
                    startHeader[2] == 0x03.toByte() && startHeader[3] == 0x04.toByte()
                ) {
                    fun readLocalUInt16(data: ByteArray, off: Int): Int =
                        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
                    val compressionMethod = readLocalUInt16(startHeader, 8)
                    val nameLen = readLocalUInt16(startHeader, 26)
                    val extraLen = readLocalUInt16(startHeader, 28)
                    val dataOffset = 30L + nameLen + extraLen
                    TeleflixLogger.log(TAG, "Found ZIP Local File Header at offset 0: dataOffset=$dataOffset, compressionMethod=$compressionMethod")
                    if (compressionMethod == 0) {
                        streamMergedFileRaw(fileIds, sizes, requestedInnerName, rangeHeader, output, isHead, dataOffset)
                        return
                    }
                }
                TeleflixLogger.log(TAG, "Falling back to raw merged file stream for '$requestedInnerName'")
                streamMergedFileRaw(fileIds, sizes, requestedInnerName, rangeHeader, output, isHead)
                return
            }

            fun readUInt16(data: ByteArray, off: Int): Int =
                (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
            fun readUInt32(data: ByteArray, off: Int): Long =
                (data[off].toInt() and 0xFF).toLong() or
                ((data[off + 1].toInt() and 0xFF).toLong() shl 8) or
                ((data[off + 2].toInt() and 0xFF).toLong() shl 16) or
                ((data[off + 3].toInt() and 0xFF).toLong() shl 24)
            fun readUInt64(data: ByteArray, off: Int): Long =
                (readUInt32(data, off) and 0xFFFFFFFFL) or ((readUInt32(data, off + 4) and 0xFFFFFFFFL) shl 32)

            var cdSize = readUInt32(eocdData, eocdPos + 12)
            var cdOffset = readUInt32(eocdData, eocdPos + 16)

            var isZip64 = false
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || eocdPos >= 20) {
                val locatorPos = eocdPos - 20
                if (locatorPos >= 0 &&
                    eocdData[locatorPos] == 0x50.toByte() &&
                    eocdData[locatorPos + 1] == 0x4B.toByte() &&
                    eocdData[locatorPos + 2] == 0x06.toByte() &&
                    eocdData[locatorPos + 3] == 0x07.toByte()
                ) {
                    val zip64EocdOffset = readUInt64(eocdData, locatorPos + 8)
                    val zip64EocdData = readBufferFromMerged(fileIds, sizes, zip64EocdOffset, 56, m)
                    if (zip64EocdData != null && zip64EocdData.size >= 56 &&
                        zip64EocdData[0] == 0x50.toByte() && zip64EocdData[1] == 0x4B.toByte() &&
                        zip64EocdData[2] == 0x06.toByte() && zip64EocdData[3] == 0x06.toByte()
                    ) {
                        cdSize = readUInt64(zip64EocdData, 40)
                        cdOffset = readUInt64(zip64EocdData, 48)
                        isZip64 = true
                    }
                }
            }

            val cdData = readBufferFromMerged(fileIds, sizes, cdOffset, cdSize.toInt(), m)
            if (cdData == null || cdData.isEmpty()) {
                output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nFailed to read Central Directory".toByteArray())
                return
            }

            data class ZipEntry(
                val name: String,
                val compressionMethod: Int,
                val compressedSize: Long,
                val uncompressedSize: Long,
                val localHeaderOffset: Long
            )

            val entries = mutableListOf<ZipEntry>()
            var pos = 0
            while (pos + 46 <= cdData.size) {
                if (cdData[pos] != 0x50.toByte() || cdData[pos + 1] != 0x4B.toByte() ||
                    cdData[pos + 2] != 0x01.toByte() || cdData[pos + 3] != 0x02.toByte()
                ) break

                val compressionMethod = readUInt16(cdData, pos + 10)
                var compressedSize = readUInt32(cdData, pos + 20)
                var uncompressedSize = readUInt32(cdData, pos + 24)
                val nameLength = readUInt16(cdData, pos + 28)
                val extraLength = readUInt16(cdData, pos + 30)
                val commentLength = readUInt16(cdData, pos + 32)
                var localHeaderOffset = readUInt32(cdData, pos + 42)

                if (pos + 46 + nameLength > cdData.size) break
                val nameBytes = cdData.copyOfRange(pos + 46, pos + 46 + nameLength)
                val entryName = String(nameBytes, Charsets.UTF_8)

                if (extraLength > 0 && pos + 46 + nameLength + extraLength <= cdData.size) {
                    var extraPos = pos + 46 + nameLength
                    val extraEnd = extraPos + extraLength
                    while (extraPos + 4 <= extraEnd) {
                        val headerId = readUInt16(cdData, extraPos)
                        val dataSize = readUInt16(cdData, extraPos + 2)
                        if (headerId == 0x0001 && extraPos + 4 + dataSize <= extraEnd) {
                            var zip64FieldPos = extraPos + 4
                            if (uncompressedSize == 0xFFFFFFFFL && zip64FieldPos + 8 <= extraEnd) {
                                uncompressedSize = readUInt64(cdData, zip64FieldPos)
                                zip64FieldPos += 8
                            }
                            if (compressedSize == 0xFFFFFFFFL && zip64FieldPos + 8 <= extraEnd) {
                                compressedSize = readUInt64(cdData, zip64FieldPos)
                                zip64FieldPos += 8
                            }
                            if (localHeaderOffset == 0xFFFFFFFFL && zip64FieldPos + 8 <= extraEnd) {
                                localHeaderOffset = readUInt64(cdData, zip64FieldPos)
                            }
                            break
                        }
                        extraPos += 4 + dataSize
                    }
                }

                if (!entryName.endsWith("/")) {
                    entries.add(ZipEntry(entryName, compressionMethod, compressedSize, uncompressedSize, localHeaderOffset))
                }
                pos += 46 + nameLength + extraLength + commentLength
            }

            if (entries.isEmpty()) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNo files found in ZIP archive".toByteArray())
                return
            }

            val mediaExtensions = setOf("mkv", "mp4", "avi", "mov", "webm", "flv", "wmv", "ts", "m2ts", "m4v", "3gp", "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a")

            var target: ZipEntry? = null

            if (!requestedInnerName.isNullOrBlank() && !TelegramRepository.isZipArchiveFilename(requestedInnerName)) {
                target = entries.find { it.name.equals(requestedInnerName, ignoreCase = true) }
                    ?: entries.find { it.name.substringAfterLast('/').equals(requestedInnerName, ignoreCase = true) }
            }

            if (target == null) {
                val mediaEntries = entries.filter { 
                    val ext = it.name.substringAfterLast('.', "").lowercase()
                    ext in mediaExtensions
                }
                target = mediaEntries.maxByOrNull { it.uncompressedSize }
                    ?: entries.maxByOrNull { it.uncompressedSize }
            }

            if (target == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNo playable entry found in ZIP".toByteArray())
                return
            }

            if (target.compressionMethod != 0) {
                output.write("HTTP/1.1 422 Unprocessable Entity\r\nContent-Type: text/plain\r\n\r\nFile '${target.name}' is compressed (method ${target.compressionMethod}). Only STORED (uncompressed) files can be streamed.".toByteArray())
                return
            }

            val localHeader = readBufferFromMerged(fileIds, sizes, target.localHeaderOffset, 30, m)
            if (localHeader == null || localHeader.size < 30) {
                output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nFailed to read local file header".toByteArray())
                return
            }
            val localNameLen = readUInt16(localHeader, 26)
            val localExtraLen = readUInt16(localHeader, 28)
            val dataOffset = target.localHeaderOffset + 30 + localNameLen + localExtraLen

            val innerFileSize = target.uncompressedSize

            Log.d(TAG, "ZIP streaming entry '${target.name}' size=$innerFileSize dataOffset=$dataOffset isZip64=$isZip64")

            val (rangeStart, rangeEnd) = parseRange(rangeHeader)
            val reqStart: Long
            val reqEnd: Long

            if (rangeStart == null && rangeEnd != null) {
                reqStart = maxOf(0L, innerFileSize - rangeEnd)
                reqEnd = innerFileSize - 1L
            } else {
                reqStart = rangeStart ?: 0L
                reqEnd = rangeEnd ?: (innerFileSize - 1L)
            }
            val length = reqEnd - reqStart + 1

            val ext = target.name.substringAfterLast('.', "").lowercase()
            val mimeType = getMimeType(ext)

            val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
            val safeFileName = target.name.replace("\"", "\\\"")
            val headers = StringBuilder().apply {
                append("HTTP/1.1 $status\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (rangeHeader != null) {
                    append("Content-Range: bytes $reqStart-$reqEnd/$innerFileSize\r\n")
                }
                append("Content-Type: $mimeType\r\n")
                append("Content-Disposition: inline; filename=\"$safeFileName\"\r\n")
                append("Connection: close\r\n\r\n")
            }.toString()

            output.write(headers.toByteArray())
            output.flush()

            if (isHead) {
                m.exitReason = "head_request"
                m.logEnd()
                return
            }

            var offset = reqStart
            while (offset <= reqEnd && running) {
                val chunkSize = minOf(CHUNK_SIZE.toLong(), reqEnd - offset + 1).toInt()
                val globalZipOffset = dataOffset + offset

                val bytes = readChunkFromMerged(fileIds, sizes, globalZipOffset, chunkSize, m)
                if (bytes == null || bytes.isEmpty()) break
                output.write(bytes)
                output.flush()
                offset += bytes.size
            }
            m.logEnd()
        } finally {
            fileIds.forEach { fId ->
                activeStreamRequests[fId]?.remove(m.reqId)
            }
            currentJobRegisteredKeys.forEach { key ->
                if (activeFileJobs[key] == currentJob) {
                    activeFileJobs.remove(key)
                }
            }
        }
    }

    fun clearStreamCache(fileId: Int) {
        if (fileId <= 0) return
        val targetId = resolveFileId(fileId)
        val fileIdsToClear = setOf(fileId, targetId).filter { it > 0 && !DownloadManager.isFileIdActive(it) }

        fileIdsToClear.forEach { fId ->
            activeFileJobs.remove("file_$fId")?.cancel()
            latestActiveStreamReqId.remove(fId)
            activeStreamRequests.remove(fId)
            activeDownloadWindows.remove(fId)
            lastDownloadRequestOffset.remove(fId)
            lastDownloadRequestTime.remove(fId)
        }

        scope.launch {
            fileIdsToClear.forEach { fId ->
                deleteFile(fId)
            }
            TeleflixLogger.log(TAG, "Auto-cleared stream cache for fileId=$fileId (targetId=$targetId)")
        }
    }

    private suspend fun deleteFile(fileId: Int) {
        if (fileId <= 0 || DownloadManager.isFileIdActive(fileId)) return
        runCatching {
            TelegramClient.sendRequest(TdApi.CancelDownloadFile().also { req ->
                req.fileId = fileId
                req.onlyIfPending = false
            })
        }
        runCatching {
            TelegramClient.sendRequest(TdApi.DeleteFile(fileId))
        }
    }

    private fun ensureRunning() {
        if (!running || serverSocket == null || port == 0) {
            start()
        }
    }

    fun refreshUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        if (!url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")) {
            return url
        }
        ensureRunning()
        var refreshed = url.replace(Regex("http://(127\\.0\\.0\\.1|localhost):[0-9]+"), "http://127.0.0.1:$port")
        if (refreshed.contains("token=")) {
            refreshed = refreshed.replace(Regex("token=[^&]+"), "token=$authToken")
        } else {
            val separator = if (refreshed.contains("?")) "&" else "?"
            refreshed += "${separator}token=$authToken"
        }
        return refreshed
    }

    fun getUrl(fileId: Int, fileName: String, expectedSize: Long = 0L, chatId: Long = 0L, messageId: Long = 0L): String {
        ensureRunning()
        if (chatId != 0L && messageId != 0L) {
            registerFileMessage(fileId, chatId, messageId)
        }
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        var url = "http://127.0.0.1:$port/file/$fileId/$encodedName?size=$expectedSize"
        if (chatId != 0L && messageId != 0L) {
            url += "&chatId=$chatId&messageId=$messageId"
        }
        return "$url&token=$authToken"
    }

    fun getThumbnailUrl(fileId: Int): String {
        ensureRunning()
        return "http://127.0.0.1:$port/thumbnail/$fileId?token=$authToken"
    }

    fun getThumbnailUrl(chatId: Long, messageId: Long): String {
        ensureRunning()
        return "http://127.0.0.1:$port/thumbnail/$chatId/$messageId?token=$authToken"
    }

    fun getMergedUrl(
        fileIds: List<Int>,
        fileName: String,
        sizes: List<Long>,
        chatIds: List<Long> = emptyList(),
        messageIds: List<Long> = emptyList()
    ): String {
        ensureRunning()
        if (chatIds.isNotEmpty() && messageIds.isNotEmpty()) {
            fileIds.forEachIndexed { i, fId ->
                val cId = chatIds.getOrNull(i) ?: 0L
                val mId = messageIds.getOrNull(i) ?: 0L
                if (cId != 0L && mId != 0L) {
                    registerFileMessage(fId, cId, mId)
                }
            }
        }
        val ids = fileIds.joinToString(",")
        val szs = sizes.joinToString(",")
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        var url = "http://127.0.0.1:$port/merged/$ids/$encodedName?sizes=$szs"
        if (chatIds.isNotEmpty() && messageIds.isNotEmpty() && chatIds.any { it != 0L }) {
            url += "&chats=${chatIds.joinToString(",")}&messages=${messageIds.joinToString(",")}"
        }
        return "$url&token=$authToken"
    }

    fun getPlaylistUrl(
        fileIds: List<Int>,
        fileName: String,
        durations: List<Int> = emptyList(),
        sizes: List<Long> = emptyList(),
        chatIds: List<Long> = emptyList(),
        messageIds: List<Long> = emptyList()
    ): String {
        ensureRunning()
        if (chatIds.size == fileIds.size && messageIds.size == fileIds.size) {
            fileIds.forEachIndexed { i, fId ->
                if (chatIds[i] != 0L && messageIds[i] != 0L) {
                    registerFileMessage(fId, chatIds[i], messageIds[i])
                }
            }
        }
        val ids = fileIds.joinToString(",")
        val durs = durations.joinToString(",")
        val szs = sizes.joinToString(",")
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        var url = "http://127.0.0.1:$port/playlist/$ids/$encodedName.m3u8?durations=$durs&sizes=$szs"
        if (chatIds.size == fileIds.size && messageIds.size == fileIds.size && chatIds.any { it != 0L }) {
            url += "&chats=${chatIds.joinToString(",")}&messages=${messageIds.joinToString(",")}"
        }
        return "$url&token=$authToken"
    }

    fun getZipStreamUrl(fileId: Int, innerFileName: String, zipSize: Long, chatId: Long = 0L, messageId: Long = 0L): String {
        ensureRunning()
        if (chatId != 0L && messageId != 0L) {
            registerFileMessage(fileId, chatId, messageId)
        }
        val encodedInner = java.net.URLEncoder.encode(innerFileName, "UTF-8").replace("+", "%20")
        var url = "http://127.0.0.1:$port/zip/$fileId/$encodedInner?size=$zipSize"
        if (chatId != 0L && messageId != 0L) {
            url += "&chatId=$chatId&messageId=$messageId"
        }
        return "$url&token=$authToken"
    }

    fun cancelAllBackgroundDownloads() {
        scope.launch {
            activeDownloadWindows.keys.forEach { fileId ->
                val hasActiveRequests = activeStreamRequests[fileId]?.isNotEmpty() == true
                if (!hasActiveRequests && !DownloadManager.isFileIdActive(fileId)) {
                    runCatching {
                        TelegramClient.sendRequest(TdApi.CancelDownloadFile(fileId, false))
                    }
                    activeDownloadWindows.remove(fileId)
                }
            }
            TeleflixLogger.log(TAG, "Cancelled idle TDLib background downloads")
        }
    }

    private suspend fun serveThumbnail(fileId: Int, output: java.io.OutputStream, isHead: Boolean = false) {
        if (fileId <= 0) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        // 1. Check RAM LRU Cache (Instant 0.1ms response)
        val cachedBytes = thumbnailMemoryCache.get(fileId)
        if (cachedBytes != null) {
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${cachedBytes.size}\r\nConnection: keep-alive\r\n\r\n"
            output.write(headers.toByteArray())
            output.flush()
            if (!isHead) {
                output.write(cachedBytes)
                output.flush()
            }
            return
        }

        // 2. Check local disk path from TDLib (Instant 1ms response)
        val fileInfo = getFileInfo(fileId)
        val localPath = fileInfo?.first
        val expectedSize = fileInfo?.second ?: 0L

        // Safety check: Thumbnail files should never be > 5 MB (videos/documents are large files)
        if (expectedSize > 5 * 1024 * 1024L) {
            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        if (localPath != null && localPath.isNotBlank()) {
            val file = java.io.File(localPath)
            if (file.exists() && file.length() in 1..(5 * 1024 * 1024L)) {
                val length = file.length()
                if (length <= 2 * 1024 * 1024L) {
                    runCatching { thumbnailMemoryCache.put(fileId, file.readBytes()) }
                }
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\nConnection: keep-alive\r\n\r\n"
                output.write(headers.toByteArray())
                output.flush()
                if (!isHead) {
                    file.inputStream().use { input -> input.copyTo(output, bufferSize = 64 * 1024) }
                    output.flush()
                }
                return
            }
        }

        // 3. Trigger capped-limit thumbnail download (512 KB max) instead of 0 (unlimited EOF)
        val thumbLimit = if (expectedSize in 1..5_242_880L) expectedSize else 512 * 1024L
        runCatching {
            TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                req.fileId = fileId
                req.priority = 8
                req.offset = 0
                req.limit = thumbLimit
                req.synchronous = false
            })
        }

        var downloadedFile: java.io.File? = null
        var attempts = 0
        while (attempts < 40 && running) {
            val f = try { TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File } catch (_: Exception) { null }
            if (f?.local?.isDownloadingCompleted == true && !f.local.path.isNullOrBlank()) {
                val diskFile = java.io.File(f.local.path)
                if (diskFile.exists() && diskFile.length() in 1..(5 * 1024 * 1024L)) {
                    downloadedFile = diskFile
                    break
                } else if (!diskFile.exists() && attempts == 0) {
                    runCatching { TelegramClient.sendRequest(TdApi.DeleteFile(fileId)) }
                }
            }
            delay(50L)
            attempts++
        }

        try {
            if (downloadedFile != null) {
                val length = downloadedFile.length()
                if (length <= 2 * 1024 * 1024L) {
                    runCatching { thumbnailMemoryCache.put(fileId, downloadedFile.readBytes()) }
                }
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\nConnection: keep-alive\r\n\r\n"
                output.write(headers.toByteArray())
                output.flush()
                if (!isHead) {
                    downloadedFile.inputStream().use { input -> input.copyTo(output, bufferSize = 64 * 1024) }
                    output.flush()
                }
            } else {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                output.flush()
            }
        } finally {
            if (!DownloadManager.isFileIdActive(fileId)) {
                runCatching { TelegramClient.sendRequest(TdApi.CancelDownloadFile(fileId, false)) }
            }
        }
    }

    private suspend fun downloadChunk(
        fileId: Int,
        offset: Long,
        limit: Int,
        metrics: StreamMetrics? = null
    ): ByteArray? {
        var activeFileId = resolveFileId(fileId)
        val chunkStartMs = System.currentTimeMillis()
        val timeoutMs = DOWNLOAD_TIMEOUT_MS
        var isFileNotFound = false
        val dataBytes = withTimeoutOrNull(timeoutMs) {
            var attempts = 0
            var consecutiveGetFileErrors = 0
            while (attempts < 2000 && running) {
                activeFileId = resolveFileId(activeFileId)
                val readRes = try {
                    TelegramClient.sendRequest(
                        TdApi.ReadFilePart(activeFileId, offset, limit.toLong())
                    )
                } catch (e: Exception) {
                    null
                }
                
                if (readRes is TdApi.Data && readRes.data.isNotEmpty()) {
                    val tdlibMs = System.currentTimeMillis() - chunkStartMs
                    metrics?.chunksOk = (metrics?.chunksOk ?: 0) + 1
                    val count = metrics?.chunksOk ?: 1
                    if (tdlibMs > 500L || count % 10 == 0 || count == 1) {
                        TeleflixLogger.log(TAG, "[TDLib] chunk #$count fileId=$activeFileId offset=$offset size=${readRes.data.size} tdlibMs=$tdlibMs status=ok")
                    }
                    return@withTimeoutOrNull readRes.data
                }
                
                val file = try {
                    val res = TelegramClient.sendRequest(TdApi.GetFile(activeFileId)) as? TdApi.File
                    if (res != null) consecutiveGetFileErrors = 0
                    res
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    val isNotFoundErr = msg.contains("File not found", ignoreCase = true) || msg.contains("400", ignoreCase = true)
                    if (isNotFoundErr) {
                        val refreshed = refreshFileId(activeFileId)
                        if (refreshed != null && refreshed != 0) {
                            activeFileId = refreshed
                            consecutiveGetFileErrors = 0
                        } else {
                            consecutiveGetFileErrors++
                        }
                    } else {
                        consecutiveGetFileErrors++
                    }
                    null
                }

                val fileMsgRef = fileToMessageMap[activeFileId] ?: fileToMessageMap[fileId]
                if (consecutiveGetFileErrors >= 3 && fileMsgRef == null) {
                    TeleflixLogger.log(TAG, "fileId=$activeFileId invalid or not found in TDLib and no message reference available, failing fast", isError = true)
                    isFileNotFound = true
                    if (metrics != null) {
                        metrics.exitReason = "file_not_found"
                    }
                    return@withTimeoutOrNull null
                }

                if (file != null) {
                    val localPath = file.local?.path
                    if (!localPath.isNullOrBlank()) {
                        val localFile = java.io.File(localPath)
                        if (localFile.exists() && localFile.length() > offset) {
                            try {
                                java.io.RandomAccessFile(localFile, "r").use { raf ->
                                    raf.seek(offset)
                                    val available = localFile.length() - offset
                                    val readLen = minOf(limit.toLong(), available).toInt()
                                    if (readLen > 0) {
                                        val buf = ByteArray(readLen)
                                        val actualRead = raf.read(buf)
                                        if (actualRead > 0) {
                                            val tdlibMs = System.currentTimeMillis() - chunkStartMs
                                            metrics?.chunksOk = (metrics?.chunksOk ?: 0) + 1
                                            val count = metrics?.chunksOk ?: 1
                                            if (tdlibMs > 500L || count % 10 == 0 || count == 1) {
                                                TeleflixLogger.log(TAG, "[Disk] chunk #$count fileId=$activeFileId offset=$offset size=$actualRead tdlibMs=$tdlibMs status=ok")
                                            }
                                            return@withTimeoutOrNull if (actualRead == readLen) buf else buf.copyOf(actualRead)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                TeleflixLogger.log(TAG, "Direct disk read error for fileId=$activeFileId: ${e.message}")
                            }
                        }
                    }

                    if (file.local?.isDownloadingCompleted == true && (localPath.isNullOrBlank() || !java.io.File(localPath).exists())) {
                        if (attempts == 0) {
                            TeleflixLogger.log(TAG, "Local file for fileId=$activeFileId missing from disk, resetting local state via DeleteFile to stream from Telegram Cloud")
                            runCatching { TelegramClient.sendRequest(TdApi.DeleteFile(activeFileId)) }
                        }
                    }

                    // If download is inactive and not complete, immediately kick off download without waiting 5 seconds
                    if (file.local?.isDownloadingActive == false && file.local?.isDownloadingCompleted == false && file.local?.canBeDownloaded == true) {
                        if (attempts % 20 == 0) {
                            TeleflixLogger.log(TAG, "File fileId=$activeFileId download inactive (attempts=$attempts), triggering DownloadFile...")
                            triggerTdlibDownload(activeFileId, offset, limit.toLong(), force = true)
                        }
                    }
                }

                // If stall detected after 40 attempts (2 seconds of no data), refresh message & re-elevate TDLib priority
                val isStalled = attempts >= 40 && attempts % 40 == 0
                if (isStalled) {
                    metrics?.chunksRetried = (metrics?.chunksRetried ?: 0) + 1
                    TeleflixLogger.log(TAG, "downloadChunk stall check for fileId=$activeFileId offset=$offset at attempt $attempts. Refreshing message location and elevating TDLib priority...")
                    val refreshed = refreshFileId(activeFileId, force = true)
                    if (refreshed != null && refreshed != 0) {
                        activeFileId = refreshed
                    }
                    triggerTdlibDownload(activeFileId, offset, limit.toLong(), force = true)
                }
                
                delay(50L)
                attempts++
            }
            null
        }
        if (dataBytes == null && metrics?.exitReason != "superseded") {
            if (metrics?.exitReason == "file_not_found" || isFileNotFound) {
                TeleflixLogger.log(TAG, "downloadChunk FAILED: fileId=$activeFileId invalid or not found in TDLib (offset=$offset, limit=$limit)", isError = true)
            } else {
                metrics?.chunksTimedOut = (metrics?.chunksTimedOut ?: 0) + 1
                TeleflixLogger.log(TAG, "downloadChunk TIMEOUT: fileId=$activeFileId offset=$offset limit=$limit after ${timeoutMs}ms", isError = true)
            }
        }
        return dataBytes
    }

    private suspend fun getFileInfo(fileId: Int): Triple<String?, Long, Long>? {
        val file = try {
            TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
        } catch (e: Exception) {
            null
        } ?: return null
        val localPath = file.local?.path?.takeIf { it.isNotBlank() }
        return Triple(localPath, file.size, file.expectedSize)
    }

    private fun parseRange(header: String?): Pair<Long?, Long?> {
        if (header == null) return Pair(null, null)
        return try {
            val range = header.removePrefix("bytes=")
            val parts = range.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            Pair(start, end)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun isCacheEnabled(): Boolean {
        return false // Video and audio streaming cache is disabled by design; files are purged automatically
    }

    private fun getMimeType(ext: String): String = when (ext) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "ts", "m2ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        "m4a" -> "audio/mp4"
        "aiff" -> "audio/aiff"
        else -> "video/mp4"
    }
}
