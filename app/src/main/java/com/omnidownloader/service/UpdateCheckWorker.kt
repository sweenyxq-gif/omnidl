package com.omnidownloader.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omnidownloader.data.repository.SettingsRepository
import com.omnidownloader.data.update.GitHubUpdateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updates: GitHubUpdateRepository,
    private val settings: SettingsRepository,
    private val notifications: NotificationHelper,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.settings.first().automaticUpdateChecks) return Result.success()
        return runCatching {
            updates.check()?.let { notifications.showUpdate(it) }
            Result.success()
        }.getOrElse { Result.success() }
    }
}
