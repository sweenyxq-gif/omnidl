package com.omnidownloader.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omnidownloader.data.repository.SettingsRepository
import com.omnidownloader.domain.repository.DownloadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class RecoveryWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters, private val repository: DownloadRepository, private val settings: SettingsRepository) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = settings.settings.first()
        repository.recoverInterrupted(prefs.autoResume)
        val knownTaskIds = repository.observeAll().first().mapTo(hashSetOf()) { it.id }
        listOf("segments", "torrents").forEach { directory ->
            File(applicationContext.noBackupFilesDir, directory).listFiles()?.forEach { taskDirectory ->
                if (taskDirectory.isDirectory && taskDirectory.name !in knownTaskIds) taskDirectory.deleteRecursively()
            }
        }
        return Result.success()
    }
}
