package com.omnidownloader.service

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.omnidownloader.R
import com.omnidownloader.data.update.UpdateCoordinator
import java.io.File
import java.security.MessageDigest

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val prefs = context.getSharedPreferences(UpdateCoordinator.PREFS, Context.MODE_PRIVATE)
        val expectedId = prefs.getLong(UpdateCoordinator.KEY_ID, -1)
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != expectedId) return
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(expectedId)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return
        }
        val version = prefs.getString(UpdateCoordinator.KEY_VERSION, null) ?: return
        val expectedDigest = prefs.getString(UpdateCoordinator.KEY_SHA, null) ?: return
        val apk = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/omnidl-$version.apk")
        if (!apk.isFile || sha256(apk) != expectedDigest) {
            apk.delete()
            prefs.edit().clear().apply()
            context.getSystemService(NotificationManager::class.java).notify(2003,
                NotificationCompat.Builder(context, NotificationHelper.FAILED).setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("Update verification failed").setContentText("The downloaded update was removed.").setAutoCancel(true).build())
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, 2002, install, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(2002,
            NotificationCompat.Builder(context, NotificationHelper.UPDATES).setSmallIcon(R.drawable.ic_download)
                .setContentTitle("OmniDL $version is ready").setContentText("Tap to approve and install the update")
                .setContentIntent(pending).setAutoCancel(true).build())
        prefs.edit().remove(UpdateCoordinator.KEY_ID).apply()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
