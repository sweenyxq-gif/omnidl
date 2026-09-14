package com.omnidownloader.download.core

import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.model.DownloadTask

object QueueScheduler {
    fun next(tasks: List<DownloadTask>, activeCount: Int, maxConcurrent: Int, wifi: Boolean, globalWifiOnly: Boolean): List<DownloadTask> {
        var torrentSlots = if (tasks.any { it.status in setOf(DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING) && it.source.isTorrent() }) 0 else 1
        return tasks.asSequence().filter { it.status == DownloadStatus.WAITING }
            .filter { wifi || !(globalWifiOnly || it.wifiOnly) }
            .sortedWith(compareByDescending<DownloadTask> { it.priority }.thenBy { it.createdAt })
            .filter { task -> !task.source.isTorrent() || torrentSlots-- > 0 }
            .take((maxConcurrent - activeCount).coerceAtLeast(0)).toList()
    }

    private fun com.omnidownloader.domain.model.DownloadSource.isTorrent() =
        this is com.omnidownloader.domain.model.DownloadSource.Magnet || this is com.omnidownloader.domain.model.DownloadSource.TorrentFile
}

object PerformancePolicy {
    fun maxConcurrent(configured: Int, ecoMode: Boolean, powerSaveMode: Boolean): Int =
        if (ecoMode || powerSaveMode) 1 else configured.coerceIn(1, 8)

    fun httpConnections(configured: Int, ecoMode: Boolean, powerSaveMode: Boolean): Int =
        if (ecoMode || powerSaveMode) configured.coerceIn(1, 2) else configured.coerceIn(1, 16)

    fun progressIntervalMs(ecoMode: Boolean, powerSaveMode: Boolean): Long =
        if (ecoMode || powerSaveMode) 2_000L else 1_000L

    fun torrentPollIntervalMs(ecoMode: Boolean, powerSaveMode: Boolean): Long =
        if (ecoMode || powerSaveMode) 2_500L else 1_500L
}

object DownloadStateMachine {
    private val allowed = mapOf(
        DownloadStatus.WAITING to setOf(DownloadStatus.RESOLVING, DownloadStatus.PAUSED, DownloadStatus.CANCELLED),
        DownloadStatus.RESOLVING to setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED),
        DownloadStatus.DOWNLOADING to setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED),
        DownloadStatus.PAUSED to setOf(DownloadStatus.WAITING, DownloadStatus.CANCELLED),
        DownloadStatus.FAILED to setOf(DownloadStatus.WAITING, DownloadStatus.CANCELLED),
        DownloadStatus.COMPLETED to emptySet(), DownloadStatus.CANCELLED to emptySet())
    fun canTransition(from: DownloadStatus, to: DownloadStatus) = from == to || to in allowed.getValue(from)
}

object BackoffPolicy { fun delayMillis(attempt: Int, base: Long = 500, maximum: Long = 15_000): Long = (base * (1L shl attempt.coerceIn(0, 30))).coerceAtMost(maximum) }
