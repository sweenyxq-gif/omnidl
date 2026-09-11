package com.omnidownloader.data.inspector

import com.omnidownloader.domain.inspector.LinkInspection
import com.omnidownloader.domain.inspector.RedirectHop
import com.omnidownloader.download.http.FileNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinkInspector @Inject constructor(
    private val client: OkHttpClient
) {
    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun inspect(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): LinkInspection = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var currentUrl = url
        val hops = mutableListOf<RedirectHop>()
        var redirectCount = 0
        val maxHops = 10
        var terminalResponse: Response? = null

        val cookieHeaderValue = if (cookies.isNotEmpty()) {
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else headers["Cookie"]

        while (redirectCount < maxHops) {
            val reqBuilder = Request.Builder().url(currentUrl).head()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (!cookieHeaderValue.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookieHeaderValue)
            }

            var response: Response
            try {
                response = noRedirectClient.newCall(reqBuilder.build()).execute()
                // If HEAD fails with 405 Method Not Allowed or 403, fallback to GET Range probe
                if (response.code in setOf(403, 405)) {
                    response.close()
                    val getReq = reqBuilder.get().header("Range", "bytes=0-0").build()
                    response = noRedirectClient.newCall(getReq).execute()
                }
            } catch (e: Exception) {
                // If HEAD failed outright due to network error or protocol error, attempt GET
                val getReq = Request.Builder().url(currentUrl).get().header("Range", "bytes=0-0")
                headers.forEach { (k, v) -> getReq.header(k, v) }
                if (!cookieHeaderValue.isNullOrBlank()) {
                    getReq.header("Cookie", cookieHeaderValue)
                }
                response = noRedirectClient.newCall(getReq.build()).execute()
            }

            val statusCode = response.code
            val isRedirect = statusCode in setOf(301, 302, 303, 307, 308)
            val location = response.header("Location")

            hops.add(RedirectHop(currentUrl, statusCode, location))

            if (isRedirect && !location.isNullOrBlank()) {
                redirectCount++
                val resolvedNext = resolveRelativeUrl(currentUrl, location)
                response.close()
                currentUrl = resolvedNext
            } else {
                terminalResponse = response
                break
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val res = terminalResponse ?: throw java.io.IOException("Too many redirects (exceeded $maxHops)")

        res.use { response ->
            val finalUrl = response.request.url.toString()
            val disposition = response.header("Content-Disposition")
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
            val rawLength = response.header("Content-Length")?.toLongOrNull()
            val contentRangeTotal = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val contentLength = contentRangeTotal ?: rawLength ?: -1L
            val filename = FileNameParser.resolve(disposition, finalUrl, contentType)
            val server = response.header("Server")
            val acceptRanges = response.code == 206 ||
                    response.header("Accept-Ranges")?.contains("bytes", true) == true
            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            val contentEncoding = response.header("Content-Encoding")
            val tlsVersion = response.handshake?.tlsVersion?.javaName

            val responseHeadersMap = mutableMapOf<String, MutableList<String>>()
            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                val value = response.headers.value(i)
                responseHeadersMap.getOrPut(name) { mutableListOf() }.add(value)
            }

            val parsedCookies = mutableMapOf<String, String>()
            response.headers("Set-Cookie").forEach { cookieStr ->
                val pair = cookieStr.substringBefore(';').trim()
                val eqIdx = pair.indexOf('=')
                if (eqIdx > 0) {
                    val k = pair.substring(0, eqIdx).trim()
                    val v = pair.substring(eqIdx + 1).trim()
                    parsedCookies[k] = v
                }
            }

            LinkInspection(
                url = url,
                finalUrl = finalUrl,
                redirectCount = redirectCount,
                redirectChain = hops,
                httpStatus = response.code,
                contentType = contentType,
                contentLength = contentLength,
                filename = filename,
                server = server,
                acceptRanges = acceptRanges,
                etag = etag,
                lastModified = lastModified,
                contentEncoding = contentEncoding,
                tlsVersion = tlsVersion,
                cookies = parsedCookies,
                responseHeaders = responseHeadersMap,
                latencyMs = elapsed
            )
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, location: String): String {
        return try {
            val base = URI(baseUrl)
            base.resolve(location).toASCIIString()
        } catch (e: Exception) {
            location
        }
    }
}
