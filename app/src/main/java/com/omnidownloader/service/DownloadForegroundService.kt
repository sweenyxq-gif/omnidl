package com.omnidownloader.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.omnidownloader.download.core.DownloadQueueManager
import com.omnidownloader.data.repository.SettingsRepository
import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.repository.DownloadRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class DownloadForegroundService : Service() {
    @Inject lateinit var repository: DownloadRepository
    @Inject lateinit var queue: DownloadQueueManager
    @Inject lateinit var notifications: NotificationHelper
    @Inject lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val previousStatuses = mutableMapOf<String, DownloadStatus>()
    private var lastActiveNotificationAt = 0L
    override fun onCreate() {
        super.onCreate(); notifications.createChannels()
        ServiceCompat.startForeground(this, 1001, notifications.active(emptyList()), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        scope.launch {
            repository.recoverInterrupted(settings.settings.first().autoResume)
            repository.observeAll().collect { tasks ->
            val manager = getSystemService(android.app.NotificationManager::class.java)
            val now = System.currentTimeMillis()
            val idle = tasks.none { it.status in setOf(DownloadStatus.WAITING, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING) }
            if (idle || now - lastActiveNotificationAt >= 1_000) {
                manager.notify(1001, notifications.active(tasks))
                lastActiveNotificationAt = now
            }
            tasks.forEach { task ->
                val before = previousStatuses.put(task.id, task.status)
                if (before != null && before != task.status && task.status == DownloadStatus.COMPLETED) manager.notify(task.id.hashCode(), notifications.terminal(task, true))
                if (before != null && before != task.status && task.status == DownloadStatus.FAILED) manager.notify(task.id.hashCode(), notifications.terminal(task, false))
            }
            previousStatuses.keys.retainAll(tasks.mapTo(mutableSetOf()) { it.id })
            if (idle) stopSelf()
            }
        }
        queue.kick()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { "PAUSE_ALL" -> queue.pauseAll(); "RESUME_ALL" -> queue.resumeAll(); "PAUSE" -> intent.getStringExtra("id")?.let(queue::pause); "RESUME" -> intent.getStringExtra("id")?.let(queue::resume); "CANCEL" -> intent.getStringExtra("id")?.let(queue::cancel) }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
