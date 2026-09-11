package com.omnidownloader.data.resolver

import com.omnidownloader.domain.model.SourceType
import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.resolver.Resolver
import com.omnidownloader.download.core.SourceDetector
import com.omnidownloader.download.http.FileNameParser
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectUrlResolver @Inject constructor(
    private val detector: SourceDetector
) : Resolver {
    override val id: String = "direct_url_resolver"
    override val priority: Int = 100

    private val directExtensions = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg", "apk", "exe",
        "mp4", "mkv", "webm", "avi", "mov", "mp3", "flac", "wav", "m4a", "aac",
        "pdf", "epub", "mobi", "docx", "xlsx", "pptx", "torrent"
    )

    override fun canHandle(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) return true
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("http", "https", "ftp", "sftp")) return false
        val path = uri.path.orEmpty().lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in directExtensions
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult {
        val raw = request.url.trim()
        val filename = FileNameParser.fromUrl(raw) ?: "file"
        val ext = filename.substringAfterLast('.', "").lowercase()

        val type = when {
            raw.startsWith("magnet:?", ignoreCase = true) -> SourceType.MAGNET
            ext == "torrent" -> SourceType.TORRENT_FILE
            ext in setOf("m3u8") -> SourceType.HLS
            ext in setOf("mpd") -> SourceType.DASH
            raw.startsWith("ftp://", ignoreCase = true) -> SourceType.FTP
            raw.startsWith("sftp://", ignoreCase = true) -> SourceType.SFTP
            else -> SourceType.HTTP
        }

        val mimeType = when (ext) {
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "torrent" -> "application/x-bittorrent"
            else -> "application/octet-stream"
        }

        val item = ResolvedItem(
            label = "Direct File ($ext)",
            url = raw,
            filename = filename,
            type = type,
            mimeType = mimeType,
            headers = request.headers,
            cookies = request.cookies
        )

        return ResolveResult.Success(
            originalUrl = request.url,
            results = listOf(item)
        )
    }
}
