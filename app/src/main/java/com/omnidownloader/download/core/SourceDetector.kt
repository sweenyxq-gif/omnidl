package com.omnidownloader.download.core

import com.omnidownloader.domain.model.DownloadSource
import java.net.URI
import javax.inject.Inject

class SourceDetector @Inject constructor() {
    fun detect(raw: String, contentType: String? = null): DownloadSource? {
        val input = raw.trim()
        if (input.startsWith("magnet:?", true)) return DownloadSource.Magnet(input)
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme == "ftp") return DownloadSource.Ftp(input)
        if (scheme == "sftp") return DownloadSource.Sftp(input)
        if (scheme !in setOf("http", "https", "content")) return null
        val path = uri.path.orEmpty().lowercase()
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            path.endsWith(".torrent") || mime == "application/x-bittorrent" -> DownloadSource.TorrentFile(input)
            path.endsWith(".m3u8") || mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") -> DownloadSource.Hls(input)
            path.endsWith(".mpd") || mime == "application/dash+xml" -> DownloadSource.Dash(input)
            scheme == "content" -> DownloadSource.TorrentFile(input)
            else -> DownloadSource.Http(input)
        }
    }
}
