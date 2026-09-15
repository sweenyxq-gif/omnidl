package com.omnidownloader.data.userscript

import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.Resolver
import com.omnidownloader.domain.userscript.UserscriptMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserscriptResolver @Inject constructor(
    private val engine: UserscriptEngine,
    private val storage: UserscriptStorage,
    @ApplicationContext private val context: Context? = null
) : Resolver {
    override val id: String = "userscript_resolver"
    override val priority: Int = 90

    private val _installedScripts = MutableStateFlow<Map<String, UserscriptMetadata>>(emptyMap())
    val installedScripts: StateFlow<Map<String, UserscriptMetadata>> = _installedScripts.asStateFlow()

    init {
        // Also migrates away entries saved by older builds that were ordinary browser
        // userscripts or targeted an unsupported Omni API version.
        val loaded = storage.loadScripts().filter(::isInstallable).associateBy { it.id }.toMutableMap()

        // Pre-load bundled userscripts from assets so all curated resolvers work out of the box
        if (context != null) {
            try {
                val files = context.assets.list("userscripts") ?: emptyArray()
                for (file in files) {
                    if (file.endsWith(".user.js")) {
                        try {
                            val scriptText = context.assets.open("userscripts/$file").bufferedReader().use { it.readText() }
                            UserscriptMetadataParser.parse(scriptText)?.let { parsed ->
                                if (isInstallable(parsed)) {
                                    val packaged = parsed.copy(builtIn = true)
                                    val saved = loaded[packaged.id]
                                    loaded[packaged.id] = when {
                                        saved == null -> packaged
                                        compareVersions(saved.version, packaged.version) > 0 ->
                                            saved.copy(builtIn = true)
                                        else -> packaged.copy(enabled = saved.enabled)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore individual script failure
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore assets list failure
            }
        }

        _installedScripts.value = loaded
        persist()
    }

    fun registerScript(rawScript: String): UserscriptMetadata? {
        if (rawScript.length > 1_000_000) return null
        val meta = UserscriptMetadataParser.parse(rawScript) ?: return null
        if (!isInstallable(meta) || _installedScripts.value[meta.id]?.builtIn == true) return null
        _installedScripts.value = _installedScripts.value + (meta.id to meta)
        persist()
        return meta
    }

    /** Replaces exactly one installed extension while preserving its source and enabled state. */
    fun replaceScript(expectedId: String, rawScript: String): UserscriptMetadata? {
        if (rawScript.length > 1_000_000) return null
        val current = _installedScripts.value[expectedId] ?: return null
        val parsed = UserscriptMetadataParser.parse(rawScript) ?: return null
        if (parsed.id != expectedId || !isInstallable(parsed)) return null
        val replacement = parsed.copy(enabled = current.enabled, builtIn = current.builtIn)
        _installedScripts.value = _installedScripts.value + (expectedId to replacement)
        persist()
        return replacement
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
        }.sortedBy { script -> script.matches.any { it == "*://*/*" || it == "<all_urls>" } }
        if (matchedScripts.isEmpty()) return ResolveResult.Unsupported(request.url)

        val failures = mutableListOf<String>()
        val results = matchedScripts.flatMap { script ->
            runCatching { engine.execute(script, request.url) }
                .fold(
                    onSuccess = { execution ->
                        if (!execution.success) failures += "${script.name}: ${execution.error ?: "no results"}"
                        execution.resolvedItems
                    },
                    onFailure = { error ->
                        failures += "${script.name}: ${error.message ?: "execution failed"}"
                        emptyList()
                    }
                )
        }.distinctBy { it.url }
        return if (results.isNotEmpty()) {
            ResolveResult.Success(
                originalUrl = request.url,
                results = results
            )
        } else {
            ResolveResult.Failed(
                originalUrl = request.url,
                reason = failures.take(3).joinToString("; ").ifBlank {
                    "Matching scripts did not produce any resolved items"
                }
            )
        }
    }

    fun isInstallable(meta: UserscriptMetadata): Boolean {
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
            meta.isOmniResolver &&
            meta.omniApiVersion == 1 &&
            (meta.matches.isNotEmpty() || meta.includes.isNotEmpty()) &&
            meta.grants.all { it in supportedGrants }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val rightParts = right.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val comparison = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun persist() = storage.saveScripts(_installedScripts.value.values)
}
