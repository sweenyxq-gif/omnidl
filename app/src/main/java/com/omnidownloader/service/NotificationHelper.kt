package com.omnidownloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.omnidownloader.MainActivity
import com.omnidownloader.R
import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.model.DownloadTask
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {
    companion object { const val ACTIVE = "active_downloads"; const val COMPLETED = "completed_downloads"; const val FAILED = "failed_downloads" }
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(ACTIVE, "Active downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(COMPLETED, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(FAILED, "Failed downloads", NotificationManager.IMPORTANCE_DEFAULT)))
    }
    fun active(tasks: List<DownloadTask>): Notification {
        val current = tasks.firstOrNull { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, ACTIVE).setSmallIcon(R.drawable.ic_download).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(current?.fileName ?: "Omni Downloader").setContentText(if (current == null) "Queue is idle" else "${tasks.count { it.status == DownloadStatus.DOWNLOADING }} active")
        current?.let { task ->
            val pause = PendingIntent.getService(context, task.id.hashCode(), Intent(context, DownloadForegroundService::class.java).setAction("PAUSE").putExtra("id", task.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val cancel = PendingIntent.getService(context, task.id.hashCode() + 1, Intent(context, DownloadForegroundService::class.java).setAction("CANCEL").putExtra("id", task.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, "Pause", pause).addAction(0, "Cancel", cancel)
        }
        if (current != null && current.totalBytes > 0) builder.setProgress(1000, ((current.downloadedBytes * 1000) / current.totalBytes).toInt(), false) else builder.setProgress(0, 0, current != null)
        return builder.build()
    }
    fun terminal(task: DownloadTask, success: Boolean): Notification {
        val open = PendingIntent.getActivity(context, task.id.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, if (success) COMPLETED else FAILED).setSmallIcon(R.drawable.ic_download).setContentIntent(open).setAutoCancel(true)
            .setContentTitle(if (success) "Download completed" else "Download failed")
            .setContentText(if (success) task.fileName else "${task.fileName}: ${task.errorMessage ?: "Unknown error"}").build()
    }
}
