package com.omnidownloader.download.resolver

data class ResolverResult(val directUrl: String, val fileName: String? = null, val size: Long? = null, val mimeType: String? = null, val headers: Map<String, String> = emptyMap(), val cookies: String? = null, val expiresAt: Long? = null)
interface LinkResolver { val id: String; fun canHandle(url: String): Boolean; suspend fun resolve(url: String): ResolverResult }

class DirectLinkResolver : LinkResolver {
    override val id = "direct"
    override fun canHandle(url: String) = runCatching { java.net.URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)
    override suspend fun resolve(url: String) = ResolverResult(directUrl = url)
}

class ResolverRegistry(private val resolvers: List<LinkResolver>) { fun select(url: String): LinkResolver? = resolvers.firstOrNull { it.canHandle(url) } }
