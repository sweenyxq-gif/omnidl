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
    private val engine: UserscriptEngine,
    private val storage: UserscriptStorage,
) : Resolver {
    override val id: String = "userscript_resolver"
    override val priority: Int = 90

    private val _installedScripts = MutableStateFlow<Map<String, UserscriptMetadata>>(emptyMap())
    val installedScripts: StateFlow<Map<String, UserscriptMetadata>> = _installedScripts.asStateFlow()

    init {
        _installedScripts.value = storage.loadScripts().associateBy { it.id }
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
        UserscriptMetadataParser.parse(sampleGithubResolver)?.copy(builtIn = true)?.let { builtIn ->
            _installedScripts.value = _installedScripts.value + (builtIn.id to builtIn)
        }
    }

    fun registerScript(rawScript: String): UserscriptMetadata? {
        if (rawScript.length > 1_000_000) return null
        val meta = UserscriptMetadataParser.parse(rawScript) ?: return null
        if (!isInstallable(meta) || _installedScripts.value[meta.id]?.builtIn == true) return null
        _installedScripts.value = _installedScripts.value + (meta.id to meta)
        persist()
        return meta
    }

    fun unregisterScript(id: String): Boolean {
        if (_installedScripts.value[id]?.builtIn == true) return false
        _installedScripts.value = _installedScripts.value - id
        storage.clear(id)
        persist()
        return true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val script = _installedScripts.value[id] ?: return
        _installedScripts.value = _installedScripts.value + (id to script.copy(enabled = enabled))
        persist()
    }

    fun getScript(id: String): UserscriptMetadata? = _installedScripts.value[id]

    override fun canHandle(url: String): Boolean {
        return _installedScripts.value.values.any { meta ->
            meta.enabled &&
            UserscriptMetadataParser.matchesUrl(meta, url)
        }
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult {
        val matchedScripts = _installedScripts.value.values.filter { meta ->
            meta.enabled && UserscriptMetadataParser.matchesUrl(meta, request.url)
        }
        if (matchedScripts.isEmpty()) return ResolveResult.Unsupported(request.url)

        val results = matchedScripts.flatMap { script ->
            val execution = engine.execute(script, request.url)
            if (execution.success) execution.resolvedItems else emptyList()
        }.distinctBy { it.url }
        return if (results.isNotEmpty()) {
            ResolveResult.Success(
                originalUrl = request.url,
                results = results
            )
        } else {
            ResolveResult.Failed(
                originalUrl = request.url,
                reason = "Matching scripts did not produce any resolved items"
            )
        }
    }

    private fun isInstallable(meta: UserscriptMetadata): Boolean {
        val supportedGrants = setOf(
            "GM_xmlhttpRequest",
            "GM_log",
            "GM_setValue",
            "GM_getValue",
            "GM_deleteValue",
            "GM_listValues",
            "GM_addStyle",
            "GM_openInTab",
            "GM.openInTab",
            "GM_download",
            "GM_setClipboard",
            "GM_notification",
            "GM_registerMenuCommand",
            "GM_info",
            "GM_getResourceText",
            "GM_getResourceURL",
            "unsafeWindow",
            "none"
        )
        return meta.id.isNotBlank() &&
            (meta.matches.isNotEmpty() || meta.includes.isNotEmpty()) &&
            meta.grants.all { it in supportedGrants }
    }

    private fun persist() = storage.saveScripts(_installedScripts.value.values)
}
