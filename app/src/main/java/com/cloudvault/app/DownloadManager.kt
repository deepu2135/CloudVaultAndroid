package com.cloudvault.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

data class VaultDownloadTask(
    val fileId: Int,
    val fileName: String,
    val progressPercent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val isCompleted: Boolean,
    val savePath: String = ""
)

object DownloadManager {
    private const val TAG = "DownloadManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadTasks = MutableStateFlow<Map<Int, VaultDownloadTask>>(emptyMap())
    val downloadTasks: StateFlow<Map<Int, VaultDownloadTask>> = _downloadTasks.asStateFlow()

    fun isFileIdActive(fileId: Int): Boolean {
        val task = _downloadTasks.value[fileId]
        return task != null && !task.isCompleted
    }

    suspend fun downloadItem(context: Context, item: VaultMediaItem): File? = withContext(Dispatchers.IO) {
        val fileId = item.fileId
        if (fileId == 0) return@withContext null

        try {
            var file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
            if (file == null || !file.local.isDownloadingCompleted || file.local.path.isBlank() || !File(file.local.path).exists()) {
                TelegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))

                var isDone = false
                var attempts = 0
                while (!isDone && attempts < 1200) {
                    delay(400)
                    file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File ?: break
                    val downloaded = file.local.downloadedSize.toLong()
                    val total = if (file.expectedSize > 0) file.expectedSize.toLong() else file.size.toLong()
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0

                    isDone = file.local.isDownloadingCompleted

                    val task = VaultDownloadTask(
                        fileId = fileId,
                        fileName = item.title,
                        progressPercent = percent,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        isCompleted = isDone,
                        savePath = file.local.path
                    )
                    val updated = _downloadTasks.value.toMutableMap()
                    updated[fileId] = task
                    _downloadTasks.value = updated
                    attempts++
                }
            }

            if (file != null && file.local.isDownloadingCompleted && File(file.local.path).exists()) {
                val source = File(file.local.path)
                val targetDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CloudVault"
                ).apply { if (!exists()) mkdirs() }

                var targetFile = File(targetDir, item.title)
                if (targetFile.exists() && targetFile.length() != source.length()) {
                    val nameWithoutExt = item.title.substringBeforeLast('.', item.title)
                    val ext = item.title.substringAfterLast('.', "")
                    val extSuffix = if (ext.isNotBlank()) ".$ext" else ""
                    targetFile = File(targetDir, "${nameWithoutExt}_${System.currentTimeMillis()}$extSuffix")
                }

                source.copyTo(targetFile, overwrite = true)

                // Scan with MediaScannerConnection so it appears in device Gallery and Downloads
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    arrayOf(targetFile.absolutePath),
                    if (item.mimeType.isNotBlank()) arrayOf(item.mimeType) else null,
                    null
                )

                Log.d(TAG, "Saved downloaded file to ${targetFile.absolutePath}")
                return@withContext targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${item.title}", e)
        }
        null
    }

    fun startDownload(context: Context, item: VaultMediaItem) {
        val appContext = context.applicationContext
        scope.launch {
            downloadItem(appContext, item)
        }
    }

    fun startBatchDownload(
        context: Context,
        items: List<VaultMediaItem>,
        onProgress: ((current: Int, total: Int, currentName: String) -> Unit)? = null,
        onComplete: ((successCount: Int, total: Int) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val total = items.size
            var successCount = 0
            for ((index, item) in items.withIndex()) {
                val current = index + 1
                onProgress?.invoke(current, total, item.title)
                val dest = downloadItem(appContext, item)
                if (dest != null && dest.exists()) {
                    successCount++
                }
            }
            onComplete?.invoke(successCount, total)
        }
    }
}
