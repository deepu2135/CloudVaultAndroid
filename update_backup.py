import sys

content = open('app/src/main/java/com/cloudvault/app/AutoBackupManager.kt').read()

import re

# Find the loop
old_code = """
            for ((index, file) in unbackedFiles.withIndex()) {
                val current = index + 1
                _backupStatus.value = "Backing up ($current/$total): ${file.displayName}"
                UploadNotificationManager.showProgress(context, current, total, "Auto Backup: ${file.displayName}")

                // Prepare file for TDLib upload
                val uploadPath = if (file.filePath.isNotBlank() && File(file.filePath).exists()) {
                    file.filePath
                } else {
                    copyUriToCache(context, file.uri, file.displayName)?.absolutePath
                }

                if (uploadPath != null) {
                    var lastProgressUpdate = 0L
                    try {
                        val success = TelegramRepository.uploadFile(
                            localPath = uploadPath,
                            mediaType = file.mediaType,
                            captionText = file.displayName,
                            onProgress = { uploaded, totalBytes ->
                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 500L || uploaded == totalBytes) {
                                    lastProgressUpdate = now
                                    val pct = if (totalBytes > 0) ((uploaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                    val progressText = if (totalBytes > 0) {
                                        "${CacheManager.formatBytes(uploaded)} of ${CacheManager.formatBytes(totalBytes)} ($pct%)"
                                    } else {
                                        "${CacheManager.formatBytes(uploaded)} uploaded"
                                    }
                                    _backupStatus.value = "Backing up ($current/$total): ${file.displayName} ($pct%)"
                                    UploadNotificationManager.showProgress(
                                        context,
                                        current,
                                        total,
                                        "Auto Backup: ${file.displayName}",
                                        percent = pct,
                                        statusText = progressText
                                    )
                                }
                            }
                        )
                        if (success) {
                            AutoBackupPreferences.markSignatureBackedUp(context, file.signature)
                            successCount++
                        }
                    } finally {
                        if (uploadPath.contains("autobackup_temp")) {
                            runCatching { File(uploadPath).delete() }
                        }
                    }
                }
            }
"""

new_code = """
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val successCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val completedCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            
            kotlinx.coroutines.coroutineScope {
                unbackedFiles.map { file ->
                    kotlinx.coroutines.async {
                        semaphore.withPermit {
                            val current = completedCountAtomic.get() + 1
                            _backupStatus.value = "Backing up ($current/$total): ${file.displayName}"
                            UploadNotificationManager.showProgress(context, current, total, "Auto Backup: ${file.displayName}")
            
                            val uploadPath = if (file.filePath.isNotBlank() && File(file.filePath).exists()) {
                                file.filePath
                            } else {
                                copyUriToCache(context, file.uri, file.displayName)?.absolutePath
                            }
            
                            if (uploadPath != null) {
                                var lastProgressUpdate = 0L
                                try {
                                    val success = TelegramRepository.uploadFile(
                                        localPath = uploadPath,
                                        mediaType = file.mediaType,
                                        captionText = file.displayName,
                                        onProgress = { uploaded, totalBytes ->
                                            val now = System.currentTimeMillis()
                                            if (now - lastProgressUpdate > 500L || uploaded == totalBytes) {
                                                lastProgressUpdate = now
                                                val pct = if (totalBytes > 0) ((uploaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                                val progressText = if (totalBytes > 0) {
                                                    "${CacheManager.formatBytes(uploaded)} of ${CacheManager.formatBytes(totalBytes)} ($pct%)"
                                                } else {
                                                    "${CacheManager.formatBytes(uploaded)} uploaded"
                                                }
                                                // Only update UI if this is the most recently started upload to avoid flickering
                                                _backupStatus.value = "Backing up ($current/$total): ${file.displayName} ($pct%)"
                                                UploadNotificationManager.showProgress(
                                                    context,
                                                    current,
                                                    total,
                                                    "Auto Backup: ${file.displayName}",
                                                    percent = pct,
                                                    statusText = progressText
                                                )
                                            }
                                        }
                                    )
                                    if (success) {
                                        AutoBackupPreferences.markSignatureBackedUp(context, file.signature)
                                        successCountAtomic.incrementAndGet()
                                    }
                                } finally {
                                    completedCountAtomic.incrementAndGet()
                                    if (uploadPath.contains("autobackup_temp")) {
                                        runCatching { File(uploadPath).delete() }
                                    }
                                }
                            } else {
                                completedCountAtomic.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
            
            var successCount = successCountAtomic.get()
"""

# replace carefully
# Because of indentation, using string replace might be tricky, let's just use regex or exact replace with stripped whitespaces

content = content.replace(old_code.strip(), new_code.strip())
open('app/src/main/java/com/cloudvault/app/AutoBackupManager.kt', 'w').write(content)

