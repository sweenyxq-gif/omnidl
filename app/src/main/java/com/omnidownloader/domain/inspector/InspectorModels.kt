package com.omnidownloader.domain.inspector

data class RedirectHop(
    val url: String,
    val statusCode: Int,
    val location: String? = null
)

data class LinkInspection(
    val url: String,
    val finalUrl: String,
    val redirectCount: Int,
    val redirectChain: List<RedirectHop> = emptyList(),
    val httpStatus: Int,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val filename: String? = null,
    val server: String? = null,
    val acceptRanges: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentEncoding: String? = null,
    val tlsVersion: String? = null,
    val cookies: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, List<String>> = emptyMap(),
    val latencyMs: Long = 0
)
