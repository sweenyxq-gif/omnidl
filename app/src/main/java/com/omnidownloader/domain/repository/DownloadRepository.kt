package com.omnidownloader.domain.repository

import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.model.DownloadTask
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun observeAll(): Flow<List<DownloadTask>>
    fun observe(id: String): Flow<DownloadTask?>
    suspend fun get(id: String): DownloadTask?
    suspend fun upsert(task: DownloadTask)
    suspend fun setStatus(id: String, status: DownloadStatus, errorCode: String? = null, errorMessage: String? = null)
    suspend fun updateProgress(id: String, downloaded: Long, total: Long, speed: Long = 0, eta: Long? = null)
    suspend fun updateRemoteMetadata(id: String, resolvedUrl: String, etag: String?, lastModified: String?)
    suspend fun delete(id: String)
    suspend fun recoverInterrupted(autoResume: Boolean)
    suspend fun clearCompleted()
}
