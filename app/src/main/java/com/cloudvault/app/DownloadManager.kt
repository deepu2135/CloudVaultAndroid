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

    suspend fun downloadItem(context: Context, item: VaultMediaItem, showNotification: Boolean = true): File? = withContext(Dispatchers.IO) {
        val fileId = item.fileId
        if (fileId == 0) return@withContext null
        val appContext = context.applicationContext

        try {
            if (showNotification) {
                DownloadNotificationManager.showDownloadProgress(
                    appContext,
                    fileId,
                    "Downloading ${item.title}",
                    0,
                    100,
                    "Starting download..."
                )
            }

            var file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
            if (file == null || !file.local.isDownloadingCompleted || file.local.path.isBlank() || !File(file.local.path).exists()) {
                if (file != null && (file.local.isDownloadingCompleted || file.local.path.isNotBlank()) && (file.local.path.isBlank() || !File(file.local.path).exists())) {
                    runCatching { TelegramClient.sendRequest(TdApi.DeleteFile(fileId)) }
                }
                TelegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))

                var isDone = false
                var attempts = 0
                while (!isDone && attempts < 1200) {
                    delay(350)
                    file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File ?: break
                    val downloaded = file.local.downloadedSize.toLong()
                    val total = if (file.expectedSize > 0) file.expectedSize.toLong() else file.size.toLong()
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0

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

                    if (showNotification) {
                        val progressText = if (total > 0) {
                            "${CacheManager.formatBytes(downloaded)} of ${CacheManager.formatBytes(total)} ($percent%)"
                        } else {
                            "${CacheManager.formatBytes(downloaded)} downloaded"
                        }
                        DownloadNotificationManager.showDownloadProgress(
                            appContext,
                            fileId,
                            "Downloading ${item.title}",
                            percent,
                            100,
                            progressText
                        )
                    }

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
                    appContext,
                    arrayOf(targetFile.absolutePath),
                    if (item.mimeType.isNotBlank()) arrayOf(item.mimeType) else null,
                    null
                )

                if (showNotification) {
                    DownloadNotificationManager.showDownloadComplete(
                        appContext,
                        fileId,
                        "Download Complete 📁",
                        "Saved ${item.title} to Downloads/CloudVault",
                        targetFile,
                        item.mimeType
                    )
                }

                Log.d(TAG, "Saved downloaded file to ${targetFile.absolutePath}")
                return@withContext targetFile
            } else {
                if (showNotification) {
                    DownloadNotificationManager.showDownloadError(
                        appContext,
                        fileId,
                        "Download Failed",
                        "Could not download ${item.title}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${item.title}", e)
            if (showNotification) {
                DownloadNotificationManager.showDownloadError(
                    appContext,
                    fileId,
                    "Download Error",
                    e.message ?: "Failed downloading ${item.title}"
                )
            }
        }
        null
    }

    fun startDownload(context: Context, item: VaultMediaItem) {
        val appContext = context.applicationContext
        scope.launch {
            downloadItem(appContext, item, showNotification = true)
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
                DownloadNotificationManager.showDownloadProgress(
                    appContext,
                    DownloadNotificationManager.DEFAULT_NOTIFICATION_ID,
                    "Downloading from CloudVault ⬇️",
                    current,
                    total,
                    "($current/$total) ${item.title}"
                )
                onProgress?.invoke(current, total, item.title)
                val dest = downloadItem(appContext, item, showNotification = false)
                if (dest != null && dest.exists()) {
                    successCount++
                }
            }
            if (successCount > 0) {
                DownloadNotificationManager.showDownloadComplete(
                    appContext,
                    DownloadNotificationManager.DEFAULT_NOTIFICATION_ID,
                    "Batch Download Complete 📁",
                    "Saved $successCount of $total item(s) to Downloads/CloudVault"
                )
            } else {
                DownloadNotificationManager.showDownloadError(
                    appContext,
                    DownloadNotificationManager.DEFAULT_NOTIFICATION_ID,
                    "Download Failed",
                    "Could not download items"
                )
            }
            onComplete?.invoke(successCount, total)
        }
    }
}
