package com.omnidownloader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidownloader.data.inspector.LinkInspector
import com.omnidownloader.data.network.HttpInspector
import com.omnidownloader.data.repository.AppSettings
import com.omnidownloader.data.repository.SettingsRepository
import com.omnidownloader.data.resolver.ResolverManager
import com.omnidownloader.data.userscript.UserscriptEngine
import com.omnidownloader.data.userscript.UserscriptMetadataParser
import com.omnidownloader.data.userscript.UserscriptResolver
import com.omnidownloader.domain.inspector.LinkInspection
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.repository.DownloadRepository
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.userscript.UserscriptExecutionResult
import com.omnidownloader.domain.userscript.UserscriptMetadata
import com.omnidownloader.download.core.DownloadQueueManager
import com.omnidownloader.download.core.SourceDetector
import com.omnidownloader.service.DownloadServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val preview: DownloadPreview? = null,
    val inspecting: Boolean = false,
    val message: String? = null,
    val resolveResult: ResolveResult? = null,
    val isResolving: Boolean = false,
    val linkInspection: LinkInspection? = null,
    val isDeepInspecting: Boolean = false,
    val installedScripts: List<UserscriptMetadata> = emptyList(),
    val debugResult: UserscriptExecutionResult? = null,
    val isDebugging: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val detector: SourceDetector,
    private val inspector: HttpInspector,
    private val linkInspector: LinkInspector,
    private val resolverManager: ResolverManager,
    private val userscriptResolver: UserscriptResolver,
    private val userscriptEngine: UserscriptEngine,
    private val queue: DownloadQueueManager,
    private val serviceController: DownloadServiceController,
) : ViewModel() {
    private val transient = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = combine(
        repository.observeAll(),
        settingsRepository.settings,
        userscriptResolver.installedScripts,
        transient
    ) { tasks, settings, scripts, local ->
        local.copy(tasks = tasks, settings = settings, installedScripts = scripts.values.toList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun resolve(url: String, headers: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap()) {
        if (url.isBlank()) {
            transient.update { it.copy(message = "Enter a valid URL to resolve") }
            return
        }
        transient.update { it.copy(isResolving = true, message = null, resolveResult = null) }
        viewModelScope.launch {
            try {
                val result = resolverManager.resolve(url, headers, cookies)
                transient.update { it.copy(isResolving = false, resolveResult = result) }
            } catch (e: Exception) {
                transient.update {
                    it.copy(isResolving = false, resolveResult = ResolveResult.Failed(url, e.message ?: "Resolution failed"))
                }
            }
        }
    }

    fun deepInspect(url: String, headers: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap()) {
        if (url.isBlank()) {
            transient.update { it.copy(message = "Enter a valid URL to inspect") }
            return
        }
        transient.update { it.copy(isDeepInspecting = true, message = null, linkInspection = null) }
        viewModelScope.launch {
            try {
                val inspection = linkInspector.inspect(url, headers, cookies)
                transient.update { it.copy(isDeepInspecting = false, linkInspection = inspection) }
            } catch (e: Exception) {
                transient.update { it.copy(isDeepInspecting = false, message = "Inspection error: ${e.message}") }
            }
        }
    }

    fun clearResolveResult() {
        transient.update { it.copy(resolveResult = null) }
    }

    fun clearInspection() {
        transient.update { it.copy(linkInspection = null) }
    }

    fun testScript(scriptCode: String, url: String) {
        if (scriptCode.isBlank() || url.isBlank()) {
            transient.update { it.copy(message = "Enter both a script and a target URL to test") }
            return
        }
        val meta = UserscriptMetadataParser.parse(scriptCode)
        if (meta == null) {
            transient.update { it.copy(message = "Failed to parse userscript header (missing // ==UserScript== block)") }
            return
        }
        transient.update { it.copy(isDebugging = true, debugResult = null, message = null) }
        viewModelScope.launch {
            val result = userscriptEngine.execute(meta, url)
            transient.update { it.copy(isDebugging = false, debugResult = result) }
        }
    }

    fun installScript(scriptCode: String): Boolean {
        val meta = userscriptResolver.registerScript(scriptCode)
        return if (meta != null) {
            transient.update { it.copy(message = "Installed '${meta.name}' successfully") }
            true
        } else {
            transient.update { it.copy(message = "Invalid userscript or missing @omni-resolver") }
            false
        }
    }

    fun uninstallScript(id: String) {
        userscriptResolver.unregisterScript(id)
        transient.update { it.copy(message = "Script removed") }
    }

    fun clearDebugResult() {
        transient.update { it.copy(debugResult = null) }
    }

    fun addResolvedItem(
        item: ResolvedItem,
        fileName: String? = null,
        treeUri: String? = null,
        connections: Int? = null,
        wifiOnly: Boolean? = null,
        startNow: Boolean = true
    ) {
        val destination = treeUri?.ifBlank { null } ?: state.value.settings.defaultTreeUri
        if (destination.isBlank()) {
            transient.update { it.copy(message = "Choose a destination folder") }
            return
        }
        val source = when (item.type) {
            SourceType.MAGNET -> DownloadSource.Magnet(item.url)
            SourceType.TORRENT_FILE -> DownloadSource.TorrentFile(item.url)
            SourceType.HLS -> DownloadSource.Hls(item.url)
            SourceType.DASH -> DownloadSource.Dash(item.url)
            SourceType.FTP -> DownloadSource.Ftp(item.url)
            SourceType.SFTP -> DownloadSource.Sftp(item.url)
            SourceType.HTTP -> DownloadSource.Http(item.url)
        }
        val name = fileName?.ifBlank { null } ?: item.filename ?: "download"
        val conn = connections ?: state.value.settings.connections
        val wifi = wifiOnly ?: state.value.settings.wifiOnly

        val task = DownloadTask(
            source = source,
            fileName = name,
            destinationTreeUri = destination,
            mimeType = item.mimeType ?: "application/octet-stream",
            totalBytes = item.size ?: -1,
            status = if (startNow) DownloadStatus.WAITING else DownloadStatus.PAUSED,
            connections = conn,
            headers = item.headers,
            wifiOnly = wifi,
            category = category(item.mimeType, name)
        )

        viewModelScope.launch {
            repository.upsert(task)
            transient.update { it.copy(message = "Download '$name' added to queue") }
            if (startNow) {
                serviceController.ensureRunning()
                queue.kick()
            }
        }
    }

    fun inspect(url: String, headers: Map<String, String>) {
        val source = detector.detect(url)
        if (source == null) {
            transient.update { it.copy(message = "Enter a valid HTTP or HTTPS URL") }
            return
        }
        if (source !is DownloadSource.Http) {
            transient.update { it.copy(message = "${source::class.simpleName} is planned for a later phase") }
            return
        }
        transient.update { it.copy(inspecting = true, message = null, preview = null) }
        viewModelScope.launch {
            runCatching { inspector.inspect(url, headers) }
                .onSuccess { preview ->
                    if (preview.source is DownloadSource.Http) transient.update { it.copy(inspecting = false, preview = preview) }
                    else transient.update { it.copy(inspecting = false, message = "Detected ${preview.source::class.simpleName}; this engine is not enabled in Phase 1") }
                }.onFailure { error -> transient.update { it.copy(inspecting = false, message = error.message ?: "Inspection failed") } }
        }
    }

    fun add(url: String, fileName: String, treeUri: String, connections: Int, headers: Map<String, String>, checksum: String?, wifiOnly: Boolean, startNow: Boolean) {
        val preview = transient.value.preview
        val source = detector.detect(url) as? DownloadSource.Http ?: return
        val destination = treeUri.ifBlank { state.value.settings.defaultTreeUri }
        if (destination.isBlank()) {
            transient.update { it.copy(message = "Choose a destination folder") }
            return
        }
        val task = DownloadTask(
            source = DownloadSource.Http(preview?.finalUrl ?: source.value),
            fileName = fileName.ifBlank { preview?.fileName ?: "download" },
            destinationTreeUri = destination,
            mimeType = preview?.mimeType ?: "application/octet-stream",
            totalBytes = preview?.size ?: -1,
            status = if (startNow) DownloadStatus.WAITING else DownloadStatus.PAUSED,
            connections = connections,
            headers = headers,
            sha256 = checksum?.takeIf { it.isNotBlank() },
            wifiOnly = wifiOnly,
            category = category(preview?.mimeType, fileName)
        )
        viewModelScope.launch {
            repository.upsert(task)
            transient.update { it.copy(preview = null, message = "Download added") }
            if (startNow) {
                serviceController.ensureRunning()
                queue.kick()
            }
        }
    }

    fun pause(id: String) = queue.pause(id)
    fun resume(id: String) { queue.resume(id); serviceController.ensureRunning() }
    fun cancel(id: String) = queue.cancel(id)
    fun pauseAll() = queue.pauseAll()
    fun resumeAll() { queue.resumeAll(); serviceController.ensureRunning() }
    fun retryFailed() {
        viewModelScope.launch {
            state.value.tasks.filter { it.status == DownloadStatus.FAILED }.forEach { repository.setStatus(it.id, DownloadStatus.WAITING) }
            serviceController.ensureRunning()
        }
    }
    fun clearCompleted() { viewModelScope.launch { repository.clearCompleted() } }
    fun delete(id: String) = queue.delete(id)
    fun addTorrent(sourceValue: String, fileName: String, treeUri: String, startNow: Boolean = true) {
        val source = detector.detect(sourceValue)
        if (source !is DownloadSource.Magnet && source !is DownloadSource.TorrentFile) {
            transient.update { it.copy(message = "Paste a magnet link or choose a .torrent file") }
            return
        }
        val destination = treeUri.ifBlank { state.value.settings.defaultTreeUri }
        if (destination.isBlank()) {
            transient.update { it.copy(message = "Choose a destination folder") }
            return
        }
        val fallbackName = if (source is DownloadSource.Magnet) magnetDisplayName(source.value) else "Torrent download"
        val task = DownloadTask(
            source = source,
            fileName = fileName.ifBlank { fallbackName },
            destinationTreeUri = destination,
            mimeType = "application/octet-stream",
            status = if (startNow) DownloadStatus.WAITING else DownloadStatus.PAUSED,
            connections = 1,
            wifiOnly = state.value.settings.wifiOnly,
            category = DownloadCategory.TORRENTS
        )
        viewModelScope.launch {
            repository.upsert(task)
            transient.update { it.copy(message = if (startNow) "Torrent added to queue" else "Torrent added paused") }
            if (startNow) { serviceController.ensureRunning(); queue.kick() }
        }
    }
    fun clearMessage() = transient.update { it.copy(message = null) }
    fun setDefaultTree(uri: String) { viewModelScope.launch { settingsRepository.setDefaultTree(uri) } }
    fun setMax(value: Int) { viewModelScope.launch { settingsRepository.setMaxConcurrent(value) } }
    fun setConnections(value: Int) { viewModelScope.launch { settingsRepository.setConnections(value) } }
    fun setWifiOnly(value: Boolean) { viewModelScope.launch { settingsRepository.setWifiOnly(value) } }
    fun setAutoResume(value: Boolean) { viewModelScope.launch { settingsRepository.setAutoResume(value) } }
    fun setTheme(value: String) { viewModelScope.launch { settingsRepository.setTheme(value) } }

    private fun category(mime: String?, name: String): DownloadCategory = when {
        mime?.startsWith("video/") == true -> DownloadCategory.VIDEOS
        mime?.startsWith("audio/") == true -> DownloadCategory.MUSIC
        mime?.startsWith("image/") == true -> DownloadCategory.IMAGES
        name.endsWith(".apk", true) -> DownloadCategory.APPS
        name.substringAfterLast('.', "").lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> DownloadCategory.ARCHIVES
        mime?.startsWith("text/") == true || mime == "application/pdf" -> DownloadCategory.DOCUMENTS
        else -> DownloadCategory.OTHER
    }

    private fun magnetDisplayName(value: String): String = runCatching {
        java.net.URI(value).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("dn=") }
            ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }.getOrNull().orEmpty().ifBlank { "Magnet download" }
}
