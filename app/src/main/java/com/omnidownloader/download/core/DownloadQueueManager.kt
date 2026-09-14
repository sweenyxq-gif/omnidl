package com.omnidownloader.download.core

import com.omnidownloader.data.repository.SettingsRepository
import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.repository.DownloadRepository
import com.omnidownloader.service.ConnectivityMonitor
import com.omnidownloader.service.PowerStateMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueManager @Inject constructor(
    private val repository: DownloadRepository,
    private val router: DownloadEngineRouter,
    settings: SettingsRepository,
    connectivity: ConnectivityMonitor,
    power: PowerStateMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = mutableMapOf<String, Job>()
    private val networkPaused = mutableSetOf<String>()

    init {
        scope.launch {
            combine(repository.observeAll(), settings.settings, connectivity.state, power.powerSaveMode) { tasks, prefs, network, savingPower ->
                QueueInputs(tasks, prefs, network, savingPower)
            }.distinctUntilChanged().collect { (tasks, prefs, network, savingPower) ->
                    synchronized(active) { active.entries.removeAll { it.value.isCompleted } }
                    val blocked = tasks.filter {
                        NetworkTransferPolicy.mustPause(it, network.connected, network.wifi, prefs.wifiOnly)
                    }
                    if (blocked.isNotEmpty()) {
                        blocked.forEach { task ->
                            networkPaused += task.id
                            router.engineFor(task.source)?.pause(task.id)
                        }
                        return@collect
                    }
                    if (network.connected && (network.wifi || !prefs.wifiOnly) && networkPaused.isNotEmpty()) {
                        val taskById = tasks.associateBy { it.id }
                        val resumable = networkPaused.filter { id ->
                            val task = taskById[id]
                            task != null && task.status == DownloadStatus.PAUSED &&
                                (network.wifi || !task.wifiOnly)
                        }
                        if (prefs.autoResume) resumable.forEach { repository.setStatus(it, DownloadStatus.WAITING) }
                        networkPaused.removeAll(resumable.toSet())
                        if (!prefs.autoResume) networkPaused.clear()
                    }
                    val maxConcurrent = PerformancePolicy.maxConcurrent(prefs.maxConcurrent, prefs.ecoMode, savingPower)
                    val slots = maxConcurrent - synchronized(active) { active.size }
                    if (slots <= 0) return@collect
                    QueueScheduler.next(tasks, synchronized(active) { active.size }, maxConcurrent, network.wifi, prefs.wifiOnly).forEach { task ->
                            val engine = router.engineFor(task.source)
                            if (engine == null) scope.launch { repository.setStatus(task.id, DownloadStatus.FAILED, "UNSUPPORTED_SOURCE", "This engine is planned for a later phase") }
                            else synchronized(active) {
                                if (active[task.id] == null) active[task.id] = scope.launch { engine.start(task) }
                            }
                        }
                }
        }
    }

    fun kick() = Unit
    fun pause(id: String) { scope.launch { repository.get(id)?.let { router.engineFor(it.source)?.pause(id) } } }
    fun resume(id: String) { scope.launch { repository.get(id)?.let { router.engineFor(it.source)?.resume(id) } } }
    fun cancel(id: String) { scope.launch { repository.get(id)?.let { router.engineFor(it.source)?.cancel(id) } } }
    fun delete(id: String) { scope.launch { repository.get(id)?.let { router.engineFor(it.source)?.cancel(id) }; repository.delete(id) } }
    fun pauseAll() { scope.launch { repository.observeAll().first().filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.RESOLVING }.forEach { router.engineFor(it.source)?.pause(it.id) } } }
    fun resumeAll() { scope.launch { repository.observeAll().first().filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }.forEach { repository.setStatus(it.id, DownloadStatus.WAITING) } } }

    private data class QueueInputs(
        val tasks: List<com.omnidownloader.domain.model.DownloadTask>,
        val settings: com.omnidownloader.data.repository.AppSettings,
        val network: com.omnidownloader.service.ConnectivityState,
        val powerSaveMode: Boolean,
    )
}
