package com.omnidownloader.data.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.omnidownloader.MainActivity
import com.omnidownloader.R
import com.omnidownloader.service.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?,
    val isCompleted: Boolean = false,
    val readyToInstallApk: File? = null,
    val error: String? = null,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val percentage: Int
        get() = (progressFraction * 100).toInt()
}

@Singleton
class UpdateCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    companion object {
        const val PREFS = "app_updates"
        const val KEY_ID = "download_id"
        const val KEY_SHA = "sha256"
        const val KEY_VERSION = "version"
        const val NOTIFICATION_ID = 2002
    }

    private val _progress = MutableStateFlow<UpdateProgress?>(null)
    val progress: StateFlow<UpdateProgress?> = _progress.asStateFlow()

    private var downloadJob: Job? = null

    fun download(update: AppUpdate): Long {
        downloadJob?.cancel()
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            startDownload(update)
        }
        return 1L
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        _progress.value = null
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    suspend fun startDownload(update: AppUpdate) = withContext(Dispatchers.IO) {
        val digest = requireNotNull(update.sha256) { "This release has no SHA-256 digest and cannot be installed safely" }
        val updateDirectory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        updateDirectory.listFiles()?.forEach { if (it.isFile) it.delete() }
        val apkFile = File(updateDirectory, "omnidl-${update.version}.apk")

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        _progress.value = UpdateProgress(
            downloadedBytes = 0,
            totalBytes = update.size,
            speedBytesPerSecond = 0,
            etaSeconds = null
        )

        val request = Request.Builder()
            .url(update.downloadUrl)
            .header("User-Agent", "OmniDL/${com.omnidownloader.BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP error ${response.code} downloading update")
                val body = response.body ?: throw java.io.IOException("Empty response body")
                val totalLength = if (update.size > 0) update.size else body.contentLength()
                val md = MessageDigest.getInstance("SHA-256")

                val buffer = ByteArray(64 * 1024)
                var bytesReadTotal = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastSpeed = 0L
                var currentSpeed = 0L
                var lastNotificationTime = 0L

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            md.update(buffer, 0, read)
                            bytesReadTotal += read
                            bytesSinceLastSpeed += read

                            val now = System.currentTimeMillis()
                            val timeDiff = now - lastSpeedCalcTime
                            if (timeDiff >= 500) {
                                currentSpeed = (bytesSinceLastSpeed * 1000) / timeDiff
                                lastSpeedCalcTime = now
                                bytesSinceLastSpeed = 0L
                            }

                            val eta = if (currentSpeed > 0 && totalLength > bytesReadTotal) {
                                (totalLength - bytesReadTotal) / currentSpeed
                            } else null

                            _progress.value = UpdateProgress(
                                downloadedBytes = bytesReadTotal,
                                totalBytes = totalLength,
                                speedBytesPerSecond = currentSpeed,
                                etaSeconds = eta
                            )

                            // Throttle ongoing notification updates to avoid IPC flooding
                            if (now - lastNotificationTime > 800) {
                                lastNotificationTime = now
                                updateNotification(
                                    update.version,
                                    bytesReadTotal,
                                    totalLength,
                                    currentSpeed,
                                    eta,
                                    notificationManager
                                )
                            }
                        }
                    }
                }

                // Verify SHA-256
                val calculatedDigest = md.digest().joinToString("") { "%02x".format(it) }
                if (!calculatedDigest.equals(digest, ignoreCase = true)) {
                    apkFile.delete()
                    _progress.value = UpdateProgress(
                        downloadedBytes = bytesReadTotal,
                        totalBytes = totalLength,
                        speedBytesPerSecond = 0,
                        etaSeconds = null,
                        error = "Update verification failed (SHA-256 mismatch)"
                    )
                    showFailureNotification(notificationManager)
                    return@withContext
                }

                // Complete
                _progress.value = UpdateProgress(
                    downloadedBytes = totalLength,
                    totalBytes = totalLength,
                    speedBytesPerSecond = 0,
                    etaSeconds = 0,
                    isCompleted = true,
                    readyToInstallApk = apkFile
                )
                showCompletedNotification(update.version, apkFile, notificationManager)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                apkFile.delete()
                _progress.value = null
                notificationManager.cancel(NOTIFICATION_ID)
                return@withContext
            }
            apkFile.delete()
            _progress.value = UpdateProgress(
                downloadedBytes = 0,
                totalBytes = 0,
                speedBytesPerSecond = 0,
                etaSeconds = null,
                error = e.message ?: "Update download failed"
            )
            showFailureNotification(notificationManager)
        }
    }

    private fun updateNotification(
        version: String,
        downloaded: Long,
        total: Long,
        speed: Long,
        eta: Long?,
        notificationManager: NotificationManager
    ) {
        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val text = buildString {
            append("$percent%")
            if (total > 0) append(" (${formatBytes(downloaded)} / ${formatBytes(total)})")
            if (speed > 0) append(" • ↓ ${formatBytes(speed)}/s")
            if (eta != null && eta > 0) append(" • ETA ${formatDuration(eta)}")
        }

        val builder = NotificationCompat.Builder(context, NotificationHelper.UPDATES)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloading OmniDL v$version")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun showCompletedNotification(version: String, apkFile: File, notificationManager: NotificationManager) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingInstall = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.UPDATES)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("OmniDL v$version is ready")
            .setContentText("Tap to approve and install the update")
            .setContentIntent(pendingInstall)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFailureNotification(notificationManager: NotificationManager) {
        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.FAILED)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Update download failed")
            .setContentText("Could not complete update download.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "${value.toLong()} ${units[index]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
