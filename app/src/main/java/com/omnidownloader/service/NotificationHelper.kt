package com.omnidownloader.service

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.omnidownloader.MainActivity
import com.omnidownloader.R
import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.model.DownloadTask
import com.omnidownloader.data.update.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {
    companion object { const val ACTIVE = "active_downloads"; const val COMPLETED = "completed_downloads"; const val FAILED = "failed_downloads"; const val UPDATES = "app_updates" }
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(ACTIVE, "Active downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(COMPLETED, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(FAILED, "Failed downloads", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(UPDATES, "Application updates", NotificationManager.IMPORTANCE_DEFAULT)))
    }
    fun showUpdate(update: AppUpdate) {
        if (update.sha256 == null) return
        val updatePrefs = context.getSharedPreferences("update_notifications", Context.MODE_PRIVATE)
        if (updatePrefs.getString("last_version", null) == update.version) return
        val download = Intent(context, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.ACTION_DOWNLOAD)
            .putExtra("version", update.version).putExtra("url", update.downloadUrl)
            .putExtra("sha256", update.sha256).putExtra("size", update.size)
        val pending = PendingIntent.getBroadcast(context, 2001, download, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openApp = PendingIntent.getActivity(context, 2001, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, UPDATES).setSmallIcon(R.drawable.ic_download)
            .setContentTitle("OmniDL ${update.version} is available")
            .setContentText("Download the signed and verified update")
            .setContentIntent(openApp).addAction(0, "Download", pending).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(2001, notification)
        updatePrefs.edit().putString("last_version", update.version).apply()
    }
    fun active(tasks: List<DownloadTask>): Notification {
        val current = tasks.firstOrNull { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val activeCount = tasks.count { it.status == DownloadStatus.DOWNLOADING }
        val progressText = current?.let { task ->
            buildList {
                add("$activeCount active")
                if (task.speedBytesPerSecond > 0) add("${formatBytes(task.speedBytesPerSecond)}/s")
                task.etaSeconds?.let { add("ETA ${formatDuration(it)}") }
            }.joinToString(" • ")
        }
        val builder = NotificationCompat.Builder(context, ACTIVE).setSmallIcon(R.drawable.ic_download).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(current?.fileName ?: "Omni Downloader").setContentText(progressText ?: "Queue is idle")
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

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble(); var index = 0
        while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
        return if (index == 0) "${value.toLong()} ${units[index]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
