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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AutoBackupManager {

    private const val TAG = "AutoBackupManager"
    private const val WORK_NAME = "cloudvault_periodic_auto_backup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isSyncing = AtomicBoolean(false)

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
                    try {
                        val success = TelegramRepository.uploadFile(uploadPath, file.mediaType, file.displayName)
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

            AutoBackupPreferences.setLastBackupTime(context, System.currentTimeMillis())

            if (successCount > 0) {
                UploadNotificationManager.showComplete(context, successCount, total)
                _backupStatus.value = "Backed up $successCount item(s) to Telegram Cloud ☁️"
                TelegramRepository.loadVaultItems()
            } else {
                _backupStatus.value = "Auto Backup encountered issues"
            }

            true
        } catch (e: Throwable) {
            Log.e(TAG, "performBackupSync error", e)
            _backupStatus.value = "Backup error: ${e.message}"
            false
        } finally {
            isSyncing.set(false)
        }
    }

    fun scanAvailableFolders(context: Context): List<DeviceFolderInfo> {
        val folderMap = mutableMapOf<String, Pair<String, Int>>() // bucketId -> (bucketName, count)
        val selectedBucketIds = AutoBackupPreferences.getSelectedBucketIds(context)

        fun queryMediaStore(uri: Uri) {
            val projection = arrayOf(
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
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

        // Get cloud vault items to prevent duplicate uploads if already in Telegram
        val cloudVaultSizes = mutableSetOf<Long>()
        val cloudVaultNames = mutableSetOf<String>()
        (TelegramRepository.photos.value + TelegramRepository.videos.value + TelegramRepository.files.value).forEach { item ->
            if (item.sizeBytes > 0) cloudVaultSizes.add(item.sizeBytes)
            if (item.title.isNotBlank()) cloudVaultNames.add(item.title.lowercase())
        }

        fun queryMedia(uri: Uri, isVideo: Boolean) {
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
                null,
                null,
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

                    val mediaType = if (isVideo) MediaType.VIDEO else MediaType.PHOTO
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

                    // 1. Check local backup signature
                    if (AutoBackupPreferences.hasSignature(context, localFile.signature)) {
                        continue
                    }

                    // 2. Check if already uploaded to Telegram Cloud (matching size and name)
                    if (cloudVaultSizes.contains(localFile.sizeBytes) && cloudVaultNames.contains(localFile.displayName.lowercase())) {
                        AutoBackupPreferences.markSignatureBackedUp(context, localFile.signature)
                        continue
                    }

                    unbackedList.add(localFile)
                }
            }
        }

        try {
            queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
            queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        } catch (e: Throwable) {
            Log.e(TAG, "scanUnbackedMedia query error", e)
        }

        // Ensure strictly sorted oldest first
        return unbackedList.sortedBy { it.dateModified }
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
