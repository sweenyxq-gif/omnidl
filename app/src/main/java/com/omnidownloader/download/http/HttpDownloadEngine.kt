package com.omnidownloader.download.http

import android.content.Context
import com.omnidownloader.data.database.DownloadDao
import com.omnidownloader.data.database.DownloadHistoryEntity
import com.omnidownloader.data.database.DownloadSegmentEntity
import com.omnidownloader.data.repository.SettingsRepository
import com.omnidownloader.data.storage.SafStorage
import com.omnidownloader.domain.engine.DownloadEngine
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class HttpDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val dao: DownloadDao,
    private val repository: DownloadRepository,
    private val storage: SafStorage,
    private val settingsRepository: SettingsRepository,
) : DownloadEngine {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val actions = ConcurrentHashMap<String, DownloadStatus>()
    private val progress = ConcurrentHashMap<String, MutableStateFlow<DownloadProgress>>()

    override fun supports(source: DownloadSource) = source is DownloadSource.Http
    override fun observe(id: String): Flow<DownloadProgress> = progress.getOrPut(id) { MutableStateFlow(DownloadProgress(id, 0, -1, status = DownloadStatus.WAITING)) }

    override suspend fun start(task: DownloadTask) {
        require(task.source is DownloadSource.Http)
        val job = currentCoroutineContext().job
        if (jobs.putIfAbsent(task.id, job) != null) return
        actions.remove(task.id)
        try {
            download(task)
        } catch (cancelled: CancellationException) {
            val status = actions.remove(task.id) ?: DownloadStatus.PAUSED
            repository.setStatus(task.id, status)
            emit(task.id, status = status)
            throw cancelled
        } catch (error: Throwable) {
            val code = classify(error)
            repository.setStatus(task.id, DownloadStatus.FAILED, code.name, error.message ?: code.name)
            emit(task.id, status = DownloadStatus.FAILED, error = error.message)
        } finally { jobs.remove(task.id, job) }
    }

    override suspend fun pause(id: String) { actions[id] = DownloadStatus.PAUSED; jobs[id]?.cancelAndJoin() ?: repository.setStatus(id, DownloadStatus.PAUSED) }
    override suspend fun resume(id: String) { repository.setStatus(id, DownloadStatus.WAITING) }
    override suspend fun cancel(id: String) {
        actions[id] = DownloadStatus.CANCELLED
        jobs[id]?.cancelAndJoin()
        repository.setStatus(id, DownloadStatus.CANCELLED)
        cleanup(id)
    }

    private suspend fun download(original: DownloadTask) = coroutineScope {
        repository.setStatus(original.id, DownloadStatus.RESOLVING)
        emit(original.id, status = DownloadStatus.RESOLVING)
        val source = original.source as DownloadSource.Http
        val probe = probe(source.value, original.headers)
        val total = if (probe.total > 0) probe.total else original.totalBytes
        val useSegments = probe.ranges && total > 0 && original.connections > 1
        val ranges = if (useSegments) RangeCalculator.calculate(total, original.connections) else listOf(ByteRange(0, 0, if (total > 0) total - 1 else Long.MAX_VALUE))
        val segmentDir = File(context.noBackupFilesDir, "segments/${original.id}").apply { mkdirs() }
        val old = dao.segments(original.id)
        val compatible = old.size == ranges.size && old.zip(ranges).all { (a, b) -> a.startByte == b.start && a.endByte == b.endInclusive }
        if (!compatible) { dao.deleteSegments(original.id); segmentDir.deleteRecursively() ; segmentDir.mkdirs() }
        val existing = if (compatible) old.associateBy { it.segmentIndex } else emptyMap()
        val segments = ranges.map { range ->
            val file = File(segmentDir, "${range.index}.part")
            val validLength = file.length().coerceAtMost(if (range.endInclusive == Long.MAX_VALUE) Long.MAX_VALUE else range.length)
            DownloadSegmentEntity(original.id, range.index, range.start, range.endInclusive, validLength, file.absolutePath,
                range.endInclusive != Long.MAX_VALUE && validLength == range.length && existing[range.index]?.complete == true)
        }
        dao.upsertSegments(segments)
        val downloaded = AtomicLong(segments.sumOf { it.downloadedBytes })
        repository.updateProgress(original.id, downloaded.get(), total)
        repository.setStatus(original.id, DownloadStatus.DOWNLOADING)
        emit(original.id, downloaded.get(), total, status = DownloadStatus.DOWNLOADING)
        val updateMutex = Mutex()
        var lastUpdateAt = System.currentTimeMillis()
        var lastUpdateBytes = downloaded.get()

        segments.map { segment -> async(Dispatchers.IO) {
            if (!segment.complete) downloadSegment(original, segment, useSegments, downloaded) { nowBytes ->
                updateMutex.withLock {
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateAt >= 500) {
                        val speed = ((nowBytes - lastUpdateBytes) * 1000 / max(1, now - lastUpdateAt)).coerceAtLeast(0)
                        repository.updateProgress(original.id, nowBytes, total)
                        emit(original.id, nowBytes, total, speed, DownloadStatus.DOWNLOADING)
                        lastUpdateAt = now; lastUpdateBytes = nowBytes
                    }
                }
            }
        } }.awaitAll()
        repository.updateProgress(original.id, downloaded.get(), total)
        mergeAndCommit(original, segments, total)
        cleanup(original.id)
    }

    private suspend fun downloadSegment(task: DownloadTask, segment: DownloadSegmentEntity, ranged: Boolean, totalDownloaded: AtomicLong, onProgress: suspend (Long) -> Unit) {
        val file = File(segment.tempPath)
        var have = file.length()
        val maxAttempts = settingsRepository.settings.first().retries.coerceIn(0, 10) + 1
        var attempt = 0
        while (true) {
            try {
                val builder = Request.Builder().url(task.source.value)
                task.headers.forEach { (name, value) -> builder.header(name, value) }
                if (ranged || have > 0) {
                    val end = if (segment.endByte == Long.MAX_VALUE) "" else segment.endByte.toString()
                    builder.header("Range", "bytes=${segment.startByte + have}-$end")
                }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    if ((ranged || have > 0) && response.code != 206) throw NoResumeException()
                    val body = response.body ?: throw IOException("Empty response body")
                    file.parentFile?.mkdirs()
                    java.io.FileOutputStream(file, have > 0).buffered().use { output ->
                        val input = body.byteStream(); val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read); have += read
                            val all = totalDownloaded.addAndGet(read.toLong())
                            onProgress(all)
                            throttle(task.speedLimitBytesPerSecond, read)
                        }
                    }
                }
                val expected = if (segment.endByte == Long.MAX_VALUE) have else segment.endByte - segment.startByte + 1
                if (segment.endByte != Long.MAX_VALUE && have != expected) throw IOException("Segment ended early ($have/$expected bytes)")
                dao.updateSegment(task.id, segment.segmentIndex, have, true)
                return
            } catch (e: Throwable) {
                if (e is NoResumeException && !ranged && have > 0) {
                    totalDownloaded.addAndGet(-have)
                    java.io.FileOutputStream(file, false).use { }
                    have = 0
                    continue
                }
                if (e is CancellationException || e is NoResumeException || e is HttpStatusException && e.code in 400..499) throw e
                attempt++
                if (attempt >= maxAttempts) throw e
                delay(com.omnidownloader.download.core.BackoffPolicy.delayMillis(attempt - 1))
                have = file.length()
            }
        }
    }

    private suspend fun mergeAndCommit(task: DownloadTask, segments: List<DownloadSegmentEntity>, total: Long) = withContext(Dispatchers.IO) {
        val pending = storage.createPending(task.destinationTreeUri, task.fileName, task.mimeType, task.id)
        var committed = false
        try {
            pending.output.buffered().use { output ->
                val actual = SegmentMerger.merge(segments.sortedBy { it.segmentIndex }.map { File(it.tempPath) }, output)
                if (!SegmentMerger.verifySha256(actual, task.sha256)) throw ChecksumException(actual)
            }
            val uri = storage.commit(pending)
            committed = true
            repository.setStatus(task.id, DownloadStatus.COMPLETED)
            dao.insertHistory(DownloadHistoryEntity(taskId = task.id, fileName = pending.finalName, destinationUri = uri.toString(), totalBytes = total, completedAt = System.currentTimeMillis()))
            emit(task.id, total.coerceAtLeast(0), total, status = DownloadStatus.COMPLETED)
        } catch (e: Throwable) { if (!committed) storage.abort(pending); throw e }
    }

    private data class Probe(val total: Long, val ranges: Boolean)
    private fun probe(url: String, headers: Map<String, String>): Probe {
        val builder = Request.Builder().url(url).header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val ranged = response.code == 206
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { !ranged }
                ?: -1
            return Probe(total, ranged)
        }
    }

    private suspend fun throttle(limit: Long, bytes: Int) { if (limit > 0) delay((bytes * 1000L / limit).coerceAtMost(2_000L)) }
    private fun cleanup(id: String) { File(context.noBackupFilesDir, "segments/$id").deleteRecursively() }
    private fun emit(id: String, downloaded: Long? = null, total: Long? = null, speed: Long = 0, status: DownloadStatus, error: String? = null) {
        val flow = progress.getOrPut(id) { MutableStateFlow(DownloadProgress(id, 0, -1, status = status)) }; val old = flow.value
        val bytes = downloaded ?: old.downloadedBytes; val size = total ?: old.totalBytes
        flow.value = DownloadProgress(id, bytes, size, speed, if (speed > 0 && size > bytes) (size - bytes) / speed else null, status, error)
    }
    private fun classify(e: Throwable) = when (e) {
        is HttpStatusException -> DownloadErrorCode.HTTP_ERROR; is NoResumeException -> DownloadErrorCode.SERVER_NO_RESUME
        is ChecksumException -> DownloadErrorCode.CHECKSUM_FAILED; is SecurityException -> DownloadErrorCode.PERMISSION_DENIED
        is java.net.SocketTimeoutException -> DownloadErrorCode.TIMEOUT; is IOException -> DownloadErrorCode.NETWORK_ERROR
        else -> DownloadErrorCode.STORAGE_ERROR
    }
}

class HttpStatusException(val code: Int) : IOException("Server returned HTTP $code")
class NoResumeException : IOException("Server did not honor the resume request")
class ChecksumException(actual: String) : IOException("SHA-256 checksum mismatch (actual $actual)")
