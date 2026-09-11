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
class RedirectResolver @Inject constructor(
    private val client: OkHttpClient
) : Resolver {
    override val id: String = "redirect_resolver"
    override val priority: Int = 80

    companion object {
        const val MAX_CHAIN_DEPTH = 5
    }

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override fun canHandle(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        var currentUrl = request.url.trim()
        val visited = mutableSetOf<String>()
        var depth = 0

        while (depth < MAX_CHAIN_DEPTH) {
            if (visited.contains(currentUrl)) {
                return@withContext ResolveResult.Failed(
                    originalUrl = request.url,
                    reason = "Resolver loop detected: $currentUrl already visited"
                )
            }
            visited.add(currentUrl)

            val reqBuilder = Request.Builder().url(currentUrl).head()
            request.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (request.cookies.isNotEmpty()) {
                reqBuilder.header("Cookie", request.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }

            val response = try {
                noRedirectClient.newCall(reqBuilder.build()).execute()
            } catch (e: Exception) {
                // If HEAD fails, try a range GET
                val getReq = Request.Builder().url(currentUrl).get().header("Range", "bytes=0-0")
                request.headers.forEach { (k, v) -> getReq.header(k, v) }
                try {
                    noRedirectClient.newCall(getReq.build()).execute()
                } catch (e2: Exception) {
                    return@withContext ResolveResult.Failed(
                        originalUrl = request.url,
                        reason = "Network probe failed: ${e2.message}"
                    )
                }
            }

            response.use { res ->
                val code = res.code
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = res.header("Location")
                    if (location.isNullOrBlank()) {
                        return@withContext ResolveResult.Failed(
                            originalUrl = request.url,
                            reason = "Redirect response HTTP $code missing Location header"
                        )
                    }
                    val nextUrl = resolveRelativeUrl(currentUrl, location)
                    currentUrl = nextUrl
                    depth++
                } else {
                    // Terminal response reached
                    if (currentUrl != request.url.trim()) {
                        val contentType = res.header("Content-Type")?.substringBefore(';')?.trim()
                        val disposition = res.header("Content-Disposition")
                        val size = res.header("Content-Length")?.toLongOrNull()
                        val filename = FileNameParser.resolve(disposition, currentUrl, contentType)

                        val item = ResolvedItem(
                            label = "Redirected Destination",
                            url = currentUrl,
                            filename = filename,
                            type = SourceType.HTTP,
                            mimeType = contentType,
                            size = size,
                            headers = request.headers,
                            cookies = request.cookies
                        )
                        return@withContext ResolveResult.Success(
                            originalUrl = request.url,
                            results = listOf(item)
                        )
                    } else {
                        // No redirects occurred
                        return@withContext ResolveResult.Unsupported(request.url)
                    }
                }
            }
        }

        ResolveResult.Failed(
            originalUrl = request.url,
            reason = "Maximum redirect chain depth ($MAX_CHAIN_DEPTH) exceeded"
        )
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
