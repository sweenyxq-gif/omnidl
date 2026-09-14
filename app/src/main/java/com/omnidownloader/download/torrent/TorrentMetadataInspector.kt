package com.omnidownloader.download.torrent

import android.content.Context
import android.net.Uri
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentInfo
import com.omnidownloader.domain.model.TorrentFilePreview
import com.omnidownloader.domain.model.TorrentMetadata
import com.omnidownloader.download.http.FileNameParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentMetadataInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    companion object {
        private const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
        private const val MAGNET_TIMEOUT_SECONDS = 30
    }

    suspend fun inspect(source: String): TorrentMetadata = withContext(Dispatchers.IO) {
        val value = source.trim()
        require(value.startsWith("magnet:?", true) || value.startsWith("content:") || value.startsWith("http://") || value.startsWith("https://")) {
            "Enter a magnet link or choose a .torrent file"
        }
        val info = if (value.startsWith("magnet:?", true)) fetchMagnet(value) else TorrentInfo(loadTorrentBytes(value))
        require(info.isValid) { "Torrent metadata is invalid" }
        val storage = info.files()
        val files = (0 until storage.numFiles()).map { index ->
            TorrentFilePreview(index, storage.filePath(index), storage.fileSize(index))
        }
        val hash = info.infoHashV1().takeUnless { it.isAllZeros }?.toHex()
            ?: info.infoHashV2().takeUnless { it.isAllZeros }?.toHex().orEmpty()
        TorrentMetadata(
            source = value,
            name = FileNameParser.sanitize(info.name()) ?: "Torrent download",
            totalBytes = info.totalSize(),
            files = files,
            infoHash = hash,
            trackerCount = info.trackers().size,
            comment = info.comment().takeIf { it.isNotBlank() },
        )
    }

    private fun fetchMagnet(magnet: String): TorrentInfo {
        val manager = SessionManager(false)
        return try {
            manager.start()
            val bytes = manager.fetchMagnet(magnet, MAGNET_TIMEOUT_SECONDS, context.cacheDir)
                ?: throw IOException("Torrent metadata was not found. Check the magnet or try again when peers are available.")
            TorrentInfo(bytes)
        } finally {
            runCatching { manager.stop() }
        }
    }

    private fun loadTorrentBytes(source: String): ByteArray {
        val input = when {
            source.startsWith("content:") -> context.contentResolver.openInputStream(Uri.parse(source))
                ?: throw IOException("The selected torrent file cannot be read")
            else -> {
                val response = client.newCall(Request.Builder().url(source).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("Torrent metadata request failed: HTTP ${response.code}")
                }
                response.body?.byteStream() ?: run { response.close(); throw IOException("The torrent response was empty") }
            }
        }
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_TORRENT_BYTES) throw IOException("Torrent metadata exceeds the 10 MB safety limit")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
