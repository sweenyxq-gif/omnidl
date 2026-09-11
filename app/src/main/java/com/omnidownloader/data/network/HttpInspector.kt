package com.omnidownloader.data.network

import com.omnidownloader.domain.model.DownloadPreview
import com.omnidownloader.domain.model.DownloadSource
import com.omnidownloader.download.core.SourceDetector
import com.omnidownloader.download.http.FileNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class HttpInspector @Inject constructor(private val client: OkHttpClient, private val detector: SourceDetector) {
    suspend fun inspect(url: String, headers: Map<String, String> = emptyMap()): DownloadPreview = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).head()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { head ->
            if (!head.isSuccessful && head.code !in setOf(403, 405)) throw java.io.IOException("Server returned HTTP ${head.code}")
            val needsProbe = !head.isSuccessful || head.header("Content-Disposition").isNullOrBlank()
            if (needsProbe) {
                val get = Request.Builder().url(head.request.url).header("Range", "bytes=0-0").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                client.newCall(get).execute().use { probe ->
                    if (probe.isSuccessful) return@withContext preview(probe, head)
                    if (!head.isSuccessful) throw java.io.IOException("Server returned HTTP ${probe.code}")
                }
            }
            preview(head)
        }
    }

    private fun preview(primary: okhttp3.Response, fallback: okhttp3.Response? = null): DownloadPreview {
        val finalUrl = primary.request.url.toString()
        val mime = (primary.header("Content-Type") ?: fallback?.header("Content-Type"))?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" }
        val disposition = primary.header("Content-Disposition") ?: fallback?.header("Content-Disposition")
        val contentRangeTotal = primary.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        val size = contentRangeTotal ?: primary.header("Content-Length")?.toLongOrNull() ?: fallback?.header("Content-Length")?.toLongOrNull() ?: -1
        val ranges = primary.code == 206 || primary.header("Accept-Ranges")?.contains("bytes", true) == true || fallback?.header("Accept-Ranges")?.contains("bytes", true) == true
        val source = detector.detect(finalUrl, mime) ?: error("Unsupported source")
        return DownloadPreview(source, FileNameParser.resolve(disposition, finalUrl, mime), size, mime, ranges, finalUrl)
    }
}
