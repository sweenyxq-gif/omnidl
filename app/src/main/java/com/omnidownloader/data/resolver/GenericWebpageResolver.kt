package com.omnidownloader.data.resolver

import com.omnidownloader.domain.model.SourceType
import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.resolver.Resolver
import com.omnidownloader.download.http.FileNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GenericWebpageResolver @Inject constructor(
    private val client: OkHttpClient
) : Resolver {
    override val id: String = "generic_webpage_resolver"
    override val priority: Int = 50

    private val mediaExtensions = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "mp3", "flac", "wav", "m4a", "aac",
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "apk", "exe", "dmg", "msi", "deb", "rpm", "pdf", "torrent", "m3u8", "mpd"
    )

    override fun canHandle(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(request.url.trim())
        request.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (request.cookies.isNotEmpty()) {
            reqBuilder.header("Cookie", request.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        val response = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            return@withContext ResolveResult.Failed(
                originalUrl = request.url,
                reason = "Failed to connect: ${e.message}"
            )
        }

        response.use { res ->
            val finalUrl = res.request.url.toString()
            val contentType = res.header("Content-Type")?.lowercase().orEmpty()
            val disposition = res.header("Content-Disposition")
            val size = res.header("Content-Length")?.toLongOrNull()

            // If not HTML, this URL is a direct media/file stream!
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
                val filename = FileNameParser.resolve(disposition, finalUrl, contentType)
                val type = detectType(finalUrl, contentType)
                val item = ResolvedItem(
                    label = "Direct Resource (${contentType.substringBefore(';')})",
                    url = finalUrl,
                    filename = filename,
                    type = type,
                    mimeType = contentType.substringBefore(';').ifBlank { null },
                    size = size,
                    headers = request.headers,
                    cookies = request.cookies
                )
                return@withContext ResolveResult.Success(
                    originalUrl = request.url,
                    results = listOf(item)
                )
            }

            // Read HTML body
            val body = res.body?.string().orEmpty()

            val candidates = extractCandidates(finalUrl, body, request.headers, request.cookies)
            if (candidates.isNotEmpty()) {
                ResolveResult.Success(
                    originalUrl = request.url,
                    results = candidates
                )
            } else {
                ResolveResult.Failed(
                    originalUrl = request.url,
                    reason = "No media, streams, or downloadable files detected on this webpage"
                )
            }
        }
    }

    private fun extractCandidates(
        baseUrl: String,
        html: String,
        headers: Map<String, String>,
        cookies: Map<String, String>
    ): List<ResolvedItem> {
        val results = mutableListOf<ResolvedItem>()
        val seenUrls = mutableSetOf<String>()

        fun addCandidate(rawUrl: String, label: String, quality: String? = null) {
            val url = resolveUrl(baseUrl, rawUrl.trim())
            if (url.isBlank() || seenUrls.contains(url)) return
            seenUrls.add(url)

            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            val type = detectType(url, null)
            val filename = if (type == SourceType.MAGNET) "magnet_download" else FileNameParser.fromUrl(url)

            results.add(
                ResolvedItem(
                    label = label,
                    url = url,
                    filename = filename,
                    type = type,
                    mimeType = if (ext.isNotBlank()) "application/$ext" else null,
                    size = null,
                    quality = quality,
                    headers = headers,
                    cookies = cookies
                )
            )
        }

        // 1. OpenGraph & Twitter video/audio tags
        val ogVideoRegex = Regex("""<meta\s+[^>]*property=["'](?:og:video|og:video:url)["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        ogVideoRegex.findAll(html).forEach { match ->
            addCandidate(match.groupValues[1], "OpenGraph Video")
        }
        val ogAudioRegex = Regex("""<meta\s+[^>]*property=["'](?:og:audio|og:audio:url)["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        ogAudioRegex.findAll(html).forEach { match ->
            addCandidate(match.groupValues[1], "OpenGraph Audio")
        }

        // 2. HTML5 <video src="..."> and <video><source src="...">
        val videoSrcRegex = Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        videoSrcRegex.findAll(html).forEach { match ->
            addCandidate(match.groupValues[1], "HTML5 Video")
        }

        val audioSrcRegex = Regex("""<audio[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        audioSrcRegex.findAll(html).forEach { match ->
            addCandidate(match.groupValues[1], "HTML5 Audio")
        }

        val sourceSrcRegex = Regex("""<source[^>]+src=["']([^"']+)["'](?:[^>]*type=["']([^"']+)["'])?""", RegexOption.IGNORE_CASE)
        sourceSrcRegex.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            val mime = match.groupValues.getOrNull(2)
            addCandidate(src, if (mime?.contains("video") == true) "Video Stream" else "Media Stream")
        }

        // 3. Magnet links
        val magnetRegex = Regex("""href=["'](magnet:\?[^"']+)["']""", RegexOption.IGNORE_CASE)
        magnetRegex.findAll(html).forEach { match ->
            addCandidate(match.groupValues[1], "BitTorrent Magnet")
        }

        // 4. Downloadable file links <a href="...">
        val hrefRegex = Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
        hrefRegex.findAll(html).forEach { match ->
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val cleanHref = href.substringBefore('?')
            val ext = cleanHref.substringAfterLast('.', "").lowercase()
            if (ext in mediaExtensions) {
                val label = if (text.isNotBlank() && text.length < 40) text else "File ($ext)"
                addCandidate(href, label)
            }
        }

        return results
    }

    private fun detectType(url: String, contentType: String?): SourceType {
        val u = url.lowercase()
        val mime = contentType?.lowercase().orEmpty()
        return when {
            u.startsWith("magnet:?") -> SourceType.MAGNET
            u.contains(".m3u8") || mime.contains("mpegurl") -> SourceType.HLS
            u.contains(".mpd") || mime.contains("dash+xml") -> SourceType.DASH
            u.endsWith(".torrent") || mime.contains("torrent") -> SourceType.TORRENT_FILE
            u.startsWith("ftp://") -> SourceType.FTP
            u.startsWith("sftp://") -> SourceType.SFTP
            else -> SourceType.HTTP
        }
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            val base = URI(baseUrl)
            base.resolve(relativeOrAbsolute).toASCIIString()
        } catch (e: Exception) {
            relativeOrAbsolute
        }
    }
}
