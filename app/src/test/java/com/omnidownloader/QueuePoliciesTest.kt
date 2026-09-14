package com.omnidownloader

import com.omnidownloader.domain.model.*
import com.omnidownloader.download.core.*
import org.junit.Assert.*
import org.junit.Test

class QueuePoliciesTest {
    private fun task(id: String, priority: Int, wifi: Boolean = false) = DownloadTask(id = id, source = DownloadSource.Http("https://example.com/$id"), fileName = id, destinationTreeUri = "content://folder", priority = priority, wifiOnly = wifi)
    @Test fun schedulerHonorsCapacityPriorityAndNetwork() {
        val tasks = listOf(task("low", 1), task("wifi", 9, true), task("high", 5))
        assertEquals(listOf("high"), QueueScheduler.next(tasks, 1, 2, wifi = false, globalWifiOnly = false).map { it.id })
        assertEquals(listOf("wifi", "high"), QueueScheduler.next(tasks, 0, 2, wifi = true, globalWifiOnly = false).map { it.id })
    }
    @Test fun transitionsRejectTerminalRestarts() {
        assertTrue(DownloadStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED))
        assertFalse(DownloadStateMachine.canTransition(DownloadStatus.COMPLETED, DownloadStatus.WAITING))
    }
    @Test fun retryBackoffIsBounded() {
        assertEquals(500, BackoffPolicy.delayMillis(0)); assertEquals(4_000, BackoffPolicy.delayMillis(3)); assertEquals(15_000, BackoffPolicy.delayMillis(20))
    }
    @Test fun performancePolicyReducesWorkInEcoAndBatterySaverModes() {
        assertEquals(4, PerformancePolicy.maxConcurrent(4, ecoMode = false, powerSaveMode = false))
        assertEquals(1, PerformancePolicy.maxConcurrent(4, ecoMode = true, powerSaveMode = false))
        assertEquals(1, PerformancePolicy.maxConcurrent(4, ecoMode = false, powerSaveMode = true))
        assertEquals(2, PerformancePolicy.httpConnections(12, ecoMode = true, powerSaveMode = false))
        assertEquals(1_000L, PerformancePolicy.progressIntervalMs(ecoMode = false, powerSaveMode = false))
        assertEquals(2_000L, PerformancePolicy.progressIntervalMs(ecoMode = false, powerSaveMode = true))
    }
    @Test fun schedulerAllowsOnlyOneNativeTorrentAtATime() {
        val torrents = listOf(
            DownloadTask(source = DownloadSource.Magnet("magnet:?xt=one"), fileName = "one", destinationTreeUri = "content://folder", priority = 2),
            DownloadTask(source = DownloadSource.Magnet("magnet:?xt=two"), fileName = "two", destinationTreeUri = "content://folder", priority = 1),
        )
        assertEquals(1, QueueScheduler.next(torrents, 0, 3, wifi = true, globalWifiOnly = false).size)
    }
}
