package com.omnidownloader.domain.engine

import com.omnidownloader.domain.model.DownloadProgress
import com.omnidownloader.domain.model.DownloadSource
import com.omnidownloader.domain.model.DownloadTask
import kotlinx.coroutines.flow.Flow

interface DownloadEngine {
    fun supports(source: DownloadSource): Boolean
    suspend fun start(task: DownloadTask)
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
    fun observe(id: String): Flow<DownloadProgress>
}
