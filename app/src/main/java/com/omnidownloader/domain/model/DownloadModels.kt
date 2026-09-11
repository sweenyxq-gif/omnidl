package com.omnidownloader.domain.model

import java.util.UUID

sealed interface DownloadSource {
    val value: String
    data class Http(override val value: String) : DownloadSource
    data class TorrentFile(override val value: String) : DownloadSource
    data class Magnet(override val value: String) : DownloadSource
    data class Hls(override val value: String) : DownloadSource
    data class Dash(override val value: String) : DownloadSource
    data class Ftp(override val value: String) : DownloadSource
    data class Sftp(override val value: String) : DownloadSource
}

enum class SourceType { HTTP, TORRENT_FILE, MAGNET, HLS, DASH, FTP, SFTP }
enum class DownloadStatus { WAITING, RESOLVING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class DownloadCategory { VIDEOS, MUSIC, IMAGES, DOCUMENTS, ARCHIVES, APPS, TORRENTS, OTHER }
enum class DownloadErrorCode {
    NETWORK_ERROR, TIMEOUT, HTTP_ERROR, STORAGE_ERROR, NO_SPACE, PERMISSION_DENIED,
    INVALID_URL, SERVER_NO_RESUME, CHECKSUM_FAILED, TORRENT_ERROR, RESOLUTION_FAILED,
    UNSUPPORTED_SOURCE, CANCELLED
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val source: DownloadSource,
    val fileName: String,
    val destinationTreeUri: String,
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val connections: Int = 8,
    val priority: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val sha256: String? = null,
    val wifiOnly: Boolean = false,
    val speedLimitBytesPerSecond: Long = 0,
    val category: DownloadCategory = DownloadCategory.OTHER,
    val createdAt: Long = System.currentTimeMillis(),
    val errorCode: DownloadErrorCode? = null,
    val errorMessage: String? = null,
)

data class DownloadProgress(
    val id: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val status: DownloadStatus,
    val error: String? = null,
) {
    val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

data class DownloadPreview(
    val source: DownloadSource,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val supportsRanges: Boolean,
    val finalUrl: String,
)
