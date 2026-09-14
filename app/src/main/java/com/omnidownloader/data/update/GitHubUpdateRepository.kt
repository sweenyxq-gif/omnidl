package com.omnidownloader.data.update

import android.os.Build
import com.omnidownloader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdate(
    val version: String,
    val title: String,
    val notes: String,
    val downloadUrl: String,
    val sha256: String?,
    val size: Long,
    val releaseUrl: String,
)

@Singleton
class GitHubUpdateRepository @Inject constructor(private val client: OkHttpClient) {
    companion object { const val LATEST_RELEASE = "https://api.github.com/repos/sweenyxq-gif/omnidl/releases/latest" }

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniDL/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) error("Update check failed: HTTP ${response.code}")
            val release = JSONObject(response.body?.string() ?: error("Empty update response"))
            val tag = release.optString("tag_name").removePrefix("v")
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
            val assets = release.getJSONArray("assets")
            val preferredNames = Build.SUPPORTED_ABIS.map { "app-$it-release.apk" } + "app-universal-release.apk"
            val asset = preferredNames.firstNotNullOfOrNull { preferred ->
                (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == preferred }
            } ?: (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }
                ?: error("Release $tag has no compatible APK")
            val downloadUrl = asset.getString("browser_download_url")
            val parsedDownloadUrl = downloadUrl.toHttpUrl()
            require(parsedDownloadUrl.isHttps && parsedDownloadUrl.host == "github.com") {
                "Release contains an untrusted download URL"
            }
            AppUpdate(
                version = tag,
                title = release.optString("name").ifBlank { "OmniDL $tag" },
                notes = release.optString("body").take(4_000),
                downloadUrl = downloadUrl,
                sha256 = asset.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
                size = asset.optLong("size", -1),
                releaseUrl = release.optString("html_url"),
            )
        }
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val left = parts(candidate); val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }
}
