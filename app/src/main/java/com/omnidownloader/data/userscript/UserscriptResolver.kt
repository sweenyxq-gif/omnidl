package com.omnidownloader.data.userscript

import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.Resolver
import com.omnidownloader.domain.userscript.UserscriptMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserscriptResolver @Inject constructor(
    private val engine: UserscriptEngine
) : Resolver {
    override val id: String = "userscript_resolver"
    override val priority: Int = 90

    private val _installedScripts = MutableStateFlow<Map<String, UserscriptMetadata>>(emptyMap())
    val installedScripts: StateFlow<Map<String, UserscriptMetadata>> = _installedScripts.asStateFlow()

    init {
        // Pre-load a sample built-in resolver: GitHub Release direct artifact resolver
        val sampleGithubResolver = """
            // ==UserScript==
            // @name         GitHub Release Direct Resolver
            // @namespace    https://omni.downloader/resolvers/github
            // @version      1.0.0
            // @description  Resolves direct download links for GitHub releases
            // @match        *://github.com/*/*/releases/tag/*
            // @grant        GM_xmlhttpRequest
            // @grant        GM_log
            // @connect      github.com
            // @connect      *.githubusercontent.com
            // @omni-resolver true
            // @omni-api     1
            // @omni-category archives
            // ==/UserScript==

            omni.log("GitHub release resolver matched: " + targetUrl);
            var response = await omni.fetch(targetUrl);
            var text = await response.text();

            // Look for release assets in HTML
            var assetRegex = /href=["']([^"']+\/releases\/download\/[^"']+)["']/g;
            var match;
            var found = false;
            while ((match = assetRegex.exec(text)) !== null) {
                var assetUrl = "https://github.com" + match[1];
                var filename = assetUrl.substring(assetUrl.lastIndexOf('/') + 1);
                omni.log("Discovered GitHub release asset: " + filename);
                omni.resolve({
                    url: assetUrl,
                    label: "GitHub Release Asset (" + filename + ")",
                    filename: filename
                });
                found = true;
            }
            if (!found) {
                omni.log("No downloadable assets found in release tag.");
            }
        """.trimIndent()
        registerScript(sampleGithubResolver)
    }

    fun registerScript(rawScript: String): UserscriptMetadata? {
        val meta = UserscriptMetadataParser.parse(rawScript) ?: return null
        if (!meta.isOmniResolver) return null
        _installedScripts.value = _installedScripts.value + (meta.id to meta)
        return meta
    }

    fun unregisterScript(id: String) {
        _installedScripts.value = _installedScripts.value - id
    }

    fun getScript(id: String): UserscriptMetadata? = _installedScripts.value[id]

    override fun canHandle(url: String): Boolean {
        return _installedScripts.value.values.any { meta ->
            UserscriptMetadataParser.matchesUrl(meta, url)
        }
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult {
        val matchedScript = _installedScripts.value.values.firstOrNull { meta ->
            UserscriptMetadataParser.matchesUrl(meta, request.url)
        } ?: return ResolveResult.Unsupported(request.url)

        val result = engine.execute(matchedScript, request.url)
        return if (result.success && result.resolvedItems.isNotEmpty()) {
            ResolveResult.Success(
                originalUrl = request.url,
                results = result.resolvedItems
            )
        } else {
            ResolveResult.Failed(
                originalUrl = request.url,
                reason = result.error ?: "Script execution did not produce any resolved items"
            )
        }
    }
}
