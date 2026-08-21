package com.cloudvault.app

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AutoBackupManager {

    private const val TAG = "AutoBackupManager"
    private const val WORK_NAME = "cloudvault_periodic_auto_backup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isSyncing = AtomicBoolean(false)
    private val inFlightSignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _backupStatus = MutableStateFlow("Idle")
    val backupStatus: StateFlow<String> = _backupStatus

    private var contentObserver: ContentObserver? = null
    private var debounceJob: Job? = null

    fun initialize(context: Context) {
        if (AutoBackupPreferences.isEnabled(context)) {
            schedulePeriodicWorker(context)
            startRealtimeObserver(context)
        }
    }

    fun enableAutoBackup(context: Context, enabled: Boolean) {
        AutoBackupPreferences.setEnabled(context, enabled)
        if (enabled) {
            schedulePeriodicWorker(context)
            startRealtimeObserver(context)
            triggerImmediateSync(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            stopRealtimeObserver(context)
            _backupStatus.value = "Auto Backup disabled"
        }
    }

    fun startRealtimeObserver(context: Context) {
        if (contentObserver != null) return
        val appContext = context.applicationContext

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Media change observed: $uri")
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(3000L) // Wait 3 seconds after camera finishes saving file
                    triggerImmediateSync(appContext)
                }
            }
        }

        try {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register ContentObserver", e)
        }
    }

    fun stopRealtimeObserver(context: Context) {
        contentObserver?.let {
            try {
                context.applicationContext.contentResolver.unregisterContentObserver(it)
            } catch (_: Throwable) {}
        }
        contentObserver = null
    }

    fun schedulePeriodicWorker(context: Context) {
        val isWifiOnly = AutoBackupPreferences.isWifiOnly(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun triggerImmediateSync(context: Context) {
        scope.launch {
            performBackupSync(context.applicationContext)
        }
    }

    suspend fun performBackupSync(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!AutoBackupPreferences.isEnabled(context)) return@withContext true
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "AutoBackup already in progress")
            return@withContext true
        }

        try {
            // Check network constraint
            if (AutoBackupPreferences.isWifiOnly(context) && !isWifiConnected(context)) {
                _backupStatus.value = "Waiting for Wi-Fi to back up"
                return@withContext true
            }

            // Ensure cloud vault items are loaded before scanning so we don't re-upload existing cloud media
            if (TelegramClient.authState.value is TelegramAuthState.Ready) {
                if (TelegramRepository.photos.value.isEmpty() && TelegramRepository.videos.value.isEmpty() && TelegramRepository.files.value.isEmpty()) {
                    _backupStatus.value = "Syncing with Telegram Cloud..."
                    try {
                        TelegramRepository.loadVaultItems()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not load vault items prior to backup", e)
                    }
                }
            }

            _backupStatus.value = "Scanning for new media..."
            val unbackedFiles = scanUnbackedMedia(context)

            if (unbackedFiles.isEmpty()) {
                _backupStatus.value = "All photos & videos backed up ☁️"
                AutoBackupPreferences.setLastBackupTime(context, System.currentTimeMillis())
                return@withContext true
            }

            val total = unbackedFiles.size
            var successCount = 0
            _backupStatus.value = "Backing up $total new item(s)..."

            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            val successCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val completedCountAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            
            coroutineScope {
                unbackedFiles.map { file ->
                    async {
                        semaphore.withPermit {
                            // Skip if another worker already claimed this signature
                            if (!inFlightSignatures.add(file.signature)) {
                                completedCountAtomic.incrementAndGet()
                                return@withPermit
                            }
                            // Skip video if video backup setting is disabled
                            if (!AutoBackupPreferences.isBackupVideosEnabled(context) && file.mediaType == MediaType.VIDEO) {
                                inFlightSignatures.remove(file.signature)
                                completedCountAtomic.incrementAndGet()
                                return@withPermit
                            }

                            // Double-check signature wasn't marked backed up while waiting for semaphore
                            if (AutoBackupPreferences.isBackedUp(context, file)) {
                                inFlightSignatures.remove(file.signature)
                                completedCountAtomic.incrementAndGet()
                                return@withPermit
                            }
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
                                        AutoBackupPreferences.markFileBackedUp(context, file)
                                        successCountAtomic.incrementAndGet()
                                    }
                                } finally {
                                    inFlightSignatures.remove(file.signature)
                                    completedCountAtomic.incrementAndGet()
                                    if (uploadPath.contains("autobackup_temp")) {
                                        runCatching { File(uploadPath).delete() }
                                    }
                                }
                            } else {
                                completedCountAtomic.incrementAndGet()
                            }
                            Unit
                        }
                    }
                }.awaitAll()
            }
            
            successCount = successCountAtomic.get()

            AutoBackupPreferences.setLastBackupTime(context, System.currentTimeMillis())

            if (successCount > 0) {
                UploadNotificationManager.showComplete(context, successCount, total)
                _backupStatus.value = "Backed up $successCount item(s) to Telegram Cloud ☁️"
                TelegramRepository.loadVaultItems(force = true)
            } else {
                _backupStatus.value = "Auto Backup encountered issues"
            }

            true
        } catch (e: Throwable) {
            TeleflixLogger.log(TAG, "performBackupSync error: ${e.message}", isError = true)
            _backupStatus.value = "Backup error: ${e.message}"
            false
        } finally {
            isSyncing.set(false)
        }
    }

    fun scanAvailableFolders(context: Context): List<DeviceFolderInfo> {
        val folderMap = mutableMapOf<String, Pair<String, Int>>() // bucketId -> (bucketName, count)
        val selectedBucketIds = AutoBackupPreferences.getSelectedBucketIds(context)

        fun queryMediaStore(uri: Uri, selection: String? = null, selectionArgs: Array<String>? = null) {
            val projection = arrayOf(
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol) ?: "default" else "default"
                    val bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) ?: "Storage" else "Storage"

                    val current = folderMap[bucketId]
                    val currentCount = current?.second ?: 0
                    folderMap[bucketId] = Pair(bucketName, currentCount + 1)
                }
            }
        }

        try {
            queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            
            val docSelection = "${MediaStore.MediaColumns.MIME_TYPE} IN (?, ?, ?, ?, ?, ?)"
            val docArgs = arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain"
            )
            queryMediaStore(MediaStore.Files.getContentUri("external"), docSelection, docArgs)
        } catch (e: Throwable) {
            Log.e(TAG, "scanAvailableFolders query error", e)
        }

        return folderMap.map { (bucketId, pair) ->
            val isSelected = selectedBucketIds?.contains(bucketId) ?: true // Default to true if unconfigured
            DeviceFolderInfo(
                bucketId = bucketId,
                bucketName = pair.first,
                totalCount = pair.second,
                isSelected = isSelected
            )
        }.sortedByDescending { it.totalCount }
    }

    fun scanUnbackedMedia(context: Context): List<LocalMediaFile> {
        val selectedBucketIds = AutoBackupPreferences.getSelectedBucketIds(context)
        val unbackedList = mutableListOf<LocalMediaFile>()
        val seenSignatures = mutableSetOf<String>()

        // Get cloud vault items to prevent duplicate uploads if already in Telegram
        val cloudVaultSizes = mutableSetOf<Long>()
        val cloudVaultNames = mutableSetOf<String>()
        val cloudVaultNamesWithoutExt = mutableSetOf<String>()

        val allCloudItems = TelegramRepository.photos.value + TelegramRepository.videos.value + TelegramRepository.audios.value + TelegramRepository.files.value
        allCloudItems.forEach { item ->
            if (item.sizeBytes > 0) cloudVaultSizes.add(item.sizeBytes)
            val titleClean = item.title.lowercase().trim()
            if (titleClean.isNotBlank()) {
                cloudVaultNames.add(titleClean)
                cloudVaultNamesWithoutExt.add(titleClean.substringBeforeLast(".", titleClean))
            }
            val captionClean = item.caption.lowercase().trim()
            if (captionClean.isNotBlank()) {
                cloudVaultNames.add(captionClean)
                cloudVaultNamesWithoutExt.add(captionClean.substringBeforeLast(".", captionClean))
            }
        }

        fun queryMedia(uri: Uri, mediaType: MediaType, selection: String? = null, selectionArgs: Array<String>? = null) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            )

            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "media_${id}" else "media_${id}"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol) ?: "" else ""
                    val bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) ?: "Storage" else "Storage"

                    // If user configured specific folders, filter by selected bucket IDs
                    if (selectedBucketIds != null && !selectedBucketIds.contains(bucketId)) {
                        continue
                    }

                    val itemUri = ContentUris.withAppendedId(uri, id)
                    val localFile = LocalMediaFile(
                        id = id,
                        uri = itemUri,
                        filePath = path,
                        displayName = name,
                        sizeBytes = size,
                        dateModified = date,
                        mediaType = mediaType,
                        bucketId = bucketId,
                        bucketName = bucketName
                    )

                    // 1. Check local backup signature / memory cache
                    if (AutoBackupPreferences.isBackedUp(context, localFile)) {
                        continue
                    }

                    val nameLower = localFile.displayName.lowercase().trim()
                    val nameNoExt = localFile.displayNameWithoutExt.lowercase().trim()

                    // 2. Check if already uploaded to Telegram Cloud (matching name, base name, caption, or size)
                    val isNameInCloud = (nameLower.isNotBlank() && cloudVaultNames.contains(nameLower)) ||
                            (nameNoExt.isNotBlank() && cloudVaultNamesWithoutExt.contains(nameNoExt))
                    val isSizeInCloud = localFile.sizeBytes > 0 && cloudVaultSizes.contains(localFile.sizeBytes)

                    if (isNameInCloud || (isSizeInCloud && (localFile.mediaType != MediaType.PHOTO || cloudVaultNames.any { it.contains(nameNoExt) }))) {
                        AutoBackupPreferences.markFileBackedUp(context, localFile)
                        continue
                    }

                    // 3. Skip if we've already seen this signature in this scan
                    if (!seenSignatures.add(localFile.signature)) {
                        continue
                    }

                    unbackedList.add(localFile)
                }
            }
        }

        val backupVideos = AutoBackupPreferences.isBackupVideosEnabled(context)
        try {
            queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.PHOTO)
            if (backupVideos) {
                queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO)
            }
            queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaType.AUDIO)
            
            val docSelection = "${MediaStore.MediaColumns.MIME_TYPE} IN (?, ?, ?, ?, ?, ?)"
            val docArgs = arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain"
            )
            queryMedia(MediaStore.Files.getContentUri("external"), MediaType.DOCUMENT, docSelection, docArgs)
        } catch (e: Throwable) {
            Log.e(TAG, "scanUnbackedMedia query error", e)
        }

        // Strictly sorted oldest first (chronological order) and deduplicated
        return unbackedList.distinctBy { it.signature }.sortedBy { it.dateModified }
    }

    fun markAllCurrentMediaAsBackedUp(context: Context): Int {
        val selectedBucketIds = AutoBackupPreferences.getSelectedBucketIds(context)
        val allDeviceFiles = mutableListOf<LocalMediaFile>()

        fun queryAll(uri: Uri, mediaType: MediaType, selection: String? = null, selectionArgs: Array<String>? = null) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "media_${id}" else "media_${id}"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol) ?: "" else ""
                    val bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) ?: "Storage" else "Storage"

                    if (selectedBucketIds != null && !selectedBucketIds.contains(bucketId)) {
                        continue
                    }

                    val itemUri = ContentUris.withAppendedId(uri, id)
                    allDeviceFiles.add(
                        LocalMediaFile(
                            id = id,
                            uri = itemUri,
                            filePath = path,
                            displayName = name,
                            sizeBytes = size,
                            dateModified = date,
                            mediaType = mediaType,
                            bucketId = bucketId,
                            bucketName = bucketName
                        )
                    )
                }
            }
        }

        try {
            val backupVideos = AutoBackupPreferences.isBackupVideosEnabled(context)
            queryAll(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.PHOTO)
            if (backupVideos) {
                queryAll(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO)
            }
            queryAll(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaType.AUDIO)
            val docSelection = "${MediaStore.MediaColumns.MIME_TYPE} IN (?, ?, ?, ?, ?, ?)"
            val docArgs = arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain"
            )
            queryAll(MediaStore.Files.getContentUri("external"), MediaType.DOCUMENT, docSelection, docArgs)
        } catch (e: Throwable) {
            Log.e(TAG, "markAllCurrentMediaAsBackedUp query error", e)
        }

        AutoBackupPreferences.markMultipleFilesBackedUp(context, allDeviceFiles)
        AutoBackupPreferences.setLastBackupTime(context, System.currentTimeMillis())
        _backupStatus.value = "Marked ${allDeviceFiles.size} item(s) as backed up ☁️"
        return allDeviceFiles.size
    }

    private fun copyUriToCache(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val dir = File(context.cacheDir, "autobackup_temp").apply { if (!exists()) mkdirs() }
            val temp = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            }
            temp
        } catch (e: Throwable) {
            Log.e(TAG, "copyUriToCache failed", e)
            null
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
