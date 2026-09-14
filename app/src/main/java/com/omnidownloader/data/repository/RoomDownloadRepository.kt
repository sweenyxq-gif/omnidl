package com.omnidownloader.data.repository

import com.omnidownloader.data.database.*
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDownloadRepository @Inject constructor(private val dao: DownloadDao) : DownloadRepository {
    override fun observeAll(): Flow<List<DownloadTask>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override fun observe(id: String): Flow<DownloadTask?> = dao.observe(id).map { it?.toDomain() }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun upsert(task: DownloadTask) = dao.upsert(task.toEntity(), task.headers.map { DownloadHeaderEntity(task.id, it.key, it.value) })
    override suspend fun setStatus(id: String, status: DownloadStatus, errorCode: String?, errorMessage: String?) = dao.setStatus(id, status.name, errorCode, errorMessage)
    override suspend fun updateProgress(id: String, downloaded: Long, total: Long, speed: Long, eta: Long?) = dao.updateProgress(id, downloaded, total, speed, eta)
    override suspend fun updateRemoteMetadata(id: String, resolvedUrl: String, etag: String?, lastModified: String?) =
        dao.updateRemoteMetadata(id, resolvedUrl, etag, lastModified)
    override suspend fun delete(id: String) = dao.deleteTask(id)
    override suspend fun recoverInterrupted(autoResume: Boolean) = dao.recover(if (autoResume) DownloadStatus.WAITING.name else DownloadStatus.PAUSED.name)
    override suspend fun clearCompleted() = dao.clearCompleted()
}

private fun TaskWithHeaders.toDomain(): DownloadTask {
    val source = when (SourceType.valueOf(task.sourceType)) {
        SourceType.HTTP -> DownloadSource.Http(task.sourceValue)
        SourceType.TORRENT_FILE -> DownloadSource.TorrentFile(task.sourceValue)
        SourceType.MAGNET -> DownloadSource.Magnet(task.sourceValue)
        SourceType.HLS -> DownloadSource.Hls(task.sourceValue)
        SourceType.DASH -> DownloadSource.Dash(task.sourceValue)
        SourceType.FTP -> DownloadSource.Ftp(task.sourceValue)
        SourceType.SFTP -> DownloadSource.Sftp(task.sourceValue)
    }
    return DownloadTask(task.id, source, task.fileName, task.destinationTreeUri, task.mimeType, task.totalBytes, task.downloadedBytes,
        DownloadStatus.valueOf(task.status), task.connections, task.priority, headers.associate { it.name to it.value }, task.sha256,
        task.wifiOnly, task.speedLimitBytesPerSecond, DownloadCategory.valueOf(task.category), task.createdAt,
        task.errorCode?.let { runCatching { DownloadErrorCode.valueOf(it) }.getOrNull() }, task.errorMessage,
        task.resolvedUrl, task.etag, task.lastModified, task.speedBytesPerSecond, task.etaSeconds)
}

private fun DownloadTask.toEntity(): DownloadTaskEntity {
    val type = when (source) {
        is DownloadSource.Http -> SourceType.HTTP; is DownloadSource.TorrentFile -> SourceType.TORRENT_FILE
        is DownloadSource.Magnet -> SourceType.MAGNET; is DownloadSource.Hls -> SourceType.HLS
        is DownloadSource.Dash -> SourceType.DASH; is DownloadSource.Ftp -> SourceType.FTP; is DownloadSource.Sftp -> SourceType.SFTP
    }
    return DownloadTaskEntity(id, type.name, source.value, fileName, destinationTreeUri, mimeType, totalBytes, downloadedBytes,
        status.name, connections.coerceIn(1, 16), priority, sha256, wifiOnly, speedLimitBytesPerSecond, category.name,
        createdAt, System.currentTimeMillis(), errorCode?.name, errorMessage, resolvedUrl, etag, lastModified,
        speedBytesPerSecond, etaSeconds)
}
