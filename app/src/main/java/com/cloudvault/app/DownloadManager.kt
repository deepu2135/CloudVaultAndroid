package com.cloudvault.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun startDownload(context: Context, item: VaultMediaItem) {
        val fileId = item.fileId
        if (fileId == 0) return

        scope.launch {
            try {
                TelegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
                
                var isDone = false
                while (!isDone) {
                    val file = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as TdApi.File
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

                    if (!isDone) kotlinx.coroutines.delay(500)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${item.title}", e)
            }
        }
    }
}
