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
import com.omnidownloader.data.userscript.ExtensionCatalog
import com.omnidownloader.data.userscript.ExtensionUpdateManager
import com.omnidownloader.data.update.AppUpdate
import com.omnidownloader.data.update.GitHubUpdateRepository
import com.omnidownloader.data.update.UpdateCoordinator
import com.omnidownloader.data.update.UpdateProgress
import com.omnidownloader.domain.inspector.LinkInspection
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.repository.DownloadRepository
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.userscript.ExtensionUpdateInfo
import com.omnidownloader.domain.userscript.UserscriptExecutionResult
import com.omnidownloader.domain.userscript.UserscriptMetadata
import com.omnidownloader.download.core.DownloadQueueManager
import com.omnidownloader.download.core.SourceDetector
import com.omnidownloader.download.torrent.TorrentMetadataInspector
import com.omnidownloader.service.DownloadServiceController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    val isDebugging: Boolean = false,
    val torrentMetadata: TorrentMetadata? = null,
    val isTorrentInspecting: Boolean = false,
    val availableUpdate: AppUpdate? = null,
    val updateProgress: UpdateProgress? = null,
    val isCheckingForUpdates: Boolean = false,
    val isCheckingExtensionUpdates: Boolean = false,
    val availableExtensionUpdates: List<ExtensionUpdateInfo> = emptyList(),
    val pendingUrlInstall: UserscriptMetadata? = null,
    val isFetchingUrlScript: Boolean = false,
    val discoverExtensions: List<UserscriptMetadata> = emptyList(),
    val isFetchingDiscover: Boolean = false,
) {
    val totalSpeedBytesPerSecond: Long
        get() = tasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSecond }

    val activeCount: Int
        get() = tasks.count { it.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING) }

    val queuedCount: Int
        get() = tasks.count { it.status == DownloadStatus.WAITING }

    val completedCount: Int
        get() = tasks.count { it.status == DownloadStatus.COMPLETED }
}

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
    private val torrentMetadataInspector: TorrentMetadataInspector,
    private val queue: DownloadQueueManager,
    private val serviceController: DownloadServiceController,
    private val updateRepository: GitHubUpdateRepository,
    private val updateCoordinator: UpdateCoordinator,
    private val extensionUpdateManager: ExtensionUpdateManager,
) : ViewModel() {
    val extensionCatalog: List<UserscriptMetadata> = ExtensionCatalog.scripts
    private val transient = MutableStateFlow(MainUiState())
    private var torrentMetadataJob: kotlinx.coroutines.Job? = null
    val state: StateFlow<MainUiState> = combine(
        repository.observeAll(),
        settingsRepository.settings,
        userscriptResolver.installedScripts,
        updateCoordinator.progress,
        transient
    ) { tasks, settings, scripts, updateProg, local ->
        local.copy(
            tasks = tasks,
            settings = settings,
            installedScripts = scripts.values.toList(),
            updateProgress = updateProg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        val bundled = extensionUpdateManager.loadBundledCatalog()
        if (bundled.isNotEmpty()) {
            transient.update { it.copy(discoverExtensions = bundled) }
        }
        loadDiscoverCatalog()
    }

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

    fun parseScriptForReview(scriptCode: String): UserscriptMetadata? =
        UserscriptMetadataParser.parse(scriptCode)

    fun uninstallScript(id: String) {
        val removed = userscriptResolver.unregisterScript(id)
        transient.update { it.copy(message = if (removed) "Script removed" else "Built-in scripts cannot be removed") }
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        userscriptResolver.setEnabled(id, enabled)
        transient.update { it.copy(message = if (enabled) "Script enabled" else "Script disabled") }
    }

    fun clearDebugResult() {
        transient.update { it.copy(debugResult = null) }
    }

    fun checkForExtensionUpdates(repoUrl: String? = null) {
        transient.update { it.copy(isCheckingExtensionUpdates = true, message = null) }
        viewModelScope.launch {
            try {
                val updates = extensionUpdateManager.checkAllInstalledForUpdates(
                    repoUrl ?: ExtensionUpdateManager.DEFAULT_REPOSITORY_URL,
                    state.value.installedScripts
                )
                transient.update {
                    it.copy(
                        isCheckingExtensionUpdates = false,
                        availableExtensionUpdates = updates,
                        message = if (updates.isEmpty()) "All extensions are up to date" else "${updates.size} extension update(s) available"
                    )
                }
            } catch (e: Exception) {
                transient.update {
                    it.copy(isCheckingExtensionUpdates = false, message = "Failed to check extension updates: ${e.message}")
                }
            }
        }
    }

    fun applyExtensionUpdate(update: ExtensionUpdateInfo) {
        val success = extensionUpdateManager.applyUpdate(update)
        if (success) {
            transient.update {
                it.copy(
                    availableExtensionUpdates = it.availableExtensionUpdates.filterNot { u -> u.scriptId == update.scriptId },
                    message = "Updated '${update.name}' to v${update.newVersion}"
                )
            }
        } else {
            transient.update { it.copy(message = "Failed to update '${update.name}'") }
        }
    }

    fun applyAllExtensionUpdates() {
        val updates = transient.value.availableExtensionUpdates
        var count = 0
        updates.forEach { u ->
            if (extensionUpdateManager.applyUpdate(u)) count++
        }
        transient.update {
            it.copy(
                availableExtensionUpdates = emptyList(),
                message = "Updated $count extension(s)"
            )
        }
    }

    fun loadDiscoverCatalog(repoUrl: String = ExtensionUpdateManager.DEFAULT_REPOSITORY_URL) {
        transient.update { it.copy(isFetchingDiscover = true) }
        viewModelScope.launch {
            try {
                val items = extensionUpdateManager.fetchRepositoryCatalog(repoUrl)
                if (items.isNotEmpty()) {
                    transient.update { it.copy(isFetchingDiscover = false, discoverExtensions = items) }
                } else {
                    transient.update { it.copy(isFetchingDiscover = false) }
                }
            } catch (e: Exception) {
                transient.update { it.copy(isFetchingDiscover = false) }
            }
        }
    }

    fun fetchAndReviewScriptUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        transient.update { it.copy(isFetchingUrlScript = true, message = null, pendingUrlInstall = null) }
        viewModelScope.launch {
            try {
                val normalizedUrl = extensionUpdateManager.normalizeUrl(trimmed)
                val code = extensionUpdateManager.fetchScriptFromUrl(normalizedUrl)
                val cleanCode = code.removePrefix("\uFEFF").trim()

                // Check if user entered a repository manifest (extensions.json or github repo link)
                if (cleanCode.startsWith("{") || cleanCode.startsWith("[")) {
                    val catalog = extensionUpdateManager.parseCatalogManifest(cleanCode)
                    if (catalog.isNotEmpty()) {
                        transient.update {
                            it.copy(
                                isFetchingUrlScript = false,
                                discoverExtensions = catalog,
                                message = "Loaded ${catalog.size} extensions into Discover tab"
                            )
                        }
                        return@launch
                    }
                }

                val meta = UserscriptMetadataParser.parse(code)
                if (meta == null) {
                    transient.update {
                        it.copy(isFetchingUrlScript = false, message = "URL content is not a valid userscript (missing // ==UserScript== header)")
                    }
                } else {
                    transient.update {
                        it.copy(isFetchingUrlScript = false, pendingUrlInstall = meta)
                    }
                }
            } catch (e: Exception) {
                transient.update {
                    it.copy(isFetchingUrlScript = false, message = "Could not download userscript: ${e.message}")
                }
            }
        }
    }

    fun clearPendingUrlInstall() {
        transient.update { it.copy(pendingUrlInstall = null) }
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
            category = category(item.mimeType, name),
            resolvedUrl = item.url,
        )

        if (isDuplicate(task)) return

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
            category = category(preview?.mimeType, fileName.ifBlank { preview?.fileName.orEmpty() }),
            resolvedUrl = preview?.finalUrl,
            etag = preview?.etag,
            lastModified = preview?.lastModified,
        )
        if (isDuplicate(task)) return
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
    fun inspectTorrent(sourceValue: String) {
        val source = sourceValue.trim()
        if (source.isBlank()) return
        torrentMetadataJob?.cancel()
        transient.update { it.copy(isTorrentInspecting = true, torrentMetadata = null, message = null) }
        torrentMetadataJob = viewModelScope.launch {
            runCatching { torrentMetadataInspector.inspect(source) }
                .onSuccess { metadata -> transient.update { it.copy(isTorrentInspecting = false, torrentMetadata = metadata) } }
                .onFailure { error ->
                    if (error !is kotlinx.coroutines.CancellationException) transient.update {
                        it.copy(isTorrentInspecting = false, message = error.message ?: "Could not read torrent metadata")
                    }
                }
        }
    }

    fun clearTorrentMetadata() {
        torrentMetadataJob?.cancel()
        transient.update { it.copy(isTorrentInspecting = false, torrentMetadata = null) }
    }

    fun addTorrent(sourceValue: String, treeUri: String, startNow: Boolean = true) {
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
        val metadata = transient.value.torrentMetadata
        if (metadata == null || metadata.source != sourceValue.trim()) {
            transient.update { it.copy(message = "Get torrent metadata before downloading") }
            return
        }
        val task = DownloadTask(
            source = source,
            fileName = metadata.name,
            destinationTreeUri = destination,
            mimeType = "application/octet-stream",
            totalBytes = metadata.totalBytes,
            status = if (startNow) DownloadStatus.WAITING else DownloadStatus.PAUSED,
            connections = 1,
            wifiOnly = state.value.settings.wifiOnly,
            category = DownloadCategory.TORRENTS
        )
        if (isDuplicate(task)) return
        viewModelScope.launch {
            repository.upsert(task)
            transient.update { it.copy(torrentMetadata = null, message = if (startNow) "Torrent added to queue" else "Torrent added paused") }
            if (startNow) { serviceController.ensureRunning(); queue.kick() }
        }
    }
    fun clearMessage() = transient.update { it.copy(message = null) }
    fun setDefaultTree(uri: String) { viewModelScope.launch { settingsRepository.setDefaultTree(uri) } }
    fun setMax(value: Int) { viewModelScope.launch { settingsRepository.setMaxConcurrent(value) } }
    fun setConnections(value: Int) { viewModelScope.launch { settingsRepository.setConnections(value) } }
    fun setWifiOnly(value: Boolean) { viewModelScope.launch { settingsRepository.setWifiOnly(value) } }
    fun setAutoResume(value: Boolean) { viewModelScope.launch { settingsRepository.setAutoResume(value) } }
    fun setRetries(value: Int) { viewModelScope.launch { settingsRepository.setRetries(value) } }
    fun setTheme(value: String) { viewModelScope.launch { settingsRepository.setTheme(value) } }
    fun setEcoMode(value: Boolean) { viewModelScope.launch { settingsRepository.setEcoMode(value) } }
    fun setAutomaticUpdateChecks(value: Boolean) { viewModelScope.launch { settingsRepository.setAutomaticUpdateChecks(value) } }
    fun checkForUpdates() {
        transient.update { it.copy(isCheckingForUpdates = true, message = null) }
        viewModelScope.launch {
            runCatching { updateRepository.check() }
                .onSuccess { update -> transient.update { it.copy(isCheckingForUpdates = false, availableUpdate = update, message = if (update == null) "OmniDL is up to date" else null) } }
                .onFailure { error -> transient.update { it.copy(isCheckingForUpdates = false, message = error.message ?: "Update check failed") } }
        }
    }
    fun downloadUpdate() {
        val update = transient.value.availableUpdate ?: return
        updateCoordinator.download(update)
    }

    fun cancelUpdateDownload() {
        updateCoordinator.cancel()
    }

    fun installDownloadedUpdate() {
        val apkFile = state.value.updateProgress?.readyToInstallApk ?: return
        updateCoordinator.installApk(apkFile)
    }

    private fun category(mime: String?, name: String): DownloadCategory = when {
        mime?.startsWith("video/") == true -> DownloadCategory.VIDEOS
        mime?.startsWith("audio/") == true -> DownloadCategory.MUSIC
        mime?.startsWith("image/") == true -> DownloadCategory.IMAGES
        name.endsWith(".apk", true) -> DownloadCategory.APPS
        name.substringAfterLast('.', "").lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> DownloadCategory.ARCHIVES
        mime?.startsWith("text/") == true || mime == "application/pdf" -> DownloadCategory.DOCUMENTS
        else -> DownloadCategory.OTHER
    }

    private fun isDuplicate(candidate: DownloadTask): Boolean {
        val source = normalizedSource(candidate.resolvedUrl ?: candidate.source.value)
        val duplicate = state.value.tasks.firstOrNull {
            it.status != DownloadStatus.CANCELLED &&
                it.destinationTreeUri == candidate.destinationTreeUri &&
                normalizedSource(it.resolvedUrl ?: it.source.value) == source
        } ?: return false
        transient.update { it.copy(message = "Already added as '${duplicate.fileName}'") }
        return true
    }

    private fun normalizedSource(value: String): String =
        value.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()
            ?: value.substringBefore('#').trim()
}
