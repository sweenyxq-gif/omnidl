package com.omnidownloader.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks", indices = [Index("status"), Index("priority"), Index("createdAt")])
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val sourceValue: String,
    val fileName: String,
    val destinationTreeUri: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val connections: Int,
    val priority: Int,
    val sha256: String?,
    val wifiOnly: Boolean,
    val speedLimitBytesPerSecond: Long,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val errorCode: String?,
    val errorMessage: String?,
    val resolvedUrl: String?,
    val etag: String?,
    val lastModified: String?,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?,
)

@Entity(
    tableName = "download_segments",
    primaryKeys = ["taskId", "segmentIndex"],
    foreignKeys = [ForeignKey(entity = DownloadTaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class DownloadSegmentEntity(
    val taskId: String,
    val segmentIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long,
    val tempPath: String,
    val complete: Boolean,
)

@Entity(
    tableName = "download_headers",
    primaryKeys = ["taskId", "name"],
    foreignKeys = [ForeignKey(entity = DownloadTaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class DownloadHeaderEntity(val taskId: String, val name: String, val value: String)

@Entity(tableName = "download_history", indices = [Index("taskId"), Index("completedAt")])
data class DownloadHistoryEntity(@PrimaryKey(autoGenerate = true) val rowId: Long = 0, val taskId: String, val fileName: String, val destinationUri: String, val totalBytes: Long, val completedAt: Long)

@Entity(tableName = "torrent_state", indices = [Index("taskId", unique = true)])
data class TorrentStateEntity(@PrimaryKey val taskId: String, val resumeDataPath: String?, val selectedFilesJson: String?, val updatedAt: Long)

@Entity(tableName = "resolver_metadata", indices = [Index("taskId", unique = true), Index("expiresAt")])
data class ResolverMetadataEntity(@PrimaryKey val taskId: String, val resolverId: String, val originalUrl: String, val resolvedUrl: String, val metadataJson: String?, val expiresAt: Long?)
