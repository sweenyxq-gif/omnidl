package com.omnidownloader.download.http

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

data class ByteRange(val index: Int, val start: Long, val endInclusive: Long) { val length get() = endInclusive - start + 1 }

object RangeCalculator {
    fun calculate(totalBytes: Long, requestedParts: Int): List<ByteRange> {
        require(totalBytes > 0)
        val upper = minOf(16L, totalBytes).toInt()
        val parts = requestedParts.coerceIn(1, upper)
        val base = totalBytes / parts
        val remainder = totalBytes % parts
        var start = 0L
        return (0 until parts).map { index ->
            val length = base + if (index < remainder) 1 else 0
            ByteRange(index, start, start + length - 1).also { start += length }
        }
    }
}

/** Conservative first-pass connection selection; runtime growth can build on these safe caps. */
object AdaptiveConnectionPolicy {
    fun initialConnections(totalBytes: Long, userLimit: Int, supportsRanges: Boolean, multiplexed: Boolean): Int {
        if (!supportsRanges || totalBytes <= 0) return 1
        val sizeCap = when {
            totalBytes < 2L * 1024 * 1024 -> 1
            totalBytes < 16L * 1024 * 1024 -> 2
            totalBytes < 128L * 1024 * 1024 -> 4
            totalBytes < 1024L * 1024 * 1024 -> 6
            else -> 8
        }
        val protocolCap = if (multiplexed) 4 else 8
        return userLimit.coerceIn(1, 16).coerceAtMost(sizeCap).coerceAtMost(protocolCap)
    }
}

object RemoteValidatorPolicy {
    fun canReuse(
        storedEtag: String?,
        storedLastModified: String?,
        currentEtag: String?,
        currentLastModified: String?,
    ): Boolean = when {
        storedEtag != null -> currentEtag != null && storedEtag == currentEtag
        storedLastModified != null -> currentLastModified != null && storedLastModified == currentLastModified
        else -> false
    }

    fun ifRange(etag: String?, lastModified: String?): String? =
        etag?.takeUnless { it.startsWith("W/", true) } ?: lastModified
}

object FileNameParser {
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
    private val knownExtensions = mapOf(
        "application/pdf" to "pdf", "application/zip" to "zip", "application/json" to "json",
        "application/vnd.android.package-archive" to "apk", "application/x-bittorrent" to "torrent",
        "video/mp4" to "mp4", "video/webm" to "webm", "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a", "image/jpeg" to "jpg", "image/png" to "png",
        "image/webp" to "webp", "text/plain" to "txt", "text/csv" to "csv",
    )

    fun fromContentDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val extended = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.trim()?.trim('"')
        val decoded = extended?.let { token ->
            val parts = token.split('\'', limit = 3)
            val charset = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: StandardCharsets.UTF_8.name()
            val encoded = parts.getOrNull(2) ?: token
            runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), Charset.forName(charset).name()) }.getOrNull()
        }
        val quoted = Regex("(?:^|;)\\s*filename\\s*=\\s*\"((?:\\\\.|[^\"])*)\"", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.replace(Regex("\\\\(.)")) { it.groupValues[1] }
        val plain = Regex("(?:^|;)\\s*filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.trim()?.trim('"')
        return sanitize(decoded ?: quoted ?: plain)
    }

    fun fromUrl(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        val pathName = decode(uri.rawPath.orEmpty().substringAfterLast('/'))
        val queryName = uri.rawQuery.orEmpty().split('&').asSequence().mapNotNull { item ->
            val key = decode(item.substringBefore('=')).lowercase()
            if (key in setOf("filename", "file", "name", "download")) decode(item.substringAfter('=', "")) else null
        }.firstOrNull { it.isNotBlank() }
        sanitize(queryName ?: pathName)
    }.getOrNull()

    fun resolve(contentDisposition: String?, url: String, mimeType: String?): String {
        val candidate = fromContentDisposition(contentDisposition) ?: fromUrl(url) ?: "download"
        if (candidate.substringAfterLast('.', "").isNotBlank()) return candidate
        val extension = knownExtensions[mimeType?.substringBefore(';')?.trim()?.lowercase()]
        return if (extension == null) candidate else sanitize("$candidate.$extension") ?: candidate
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
    fun sanitize(input: String?): String? = input?.trim()?.trim('"')?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.replace(forbidden, "_")?.trim()?.trim('.')?.take(180)?.takeIf { it.isNotBlank() && it != "." && it != ".." }
}
