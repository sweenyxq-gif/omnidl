package com.omnidownloader.download.core

import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.model.DownloadTask

object QueueScheduler {
    fun next(tasks: List<DownloadTask>, activeCount: Int, maxConcurrent: Int, wifi: Boolean, globalWifiOnly: Boolean): List<DownloadTask> =
        tasks.asSequence().filter { it.status == DownloadStatus.WAITING }
            .filter { wifi || !(globalWifiOnly || it.wifiOnly) }
            .sortedWith(compareByDescending<DownloadTask> { it.priority }.thenBy { it.createdAt })
            .take((maxConcurrent - activeCount).coerceAtLeast(0)).toList()
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
