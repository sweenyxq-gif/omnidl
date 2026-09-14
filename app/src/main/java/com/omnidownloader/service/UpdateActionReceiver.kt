package com.omnidownloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnidownloader.data.update.AppUpdate
import com.omnidownloader.data.update.UpdateCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class UpdateActionReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: UpdateCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOWNLOAD) return
        val update = AppUpdate(
            version = intent.getStringExtra("version") ?: return,
            title = "OmniDL update",
            notes = "",
            downloadUrl = intent.getStringExtra("url") ?: return,
            sha256 = intent.getStringExtra("sha256") ?: return,
            size = intent.getLongExtra("size", -1),
            releaseUrl = "",
        )
        runCatching { coordinator.download(update) }
    }

    companion object { const val ACTION_DOWNLOAD = "com.omnidownloader.DOWNLOAD_UPDATE" }
}
