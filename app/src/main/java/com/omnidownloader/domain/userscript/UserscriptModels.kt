package com.omnidownloader.domain.userscript

import com.omnidownloader.domain.resolver.ResolvedItem

data class UserscriptMetadata(
    val id: String,
    val name: String,
    val namespace: String? = null,
    val version: String = "1.0.0",
    val description: String? = null,
    val author: String? = null,
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val grants: Set<String> = emptySet(),
    val connects: List<String> = emptyList(),
    val isOmniResolver: Boolean = true,
    val omniApiVersion: Int = 1,
    val category: String? = null,
    val rawScript: String = ""
)

data class ScriptNetworkLog(
    val method: String,
    val url: String,
    val statusCode: Int,
    val latencyMs: Long
)

data class UserscriptExecutionResult(
    val success: Boolean,
    val resolvedItems: List<ResolvedItem> = emptyList(),
    val logs: List<String> = emptyList(),
    val networkCalls: List<ScriptNetworkLog> = emptyList(),
    val executionTimeMs: Long = 0,
    val error: String? = null
)
