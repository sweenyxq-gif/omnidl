package com.omnidownloader.download.torrent

import android.content.Context
import android.net.Uri
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import com.omnidownloader.data.database.DownloadDao
import com.omnidownloader.data.database.DownloadHistoryEntity
import com.omnidownloader.data.storage.SafStorage
import com.omnidownloader.domain.engine.DownloadEngine
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val repository: DownloadRepository,
    private val dao: DownloadDao,
    private val storage: SafStorage,
) : DownloadEngine {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val managers = ConcurrentHashMap<String, SessionManager>()
    private val actions = ConcurrentHashMap<String, DownloadStatus>()
    private val progress = ConcurrentHashMap<String, MutableStateFlow<DownloadProgress>>()

    override fun supports(source: DownloadSource) = source is DownloadSource.Magnet || source is DownloadSource.TorrentFile
    override fun observe(id: String): Flow<DownloadProgress> = progress.getOrPut(id) { MutableStateFlow(DownloadProgress(id, 0, -1, status = DownloadStatus.WAITING)) }

    override suspend fun start(task: DownloadTask) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        if (jobs.putIfAbsent(task.id, job) != null) return@withContext
        val staging = File(context.noBackupFilesDir, "torrents/${task.id}").apply { mkdirs() }
        val manager = SessionManager(false)
        managers[task.id] = manager
        actions.remove(task.id)
        try {
            repository.setStatus(task.id, DownloadStatus.RESOLVING)
            manager.start()
            when (val source = task.source) {
                is DownloadSource.Magnet -> manager.download(source.value, staging, torrent_flags_t())
                is DownloadSource.TorrentFile -> manager.download(TorrentInfo(materializeTorrent(source.value, task.id)), staging)
                else -> error("Unsupported torrent source")
            }
            val handle = awaitHandle(manager)
            handles[task.id] = handle
            repository.setStatus(task.id, DownloadStatus.DOWNLOADING)
            while (currentCoroutineContext().isActive) {
                val status = handle.status()
                val total = status.totalWanted().takeIf { it > 0 } ?: -1
                val done = status.totalWantedDone()
                val speed = status.downloadRate().toLong().coerceAtLeast(0)
                repository.updateProgress(task.id, done, total)
                progress.getOrPut(task.id) { MutableStateFlow(DownloadProgress(task.id, 0, -1, status = DownloadStatus.DOWNLOADING)) }.value =
                    DownloadProgress(task.id, done, total, speed, if (speed > 0 && total > done) (total - done) / speed else null, DownloadStatus.DOWNLOADING)
                if (status.isFinished()) {
                    val payload = selectPayload(staging, status.name())
                    val outputName = if (payload.isFile) payload.name else task.fileName.ifBlank { status.name() }
                    val uri = storage.exportPayload(task.destinationTreeUri, payload, outputName)
                    repository.setStatus(task.id, DownloadStatus.COMPLETED)
                    dao.insertHistory(DownloadHistoryEntity(taskId = task.id, fileName = task.fileName, destinationUri = uri.toString(), totalBytes = total, completedAt = System.currentTimeMillis()))
                    progress[task.id]?.value = DownloadProgress(task.id, done, total, status = DownloadStatus.COMPLETED)
                    staging.deleteRecursively()
                    break
                }
                delay(750)
            }
        } catch (cancelled: CancellationException) {
            val next = actions.remove(task.id) ?: DownloadStatus.PAUSED
            repository.setStatus(task.id, next)
            throw cancelled
        } catch (error: Throwable) {
            repository.setStatus(task.id, DownloadStatus.FAILED, DownloadErrorCode.TORRENT_ERROR.name, error.message ?: "Torrent download failed")
            progress[task.id]?.value = DownloadProgress(task.id, 0, -1, status = DownloadStatus.FAILED, error = error.message)
        } finally {
            handles.remove(task.id)
            managers.remove(task.id)
            runCatching { manager.stop() }
            jobs.remove(task.id, job)
        }
    }

    override suspend fun pause(id: String) {
        actions[id] = DownloadStatus.PAUSED
        handles[id]?.pause()
        jobs[id]?.cancelAndJoin() ?: repository.setStatus(id, DownloadStatus.PAUSED)
    }

    override suspend fun resume(id: String) { repository.setStatus(id, DownloadStatus.WAITING) }

    override suspend fun cancel(id: String) {
        actions[id] = DownloadStatus.CANCELLED
        jobs[id]?.cancelAndJoin()
        repository.setStatus(id, DownloadStatus.CANCELLED)
        File(context.noBackupFilesDir, "torrents/$id").deleteRecursively()
    }

    private suspend fun awaitHandle(manager: SessionManager): TorrentHandle {
        repeat(120) {
            manager.getTorrentHandles().firstOrNull()?.let { return it }
            delay(250)
        }
        throw IOException("Timed out while adding torrent")
    }

    private fun materializeTorrent(value: String, id: String): File {
        val target = File(context.cacheDir, "$id.torrent")
        when {
            value.startsWith("content:") -> context.contentResolver.openInputStream(Uri.parse(value))?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Cannot read torrent file")
            value.startsWith("http://") || value.startsWith("https://") -> client.newCall(Request.Builder().url(value).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Torrent file request failed: HTTP ${response.code}")
                response.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: throw IOException("Empty torrent file")
            }
            else -> File(value).inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private fun selectPayload(staging: File, torrentName: String): File {
        val named = File(staging, torrentName)
        if (named.exists()) return named
        return staging.listFiles()?.singleOrNull { !it.name.endsWith(".torrent") } ?: staging
    }
}
