package com.omnidownloader.data.resolver

import com.omnidownloader.data.userscript.UserscriptResolver
import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.Resolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResolverManager @Inject constructor(
    private val directUrlResolver: DirectUrlResolver,
    private val youTubeResolver: YouTubeResolver,
    private val userscriptResolver: UserscriptResolver,
    private val redirectResolver: RedirectResolver,
    private val genericWebpageResolver: GenericWebpageResolver
) {
    private val builtInResolvers: List<Resolver> = listOf(
        directUrlResolver,
        youTubeResolver,
        userscriptResolver,
        redirectResolver,
        genericWebpageResolver
    ).sortedByDescending { it.priority }

    suspend fun resolve(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): ResolveResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return@withContext ResolveResult.Failed(trimmed, "URL cannot be empty")
        }

        val request = ResolveRequest(
            url = trimmed,
            headers = headers,
            cookies = cookies
        )

        val errors = mutableListOf<String>()

        for (resolver in builtInResolvers) {
            if (!resolver.canHandle(request.url)) continue

            try {
                when (val result = resolver.resolve(request)) {
                    is ResolveResult.Success -> {
                        if (result.results.isNotEmpty()) {
                            return@withContext result
                        }
                    }
                    is ResolveResult.Failed -> {
                        errors.add("[${resolver.id}]: ${result.reason}")
                    }
                    is ResolveResult.Unsupported -> {
                        // Fall through to next resolver in the chain
                    }
                }
            } catch (e: Exception) {
                errors.add("[${resolver.id}] Error: ${e.message}")
            }
        }

        if (errors.isNotEmpty()) {
            ResolveResult.Failed(
                originalUrl = trimmed,
                reason = errors.joinToString("\n")
            )
        } else {
            ResolveResult.Unsupported(trimmed)
        }
    }
}
