package com.omnidownloader.data.userscript

import com.omnidownloader.domain.userscript.ScriptNetworkLog
import com.omnidownloader.domain.userscript.UserscriptMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SafeHttpResponse(
    val statusCode: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val bodyText: String,
    val finalUrl: String,
    val latencyMs: Long
)

@Singleton
class ResolverNetworkSandbox @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        const val MAX_RESPONSE_BYTES: Long = 10 * 1024 * 1024 // 10 MB
        const val DEFAULT_TIMEOUT_SECONDS: Long = 15
        val BLOCKED_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
    }

    private val sandboxClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun executeSafeRequest(
        metadata: UserscriptMetadata,
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        onLog: ((ScriptNetworkLog) -> Unit)? = null
    ): SafeHttpResponse = withContext(Dispatchers.IO) {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid request URL: $url")
        }

        val host = uri.host?.lowercase().orEmpty()
        val scheme = uri.scheme?.lowercase().orEmpty()

        if (scheme !in setOf("http", "https")) {
            throw SecurityException("Sandbox blocked: Only HTTP and HTTPS schemes are permitted, got '$scheme'")
        }

        if (isBlockedHost(host)) {
            throw SecurityException("Sandbox blocked: Connections to localhost and private networks are forbidden ($host)")
        }

        if (!isDomainPermitted(metadata, host)) {
            throw SecurityException("Sandbox blocked: Host '$host' is not permitted by @connect declarations")
        }

        val httpMethod = method.trim().uppercase()
        val validMethods = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS")
        if (httpMethod !in validMethods) {
            throw IllegalArgumentException("Unsupported HTTP method: $httpMethod")
        }

        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }

        val reqBody = if (body != null && httpMethod in setOf("POST", "PUT")) {
            val contentType = (headers["Content-Type"] ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
            body.toRequestBody(contentType)
        } else null

        reqBuilder.method(httpMethod, reqBody)

        val startTime = System.currentTimeMillis()
        val response = sandboxClient.newCall(reqBuilder.build()).execute()
        val latency = System.currentTimeMillis() - startTime

        response.use { res ->
            val finalUrl = res.request.url.toString()
            val statusCode = res.code
            val statusText = res.message

            val headersMap = mutableMapOf<String, String>()
            for (i in 0 until res.headers.size) {
                headersMap[res.headers.name(i)] = res.headers.value(i)
            }

            val bodySource = res.body?.source()
            val bodyString = if (bodySource != null) {
                val buffer = okio.Buffer()
                bodySource.read(buffer, MAX_RESPONSE_BYTES)
                buffer.readUtf8()
            } else ""

            val networkLog = ScriptNetworkLog(
                method = httpMethod,
                url = url,
                statusCode = statusCode,
                latencyMs = latency
            )
            onLog?.invoke(networkLog)

            SafeHttpResponse(
                statusCode = statusCode,
                statusText = statusText,
                headers = headersMap,
                bodyText = bodyString,
                finalUrl = finalUrl,
                latencyMs = latency
            )
        }
    }

    fun isDomainPermitted(metadata: UserscriptMetadata, host: String): Boolean {
        if (metadata.connects.isEmpty()) {
            // If @connect is omitted, check if host matches any @match declaration
            return metadata.matches.any { match ->
                val matchHost = URI(match.replace("*://", "http://").replace("/*", "")).host?.lowercase().orEmpty()
                matchHost == host || (matchHost.startsWith("*.") && host.endsWith(matchHost.substring(1)))
            }
        }

        return metadata.connects.any { connectRule ->
            val rule = connectRule.trim().lowercase()
            when {
                rule == "*" -> true
                rule == host -> true
                rule.startsWith("*.") -> {
                    val root = rule.substring(2)
                    host == root || host.endsWith(".$root")
                }
                else -> host == rule
            }
        }
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host in BLOCKED_HOSTS) return true
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true
        if (host.startsWith("172.")) {
            val secondOctet = host.substringAfter("172.").substringBefore('.').toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) return true
        }
        return false
    }
}
