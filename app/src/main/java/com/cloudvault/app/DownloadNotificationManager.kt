package com.cloudvault.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

object DownloadNotificationManager {

    private const val CHANNEL_ID = "cloudvault_downloads"
    private const val CHANNEL_NAME = "CloudVault Downloads"
    const val DEFAULT_NOTIFICATION_ID = 3001

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for CloudVault files"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun showDownloadProgress(
        context: Context,
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        title: String,
        currentProgress: Int,
        maxProgress: Int = 100,
        statusText: String
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText)
            .setProgress(maxProgress, currentProgress, maxProgress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        manager.notify(notificationId, builder.build())
    }

    fun showDownloadComplete(
        context: Context,
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        title: String,
        message: String,
        savedFile: File? = null,
        mimeType: String = ""
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (savedFile != null && savedFile.exists()) {
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    savedFile
                )
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, if (mimeType.isNotBlank()) mimeType else "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pendingIntent)
            } catch (e: Throwable) {
                // If FileProvider resolution fails, notify without pending intent
            }
        }

        manager.notify(notificationId, builder.build())
    }

    fun showDownloadError(
        context: Context,
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
        title: String,
        message: String
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        manager.notify(notificationId, builder.build())
    }

    fun cancel(context: Context, notificationId: Int = DEFAULT_NOTIFICATION_ID) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(notificationId)
    }
}
