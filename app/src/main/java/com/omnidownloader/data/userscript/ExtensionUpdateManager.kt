package com.omnidownloader.data.userscript

import android.content.Context
import com.omnidownloader.domain.userscript.ExtensionUpdateInfo
import com.omnidownloader.domain.userscript.UserscriptMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionUpdateManager @Inject constructor(
    private val client: OkHttpClient,
    private val resolver: UserscriptResolver,
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val DEFAULT_REPOSITORY_URL =
            "https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/extensions.json"
    }

    suspend fun fetchScriptFromUrl(url: String): String = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            "Only HTTP and HTTPS URLs are supported"
        }
        val normalized = normalizeUrl(trimmed)
        val urlsToTry = mutableListOf(normalized)

        // If it is a raw.githubusercontent.com URL, also try jsDelivr CDN fallback to bypass regional ISP blocks
        val rawGhRegex = Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.*)$""", RegexOption.IGNORE_CASE)
        val match = rawGhRegex.matchEntire(normalized)
        if (match != null) {
            val user = match.groupValues[1]
            val repo = match.groupValues[2]
            val branch = match.groupValues[3]
            val path = match.groupValues[4]
            urlsToTry.add(0, "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
        }

        var lastError: Exception? = null
        for (targetUrl in urlsToTry) {
            try {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36")
                    .build()
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyText = response.body?.string().orEmpty()
                        if (bodyText.isNotBlank()) {
                            return@withContext bodyText
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Fallback: Check if the requested script is available in bundled assets
        getBundledScriptByUrl(trimmed)?.let { return@withContext it }

        throw lastError ?: java.io.IOException("Failed to fetch script from $trimmed")
    }

    fun getBundledScriptByUrl(inputUrl: String): String? {
        val clean = inputUrl.substringBefore('?').substringBefore('#')
        val filename = clean.substringAfterLast('/')
        if (filename.endsWith(".user.js")) {
            return try {
                context.assets.open("userscripts/$filename").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    suspend fun checkScriptForUpdate(installed: UserscriptMetadata): ExtensionUpdateInfo? = withContext(Dispatchers.IO) {
        val targetUrl = installed.updateUrl?.ifBlank { null } ?: installed.downloadUrl?.ifBlank { null } ?: return@withContext null
        try {
            val remoteCode = fetchScriptFromUrl(targetUrl)
            val remoteMeta = UserscriptMetadataParser.parse(remoteCode) ?: return@withContext null
            if (isNewerVersion(remoteMeta.version, installed.version)) {
                val newConnects = remoteMeta.connects - installed.connects.toSet()
                val newGrants = remoteMeta.grants - installed.grants
                val newPermissions = buildList {
                    newConnects.forEach { add("network:$it") }
                    newGrants.forEach { add("grant:$it") }
                }
                ExtensionUpdateInfo(
                    scriptId = installed.id,
                    name = remoteMeta.name,
                    currentVersion = installed.version,
                    newVersion = remoteMeta.version,
                    updateSourceUrl = targetUrl,
                    newScriptCode = remoteCode,
                    newMetadata = remoteMeta,
                    newPermissions = newPermissions
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun checkAllInstalledForUpdates(
        repoUrl: String = DEFAULT_REPOSITORY_URL,
        installedScripts: List<UserscriptMetadata>
    ): List<ExtensionUpdateInfo> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<ExtensionUpdateInfo>()

        // 1. Check individual @updateURL / @downloadURL
        installedScripts.forEach { script ->
            checkScriptForUpdate(script)?.let { updates.add(it) }
        }

        // 2. Check catalog repo manifest if available
        try {
            val req = Request.Builder().url(repoUrl.trim()).build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val entries = parseRepoIndex(body)
                    entries.forEach { entry ->
                        val matchingLocal = installedScripts.firstOrNull { it.id == entry.id }
                        if (matchingLocal != null && updates.none { it.scriptId == entry.id }) {
                            if (isNewerVersion(entry.version, matchingLocal.version)) {
                                val scriptCode = fetchScriptFromUrl(entry.scriptUrl)
                                val meta = UserscriptMetadataParser.parse(scriptCode)
                                if (meta != null) {
                                    val newConnects = meta.connects - matchingLocal.connects.toSet()
                                    val newGrants = meta.grants - matchingLocal.grants
                                    val newPermissions = buildList {
                                        newConnects.forEach { add("network:$it") }
                                        newGrants.forEach { add("grant:$it") }
                                    }
                                    updates.add(
                                        ExtensionUpdateInfo(
                                            scriptId = matchingLocal.id,
                                            name = meta.name,
                                            currentVersion = matchingLocal.version,
                                            newVersion = entry.version,
                                            updateSourceUrl = entry.scriptUrl,
                                            newScriptCode = scriptCode,
                                            newMetadata = meta,
                                            newPermissions = newPermissions
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Repo check is optional / graceful fallback
        }

        updates.distinctBy { it.scriptId }
    }

    fun applyUpdate(update: ExtensionUpdateInfo): Boolean {
        val registered = resolver.registerScript(update.newScriptCode)
        return registered != null
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = remoteVersion.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val currentParts = currentVersion.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun parseRepoIndex(jsonStr: String): List<RepoEntry> {
        val result = mutableListOf<RepoEntry>()
        try {
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("extensions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id")
                val version = item.optString("version")
                val scriptUrl = item.optString("scriptUrl").ifBlank { item.optString("url") }
                if (id.isNotBlank() && version.isNotBlank() && scriptUrl.isNotBlank()) {
                    result.add(RepoEntry(id, version, scriptUrl))
                }
            }
        } catch (e: Exception) {
            // try top-level array
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optString("id")
                    val version = item.optString("version")
                    val scriptUrl = item.optString("scriptUrl").ifBlank { item.optString("url") }
                    if (id.isNotBlank() && version.isNotBlank() && scriptUrl.isNotBlank()) {
                        result.add(RepoEntry(id, version, scriptUrl))
                    }
                }
            } catch (e2: Exception) {
                // Ignore malformed repo json
            }
        }
        return result
    }

    fun normalizeUrl(inputUrl: String): String {
        var url = inputUrl.trim()
        val blobRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$""", RegexOption.IGNORE_CASE)
        val blobMatch = blobRegex.matchEntire(url)
        if (blobMatch != null) {
            val user = blobMatch.groupValues[1]
            val repo = blobMatch.groupValues[2]
            val branch = blobMatch.groupValues[3]
            val path = blobMatch.groupValues[4]
            return "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
        }

        val repoRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/?(\.git)?$""", RegexOption.IGNORE_CASE)
        val repoMatch = repoRegex.matchEntire(url)
        if (repoMatch != null) {
            val user = repoMatch.groupValues[1]
            val repo = repoMatch.groupValues[2]
            return "https://raw.githubusercontent.com/$user/$repo/main/extensions.json"
        }

        return url
    }

    fun loadBundledCatalog(): List<UserscriptMetadata> {
        return try {
            val jsonStr = context.assets.open("extensions.json").bufferedReader().use { it.readText() }
            parseCatalogManifest(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchRepositoryCatalog(repoUrl: String = DEFAULT_REPOSITORY_URL): List<UserscriptMetadata> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(repoUrl)
            val jsonStr = fetchScriptFromUrl(normalized)
            val items = parseCatalogManifest(jsonStr)
            if (items.isNotEmpty()) items else loadBundledCatalog()
        } catch (e: Exception) {
            loadBundledCatalog()
        }
    }

    fun parseCatalogManifest(jsonStr: String): List<UserscriptMetadata> {
        val list = mutableListOf<UserscriptMetadata>()
        val array = try {
            val root = JSONObject(jsonStr)
            root.optJSONArray("extensions") ?: JSONArray()
        } catch (e: Exception) {
            try { JSONArray(jsonStr) } catch (e2: Exception) { JSONArray() }
        }

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").ifBlank { "Unnamed" }
            val namespace = obj.optString("namespace").ifBlank { null }
            val version = obj.optString("version").ifBlank { "1.0.0" }
            val category = obj.optString("category").ifBlank { "general" }
            val author = obj.optString("author").ifBlank { null }
            val description = obj.optString("description").ifBlank { null }
            val scriptUrl = obj.optString("scriptUrl").ifBlank { obj.optString("url") }
            val filename = obj.optString("filename").ifBlank { null }

            val matches = mutableListOf<String>()
            obj.optJSONArray("matches")?.let { ma ->
                for (j in 0 until ma.length()) matches.add(ma.getString(j))
            }
            val includes = mutableListOf<String>()
            obj.optJSONArray("includes")?.let { ia ->
                for (j in 0 until ia.length()) includes.add(ia.getString(j))
            }
            val connects = mutableListOf<String>()
            obj.optJSONArray("connects")?.let { ca ->
                for (j in 0 until ca.length()) connects.add(ca.getString(j))
            }

            val bundledCode = if (filename != null) {
                try {
                    context.assets.open("userscripts/$filename").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                }
            } else ""

            val parsedBundled = if (bundledCode.isNotBlank()) UserscriptMetadataParser.parse(bundledCode) else null
            val finalId = parsedBundled?.id ?: UserscriptMetadataParser.buildScriptId(namespace, name)

            list.add(
                UserscriptMetadata(
                    id = finalId,
                    name = parsedBundled?.name ?: name,
                    namespace = parsedBundled?.namespace ?: namespace,
                    version = parsedBundled?.version ?: version,
                    category = category,
                    author = parsedBundled?.author ?: author,
                    description = parsedBundled?.description ?: description,
                    matches = if (parsedBundled != null && parsedBundled.matches.isNotEmpty()) parsedBundled.matches else matches,
                    includes = if (parsedBundled != null && parsedBundled.includes.isNotEmpty()) parsedBundled.includes else includes,
                    connects = if (parsedBundled != null && parsedBundled.connects.isNotEmpty()) parsedBundled.connects else connects,
                    grants = parsedBundled?.grants ?: emptySet(),
                    rawScript = bundledCode,
                    downloadUrl = scriptUrl,
                    updateUrl = parsedBundled?.updateUrl ?: scriptUrl,
                    isOmniResolver = true,
                    omniApiVersion = 1,
                    builtIn = false
                )
            )
        }
        return list
    }

    private data class RepoEntry(val id: String, val version: String, val scriptUrl: String)
}
