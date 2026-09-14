package com.omnidownloader.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateCoordinator @Inject constructor(@ApplicationContext private val context: Context) {
    companion object { const val PREFS = "app_updates"; const val KEY_ID = "download_id"; const val KEY_SHA = "sha256"; const val KEY_VERSION = "version" }

    fun download(update: AppUpdate): Long {
        val digest = requireNotNull(update.sha256) { "This release has no SHA-256 digest and cannot be installed safely" }
        val updateDirectory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        updateDirectory.listFiles()?.forEach { if (it.isFile) it.delete() }
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("OmniDL ${update.version}")
            .setDescription("Downloading verified application update")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/omnidl-${update.version}.apk")
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ID, id).putString(KEY_SHA, digest.lowercase()).putString(KEY_VERSION, update.version).apply()
        return id
    }
}
