package com.omnidownloader.data.resolver

import com.omnidownloader.domain.model.SourceType
import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.resolver.Resolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeResolver(
    private val client: OkHttpClient,
    private val endpointUrl: String
) : Resolver {
    @Inject
    constructor(client: OkHttpClient) : this(client, INNERTUBE_API_URL)

    override val id: String = "youtube_resolver"
    override val priority: Int = 95

    companion object {
        val YT_URL_REGEX = Regex(
            """(?:https?://)?(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?(?:.*&)?v=|embed/|v/|shorts/|live/)|youtu\.be/)([\w-]{11})""",
            RegexOption.IGNORE_CASE
        )
        const val INNERTUBE_API_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    }

    override fun canHandle(url: String): Boolean {
        return extractVideoId(url) != null
    }

    override suspend fun resolve(request: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(request.url)
            ?: return@withContext ResolveResult.Unsupported(request.url)

        try {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.61.48")
                        put("deviceModel", "Quest 3")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val apiRequest = Request.Builder()
                .url(endpointUrl)
                .post(reqBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.youtube.com/")
                .build()

            client.newCall(apiRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ResolveResult.Failed(
                        originalUrl = request.url,
                        reason = "YouTube service responded with HTTP ${response.code}"
                    )
                }

                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    return@withContext ResolveResult.Failed(
                        originalUrl = request.url,
                        reason = "Empty response received from YouTube"
                    )
                }

                val json = JSONObject(bodyStr)
                val playability = json.optJSONObject("playabilityStatus")
                val status = playability?.optString("status")
                if (status != null && status != "OK") {
                    val reason = playability.optString("reason", "Video is unavailable or restricted ($status)")
                    return@withContext ResolveResult.Failed(
                        originalUrl = request.url,
                        reason = reason
                    )
                }

                val videoDetails = json.optJSONObject("videoDetails")
                val title = videoDetails?.optString("title")?.ifBlank { null } ?: "YouTube Video $videoId"
                val author = videoDetails?.optString("author")?.ifBlank { null }
                val cleanTitle = sanitizeFilename(title)

                val streamingData = json.optJSONObject("streamingData")
                    ?: return@withContext ResolveResult.Failed(
                        originalUrl = request.url,
                        reason = "No stream data available for this video"
                    )

                val items = mutableListOf<ResolvedItem>()
                val streamHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://www.youtube.com/"
                )

                // 1. Muxed Formats (Video + Audio combined)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val fmt = formats.optJSONObject(i) ?: continue
                        val streamUrl = fmt.optString("url")
                        if (streamUrl.isNotBlank()) {
                            val quality = fmt.optString("qualityLabel").ifBlank { fmt.optString("quality", "Standard") }
                            val mime = fmt.optString("mimeType").substringBefore(';')
                            val ext = if (mime.contains("webm")) "webm" else "mp4"
                            val size = fmt.optLong("contentLength", -1).takeIf { it > 0 }

                            items.add(
                                ResolvedItem(
                                    label = "$title ($quality - Video & Audio)",
                                    url = streamUrl,
                                    filename = "${cleanTitle}_$quality.$ext",
                                    type = SourceType.HTTP,
                                    mimeType = mime,
                                    size = size,
                                    quality = quality,
                                    headers = streamHeaders
                                )
                            )
                        }
                    }
                }

                // 2. Adaptive Formats (HD video-only and Audio-only)
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                if (adaptive != null) {
                    val seenQualities = mutableSetOf<String>()
                    val audioItems = mutableListOf<ResolvedItem>()

                    for (i in 0 until adaptive.length()) {
                        val fmt = adaptive.optJSONObject(i) ?: continue
                        val streamUrl = fmt.optString("url")
                        if (streamUrl.isBlank()) continue

                        val mime = fmt.optString("mimeType")
                        val cleanMime = mime.substringBefore(';')
                        val size = fmt.optLong("contentLength", -1).takeIf { it > 0 }
                        val bitrate = fmt.optLong("bitrate", -1)

                        if (cleanMime.startsWith("video/")) {
                            val quality = fmt.optString("qualityLabel").ifBlank { "HD" }
                            val ext = if (cleanMime.contains("webm")) "webm" else "mp4"
                            val key = "$quality-$ext"
                            if (seenQualities.add(key) && items.size < 12) {
                                items.add(
                                    ResolvedItem(
                                        label = "$title ($quality Video Only)",
                                        url = streamUrl,
                                        filename = "${cleanTitle}_$quality.$ext",
                                        type = SourceType.HTTP,
                                        mimeType = cleanMime,
                                        size = size,
                                        quality = quality,
                                        headers = streamHeaders
                                    )
                                )
                            }
                        } else if (cleanMime.startsWith("audio/")) {
                            val audioQuality = fmt.optString("audioQuality").removePrefix("AUDIO_QUALITY_").lowercase()
                            val kbps = if (bitrate > 0) "${bitrate / 1000}k" else audioQuality
                            val ext = if (cleanMime.contains("mp4")) "m4a" else "opus"
                            val audioLabel = if (author != null) "$title - $author [Audio $kbps]" else "$title [Audio $kbps]"

                            audioItems.add(
                                ResolvedItem(
                                    label = audioLabel,
                                    url = streamUrl,
                                    filename = "${cleanTitle}_audio_$kbps.$ext",
                                    type = SourceType.HTTP,
                                    mimeType = cleanMime,
                                    size = size,
                                    quality = kbps,
                                    headers = streamHeaders
                                )
                            )
                        }
                    }

                    // Append audio streams sorted by highest bitrate
                    items.addAll(audioItems.take(4))
                }

                if (items.isNotEmpty()) {
                    ResolveResult.Success(
                        originalUrl = request.url,
                        results = items
                    )
                } else {
                    ResolveResult.Failed(
                        originalUrl = request.url,
                        reason = "No playable video or audio streams could be extracted"
                    )
                }
            }
        } catch (e: Exception) {
            ResolveResult.Failed(
                originalUrl = request.url,
                reason = "YouTube extraction error: ${e.message}"
            )
        }
    }

    private fun extractVideoId(url: String): String? {
        val match = YT_URL_REGEX.find(url.trim())
        return match?.groupValues?.get(1)
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .trim()
            .take(120)
    }
}
