package com.cloudvault.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object TelegramStreamingProxy {
    private const val TAG = "TelegramStreamingProxy"
    private const val CHUNK_SIZE = 512 * 1024 // 512 KB

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    var port: Int = 0
        private set

    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(0)
            port = serverSocket?.localPort ?: 8080
            isRunning = true
            Log.d(TAG, "Local Range Streaming Proxy started on port $port")

            thread(name = "CloudVaultProxyThread") {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        scope.launch { handleClientSocket(clientSocket) }
                    } catch (e: IOException) {
                        if (!isRunning) break
                        Log.e(TAG, "Socket accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming proxy", e)
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = input.bufferedReader()

            val requestLine = reader.readLine() ?: return
            var rangeHeader: String? = null

            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substring(6).trim()
                }
                line = reader.readLine()
            }

            // Extract file_id from query: GET /stream?file_id=123 HTTP/1.1
            val fileIdStr = requestLine.split(" ").getOrNull(1)?.let { uri ->
                android.net.Uri.parse(uri).getQueryParameter("file_id")
            }

            val fileId = fileIdStr?.toIntOrNull()
            if (fileId == null || fileId == 0) {
                val resp = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
                output.write(resp.toByteArray())
                output.flush()
                socket.close()
                return
            }

            // Fetch TDLib file info
            val tdFile = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as TdApi.File
            val totalSize = tdFile.expectedSize.toLong().let { if (it <= 0) tdFile.size.toLong() else it }

            var startByte = 0L
            var endByte = totalSize - 1

            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val parts = rangeHeader.substring(6).split("-")
                startByte = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                endByte = parts.getOrNull(1)?.toLongOrNull() ?: (totalSize - 1)
            }

            val contentLength = (endByte - startByte + 1).coerceAtLeast(0L)

            val headers = if (!rangeHeader.isNullOrBlank()) {
                "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: video/mp4\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Range: bytes $startByte-$endByte/$totalSize\r\n" +
                "Content-Length: $contentLength\r\n\r\n"
            } else {
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mp4\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Length: $totalSize\r\n\r\n"
            }

            output.write(headers.toByteArray())
            output.flush()

            // Request TDLib file range download
            TelegramClient.sendRequest(
                TdApi.DownloadFile(fileId, 32, startByte, CHUNK_SIZE.toLong(), false)
            )

            // Dynamic stream loop
            var currentPos = startByte
            while (currentPos <= endByte && isRunning && !socket.isClosed) {
                var fileInfo = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as TdApi.File
                val localPath = fileInfo.local.path

                if (localPath.isNotBlank() && java.io.File(localPath).exists()) {
                    val file = java.io.File(localPath)
                    val availableBytes = file.length()

                    if (availableBytes > currentPos) {
                        val bytesToRead = Math.min(CHUNK_SIZE.toLong(), availableBytes - currentPos)
                        file.inputStream().use { fileInput ->
                            fileInput.skip(currentPos)
                            val buffer = ByteArray(bytesToRead.toInt())
                            val read = fileInput.read(buffer)
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                output.flush()
                                currentPos += read
                            }
                        }
                    } else {
                        // Request next chunk download
                        TelegramClient.sendRequest(
                            TdApi.DownloadFile(fileId, 32, currentPos, CHUNK_SIZE.toLong(), false)
                        )
                        delay(100)
                    }
                } else {
                    delay(100)
                }
            }

        } catch (e: Exception) {
            Log.d(TAG, "Stream connection closed or interrupted: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
