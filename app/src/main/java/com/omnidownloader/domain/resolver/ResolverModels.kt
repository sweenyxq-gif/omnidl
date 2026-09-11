package com.omnidownloader.domain.resolver

import com.omnidownloader.domain.model.SourceType

data class ResolveRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap()
)

data class ResolvedItem(
    val label: String?,
    val url: String,
    val filename: String?,
    val type: SourceType = SourceType.HTTP,
    val mimeType: String? = null,
    val size: Long? = null,
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap()
)

sealed interface ResolveResult {
    data class Success(
        val originalUrl: String,
        val results: List<ResolvedItem>
    ) : ResolveResult

    data class Failed(
        val originalUrl: String,
        val reason: String
    ) : ResolveResult

    data class Unsupported(
        val url: String
    ) : ResolveResult
}

interface Resolver {
    val id: String
    val priority: Int get() = 0
    fun canHandle(url: String): Boolean
    suspend fun resolve(request: ResolveRequest): ResolveResult
}
