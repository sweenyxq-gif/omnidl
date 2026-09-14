package com.omnidownloader.data.userscript

import com.omnidownloader.domain.userscript.UserscriptMetadata
import java.util.regex.Pattern

object UserscriptMetadataParser {

    private val headerStartRegex = Regex("""//\s*==UserScript==""", RegexOption.IGNORE_CASE)
    private val headerEndRegex = Regex("""//\s*==/UserScript==""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""//\s*@([a-zA-Z0-9_\-.:]+)(?:\s+(.*))?""")

    fun parse(rawScript: String): UserscriptMetadata? {
        val cleanScript = rawScript.removePrefix("\uFEFF").trim()
        val lines = cleanScript.lineSequence()
        var insideHeader = false
        var closedHeader = false
        val tags = mutableMapOf<String, MutableList<String>>()

        for (line in lines) {
            val trimmed = line.trim().removePrefix("\uFEFF")
            if (!insideHeader) {
                if (headerStartRegex.containsMatchIn(trimmed)) {
                    insideHeader = true
                }
            } else {
                if (headerEndRegex.containsMatchIn(trimmed)) {
                    closedHeader = true
                    break
                }
                val match = tagRegex.matchEntire(trimmed)
                if (match != null) {
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    tags.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
        }

        if (tags.isEmpty() || !closedHeader) return null

        val name = tags["name"]?.firstOrNull()
            ?: tags.entries.firstOrNull { it.key.startsWith("name") }?.value?.firstOrNull()
            ?: "Unnamed Userscript"
        val namespace = tags["namespace"]?.firstOrNull()
        val version = tags["version"]?.firstOrNull() ?: "1.0.0"
        val description = tags["description"]?.firstOrNull()
            ?: tags.entries.firstOrNull { it.key.startsWith("description") }?.value?.firstOrNull()
        val author = tags["author"]?.firstOrNull()
        val matches = tags["match"].orEmpty()
        val includes = tags["include"].orEmpty()
        val excludes = tags["exclude"].orEmpty()
        val grants = tags["grant"].orEmpty().toSet()
        val connects = tags["connect"].orEmpty()

        val isOmniResolver = tags["omni-resolver"]?.firstOrNull()?.equals("false", ignoreCase = true) != true
        val omniApiVersion = tags["omni-api"]?.firstOrNull()?.toIntOrNull() ?: 1
        val category = tags["omni-category"]?.firstOrNull()
        val updateUrl = tags["updateurl"]?.firstOrNull()
        val downloadUrl = tags["downloadurl"]?.firstOrNull()

        val id = buildScriptId(namespace, name)

        return UserscriptMetadata(
            id = id,
            name = name,
            namespace = namespace,
            version = version,
            description = description,
            author = author,
            matches = matches,
            includes = includes,
            excludes = excludes,
            grants = grants,
            connects = connects,
            isOmniResolver = isOmniResolver,
            omniApiVersion = omniApiVersion,
            category = category,
            rawScript = rawScript,
            updateUrl = updateUrl,
            downloadUrl = downloadUrl,
        )
    }

    fun matchesUrl(metadata: UserscriptMetadata, url: String): Boolean {
        val trimmed = url.trim()
        if (metadata.excludes.any { matchPattern(it, trimmed) }) {
            return false
        }
        if (metadata.matches.any { matchPattern(it, trimmed) }) {
            return true
        }
        if (metadata.includes.any { matchPattern(it, trimmed) }) {
            return true
        }
        return false
    }

    fun matchPattern(patternStr: String, url: String): Boolean {
        val pattern = patternStr.trim()
        if (pattern == "<all_urls>") return true
        if (pattern == "*" || pattern == "*://*/*") return true

        return try {
            val regexStr = compilePatternToRegex(pattern)
            Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE).matcher(url).matches()
        } catch (e: Exception) {
            false
        }
    }

    private fun compilePatternToRegex(pattern: String): String {
        val schemeSplit = pattern.indexOf("://")
        if (schemeSplit == -1) {
            // Simple wildcard string
            return "^" + wildcardRegex(pattern) + "$"
        }

        val schemePart = pattern.substring(0, schemeSplit)
        val rest = pattern.substring(schemeSplit + 3)
        val pathSplit = rest.indexOf('/')

        val hostPart = if (pathSplit != -1) rest.substring(0, pathSplit) else rest
        val pathPart = if (pathSplit != -1) rest.substring(pathSplit) else "/*"

        val schemeRegex = when (schemePart) {
            "*" -> "(?:http|https)"
            else -> Regex.escape(schemePart)
        }

        val hostRegex = when {
            hostPart == "*" -> "[^/]+"
            hostPart.startsWith("*.") -> "(?:[^/]+\\.)?" + Regex.escape(hostPart.substring(2))
            else -> Regex.escape(hostPart)
        }

        val pathRegex = wildcardRegex(pathPart)

        return "^$schemeRegex://$hostRegex$pathRegex$"
    }

    private fun wildcardRegex(value: String): String = buildString {
        value.forEach { character ->
            if (character == '*') append(".*") else append(Regex.escape(character.toString()))
        }
    }

    fun buildScriptId(namespace: String?, name: String): String {
        val cleanName = name.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        val cleanNs = namespace?.lowercase()?.replace(Regex("[^a-z0-9_]"), "_")?.trim('_')
        return if (!cleanNs.isNullOrBlank()) "${cleanNs}_$cleanName" else cleanName
    }
}
