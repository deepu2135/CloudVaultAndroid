package com.cloudvault.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AutoBackupPreferences.isEnabled(applicationContext)) {
            return@withContext Result.success()
        }

        val success = AutoBackupManager.performBackupSync(applicationContext)
        if (success) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
