@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.omnidownloader.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidownloader.domain.inspector.LinkInspection
import com.omnidownloader.BuildConfig
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.userscript.ExtensionUpdateInfo
import com.omnidownloader.domain.userscript.UserscriptMetadata
import com.omnidownloader.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Destination(val label: String) {
    DOWNLOADS("Downloads"),
    RESOLVE("Resolve"),
    TORRENTS("Torrents"),
    EXTENSIONS("Extensions"),
    SETTINGS("Settings")
}

private enum class DownloadTab(val label: String) {
    ACTIVE("Active"),
    QUEUED("Queued"),
    COMPLETED("Done"),
    FAILED("Failed")
}

@Composable
fun OmniApp(initialUrl: String, viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dark = when (state.settings.theme) {
        "DARK" -> true
        "LIGHT" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    OmniTheme(darkTheme = dark, dynamicColor = false) {
        val torrentInput = initialUrl.startsWith("magnet:", true) ||
                initialUrl.startsWith("content:", true) ||
                initialUrl.endsWith(".torrent", true)

        var destination by rememberSaveable {
            mutableStateOf(
                if (torrentInput) Destination.TORRENTS
                else if (initialUrl.isNotBlank()) Destination.RESOLVE
                else Destination.DOWNLOADS
            )
        }

        var showAddMenu by rememberSaveable { mutableStateOf(false) }
        var showDirectDownloadDialog by rememberSaveable { mutableStateOf(false) }
        var directDownloadInitialUrl by rememberSaveable { mutableStateOf("") }
        var downloadTargetItem by remember { mutableStateOf<ResolvedItem?>(null) }

        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                viewModel.clearMessage()
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Download,
                                        null,
                                        Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("OmniDL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            "PRO",
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.totalSpeedBytesPerSecond > 0) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.ArrowDownward,
                                        null,
                                        Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        "${formatBytes(state.totalSpeedBytesPerSecond)}/s",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        if (destination == Destination.DOWNLOADS) {
                            FilledTonalIconButton(
                                onClick = viewModel::pauseAll,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Pause, "Pause all", Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            FilledTonalIconButton(
                                onClick = viewModel::resumeAll,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, "Resume all", Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 8.dp
                ) {
                    Destination.entries.forEach { item ->
                        val selected = destination == item
                        NavigationBarItem(
                            selected = selected,
                            onClick = { destination = item },
                            icon = {
                                BadgedBox(badge = {
                                    if (item == Destination.DOWNLOADS && state.activeCount > 0) {
                                        Badge { Text(state.activeCount.toString()) }
                                    } else if (item == Destination.EXTENSIONS && state.availableExtensionUpdates.isNotEmpty()) {
                                        Badge { Text(state.availableExtensionUpdates.size.toString()) }
                                    }
                                }) {
                                    Icon(
                                        when (item) {
                                            Destination.DOWNLOADS -> Icons.Default.Download
                                            Destination.RESOLVE -> Icons.Default.Link
                                            Destination.TORRENTS -> Icons.Default.CloudDownload
                                            Destination.EXTENSIONS -> Icons.Default.Extension
                                            Destination.SETTINGS -> Icons.Default.Settings
                                        },
                                        item.label
                                    )
                                }
                            },
                            label = { Text(item.label, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            },
            floatingActionButton = {
                if (destination == Destination.DOWNLOADS) {
                    FloatingActionButton(onClick = { showAddMenu = true }) {
                        Icon(Icons.Default.Add, "New download")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (destination) {
                    Destination.DOWNLOADS -> DownloadsScreen(state, viewModel, onNewDownload = { showAddMenu = true })
                    Destination.RESOLVE -> ResolveScreen(
                        if (torrentInput) "" else initialUrl,
                        state,
                        viewModel,
                        onOpenDirectDownload = { targetUrl ->
                            directDownloadInitialUrl = targetUrl
                            showDirectDownloadDialog = true
                        },
                        onOpenDownloadItem = { item ->
                            downloadTargetItem = item
                        }
                    )
                    Destination.TORRENTS -> TorrentScreen(if (torrentInput) initialUrl else "", state, viewModel)
                    Destination.EXTENSIONS -> ExtensionsScreen(state, viewModel)
                    Destination.SETTINGS -> SettingsScreen(state, viewModel)
                }

                // Link Inspector Dialog
                state.linkInspection?.let { inspection ->
                    LinkInspectionDialog(inspection, onDismiss = { viewModel.clearInspection() }) { url, filename ->
                        downloadTargetItem = ResolvedItem(
                            label = "Inspected file",
                            url = url,
                            filename = filename,
                            type = SourceType.HTTP
                        )
                        viewModel.clearInspection()
                    }
                }

                // New Transfer Selection Dialog
                if (showAddMenu) {
                    AlertDialog(
                        onDismissRequest = { showAddMenu = false },
                        title = { Text("New Transfer") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        showAddMenu = false
                                        directDownloadInitialUrl = ""
                                        showDirectDownloadDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(14.dp))
                                        Column {
                                            Text("Direct HTTP / HTTPS Download", fontWeight = FontWeight.SemiBold)
                                            Text("Files, APKs, videos, archives from a direct link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        showAddMenu = false
                                        destination = Destination.RESOLVE
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(14.dp))
                                        Column {
                                            Text("Resolve Webpage or Stream", fontWeight = FontWeight.SemiBold)
                                            Text("Extract media streams or run userscript resolvers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        showAddMenu = false
                                        destination = Destination.TORRENTS
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(14.dp))
                                        Column {
                                            Text("BitTorrent Transfer", fontWeight = FontWeight.SemiBold)
                                            Text("Download from a magnet link or .torrent file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showAddMenu = false }) { Text("Cancel") }
                        }
                    )
                }

                // Add Direct Download Dialog
                if (showDirectDownloadDialog) {
                    AddDirectDownloadDialog(
                        initialUrl = directDownloadInitialUrl,
                        state = state,
                        vm = viewModel,
                        onDismiss = { showDirectDownloadDialog = false }
                    )
                }

                // Download Item Dialog
                downloadTargetItem?.let { item ->
                    DownloadItemDialog(
                        item = item,
                        state = state,
                        vm = viewModel,
                        onDismiss = { downloadTargetItem = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(state: MainUiState, vm: MainViewModel, onNewDownload: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { DownloadTab.entries.size }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("ALL") }

    Column {
        DownloadOverview(state.tasks)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear search") }
            },
            placeholder = { Text("Search downloads…") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp)
        )

        // Category Filter Chips
        val categories = listOf(
            "ALL" to "All",
            "VIDEOS" to "Videos",
            "AUDIO" to "Audio",
            "ARCHIVES" to "Archives",
            "APPS" to "Apps",
            "DOCUMENTS" to "Docs",
            "TORRENTS" to "Torrents"
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { (key, label) ->
                FilterChip(
                    selected = selectedCategory == key,
                    onClick = { selectedCategory = key },
                    label = { Text(label) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp, divider = {}) {
            DownloadTab.entries.forEachIndexed { index, tab ->
                val count = state.tasks.count { matchesTab(it, tab) }
                Tab(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.label, fontWeight = if (pager.currentPage == index) FontWeight.Bold else FontWeight.Normal)
                            if (count > 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge(
                                    containerColor = if (pager.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (pager.currentPage == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                ) { Text(count.toString()) }
                            }
                        }
                    }
                )
            }
        }

        if (pager.currentPage == DownloadTab.COMPLETED.ordinal && state.tasks.any { it.status == DownloadStatus.COMPLETED }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                TextButton(vm::clearCompleted) {
                    Icon(Icons.Default.CleaningServices, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear completed history")
                }
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
            val tab = DownloadTab.entries[page]
            val tasks = state.tasks.filter {
                matchesTab(it, tab) &&
                matchesCategory(it, selectedCategory) &&
                (query.isBlank() || it.fileName.contains(query, true))
            }
            if (tasks.isEmpty()) {
                val (emptyTitle, emptySubtitle) = when {
                    query.isNotBlank() -> "No matching downloads" to "Try adjusting your search query"
                    selectedCategory != "ALL" -> "No $selectedCategory downloads" to "Filter returned zero results"
                    tab == DownloadTab.ACTIVE -> "No active transfers" to "Active downloads and transfers will appear here"
                    tab == DownloadTab.QUEUED -> "Queue is empty" to "Waiting downloads will appear here"
                    tab == DownloadTab.COMPLETED -> "No finished downloads" to "Finished transfers will be organized here"
                    else -> "No failed downloads" to "All downloads completed without errors"
                }
                EmptyState(
                    title = emptyTitle,
                    subtitle = emptySubtitle,
                    icon = when (tab) {
                        DownloadTab.ACTIVE -> Icons.Default.Downloading
                        DownloadTab.QUEUED -> Icons.Default.Schedule
                        DownloadTab.COMPLETED -> Icons.Default.CheckCircle
                        DownloadTab.FAILED -> Icons.Default.ErrorOutline
                    },
                    actionLabel = if (tab == DownloadTab.ACTIVE || tab == DownloadTab.QUEUED) "New Transfer" else null,
                    onAction = if (tab == DownloadTab.ACTIVE || tab == DownloadTab.QUEUED) onNewDownload else null
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(tasks, key = { it.id }) { DownloadCard(it, vm) }
                }
            }
        }
    }
}

@Composable
private fun DownloadOverview(tasks: List<DownloadTask>) {
    val totalSpeed = tasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSecond }
    val active = tasks.count { it.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING) }
    val queued = tasks.count { it.status == DownloadStatus.WAITING }
    val completed = tasks.count { it.status == DownloadStatus.COMPLETED }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Transfer Hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (totalSpeed > 0) "Engine downloading at high speed" else "Idle • Queue ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (totalSpeed > 0) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Speed, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${formatBytes(totalSpeed)}/s",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OverviewMetric("Active", active, Icons.Default.Downloading, MaterialTheme.colorScheme.primary)
                OverviewMetric("Queued", queued, Icons.Default.Schedule, MaterialTheme.colorScheme.tertiary)
                OverviewMetric("Done", completed, Icons.Default.CheckCircle, OmniMint)
            }
        }
    }
}

@Composable
private fun RowScope.OverviewMetric(label: String, value: Int, icon: ImageVector, color: Color) {
    Surface(
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun matchesTab(task: DownloadTask, tab: DownloadTab) = when (tab) {
    DownloadTab.ACTIVE -> task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING, DownloadStatus.PAUSED)
    DownloadTab.QUEUED -> task.status == DownloadStatus.WAITING
    DownloadTab.COMPLETED -> task.status == DownloadStatus.COMPLETED
    DownloadTab.FAILED -> task.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)
}

private fun matchesCategory(task: DownloadTask, filter: String): Boolean {
    if (filter == "ALL") return true
    val ext = task.fileName.substringAfterLast('.', "").lowercase()
    val mime = task.mimeType.lowercase()
    return when (filter) {
        "VIDEOS" -> mime.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "webm", "mov", "flv", "m3u8", "ts")
        "AUDIO" -> mime.startsWith("audio/") || ext in setOf("mp3", "flac", "aac", "wav", "ogg", "m4a", "opus")
        "ARCHIVES" -> ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        "APPS" -> mime.contains("android.package-archive") || ext == "apk"
        "TORRENTS" -> task.source is DownloadSource.Magnet || task.source is DownloadSource.TorrentFile
        "DOCUMENTS" -> ext in setOf("pdf", "doc", "docx", "txt", "epub", "csv", "xlsx")
        else -> true
    }
}

private fun categoryIconAndColor(task: DownloadTask): Pair<ImageVector, Color> {
    val ext = task.fileName.substringAfterLast('.', "").lowercase()
    val mime = task.mimeType.lowercase()
    return when {
        task.source is DownloadSource.Magnet || task.source is DownloadSource.TorrentFile ->
            Icons.Default.CloudDownload to OmniTorrent
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "webm", "mov", "flv", "m3u8", "ts") ->
            Icons.Default.Movie to OmniVideo
        mime.startsWith("audio/") || ext in setOf("mp3", "flac", "aac", "wav", "ogg", "m4a", "opus") ->
            Icons.Default.MusicNote to OmniAudio
        mime.contains("android.package-archive") || ext == "apk" ->
            Icons.Default.Android to OmniAppGreen
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ->
            Icons.Default.Archive to OmniArchive
        ext in setOf("pdf", "doc", "docx", "txt", "epub") ->
            Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFFE11D48)
        else ->
            Icons.AutoMirrored.Filled.InsertDriveFile to DarkPrimary
    }
}

private fun openDownloadedFile(context: Context, task: DownloadTask) {
    runCatching {
        val treeUri = Uri.parse(task.destinationTreeUri)
        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        val file = dir?.findFile(task.fileName)
        if (file != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, task.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } else {
            val folderIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, "vnd.android.document/root")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(folderIntent, "Open download folder"))
        }
    }.onFailure {
        Toast.makeText(context, "Could not open file: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareDownloadedFile(context: Context, task: DownloadTask) {
    runCatching {
        val treeUri = Uri.parse(task.destinationTreeUri)
        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        val file = dir?.findFile(task.fileName)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            if (file != null) {
                type = task.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, task.source.value)
            }
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Download"))
    }.onFailure {
        Toast.makeText(context, "Could not share file: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DownloadCard(task: DownloadTask, vm: MainViewModel) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (catIcon, catColor) = remember(task) { categoryIconAndColor(task) }
    val ext = remember(task.fileName) { task.fileName.substringAfterLast('.', "").uppercase().take(5) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = catColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(catIcon, null, tint = catColor, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ext.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(
                                    ext,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            task.fileName,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor(task.status).copy(alpha = 0.12f)
                        ) {
                            Text(
                                statusLabel(task.status),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor(task.status)
                            )
                        }
                        if (task.status == DownloadStatus.DOWNLOADING && task.speedBytesPerSecond > 0) {
                            Text(
                                "↓ ${formatBytes(task.speedBytesPerSecond)}/s",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy source URL") },
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("URL", task.source.value))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                menu = false
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (task.status == DownloadStatus.COMPLETED) "Remove from history" else "Delete download") },
                            onClick = { menu = false; confirmDelete = true },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                        )
                    }
                }
            }

            if (task.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatBytes(task.downloadedBytes)} of ${formatBytes(task.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "${(task.downloadedBytes * 100 / task.totalBytes).coerceIn(0, 100)}%",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                if (task.status == DownloadStatus.DOWNLOADING) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        task.etaSeconds?.let {
                            Text("ETA ~ ${formatDuration(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${task.connections} streams", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Text("Connecting and preparing streams…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            task.errorMessage?.let {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Expandable details drawer
            if (expanded) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Destination: ${task.destinationTreeUri.takeLast(60)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Source: ${task.source.value.take(70)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        task.sha256?.let { Text("SHA-256: ${it.take(24)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            // Primary Action Buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (expanded) "Less" else "Details", style = MaterialTheme.typography.labelMedium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (task.status) {
                        DownloadStatus.COMPLETED -> {
                            OutlinedButton(
                                onClick = { shareDownloadedFile(context, task) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                            Button(
                                onClick = { openDownloadedFile(context, task) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open")
                            }
                        }
                        DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING -> {
                            OutlinedButton(
                                onClick = { vm.cancel(task.id) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel")
                            }
                            FilledTonalButton(
                                onClick = { vm.pause(task.id) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pause")
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                            OutlinedButton(
                                onClick = { vm.cancel(task.id) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel")
                            }
                            Button(
                                onClick = { vm.resume(task.id) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (task.status == DownloadStatus.FAILED) "Retry" else "Resume")
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text("Delete this task?") },
            text = { Text(if (task.status == DownloadStatus.COMPLETED) "The downloaded file will stay on your device." else "The task and any temporary download data will be removed.") },
            confirmButton = { TextButton({ confirmDelete = false; vm.delete(task.id) }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun ResolveScreen(
    initialUrl: String,
    state: MainUiState,
    vm: MainViewModel,
    onOpenDirectDownload: (String) -> Unit,
    onOpenDownloadItem: (ResolvedItem) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var headersText by rememberSaveable { mutableStateOf("") }
    var cookiesText by rememberSaveable { mutableStateOf("") }

    val headers = remember(headersText) {
        headersText.lineSequence()
            .mapNotNull { line ->
                val k = line.substringBefore(':', "").trim()
                val v = line.substringAfter(':', "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) k to v else null
            }.toMap()
    }
    val cookies = remember(cookiesText) {
        cookiesText.lineSequence()
            .mapNotNull { line ->
                val k = line.substringBefore('=', "").trim()
                val v = line.substringAfter('=', "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) k to v else null
            }.toMap()
    }

    val clipboardManager = LocalClipboardManager.current
    val clipboardText = remember { clipboardManager.getText()?.text?.trim().orEmpty() }
    val showClipboardPaste = remember(clipboardText, url) {
        clipboardText.isNotBlank() &&
            (clipboardText.startsWith("http://", ignoreCase = true) || clipboardText.startsWith("https://", ignoreCase = true)) &&
            clipboardText != url
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.TravelExplore, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Resolve & Inspect", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Stream Extractor • Userscript Resolvers", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            "Resolving never starts a download automatically. Inspect link metadata or choose streams to download.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        if (showClipboardPaste) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().clickable { url = clipboardText }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Paste from clipboard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(clipboardText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enter Webpage or File URL") },
            leadingIcon = { Icon(Icons.Default.Link, null) },
            trailingIcon = {
                if (url.isNotBlank()) IconButton({ url = "" }) { Icon(Icons.Default.Close, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { vm.resolve(url, headers, cookies) },
                enabled = !state.isResolving && url.isNotBlank(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.isResolving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.TravelExplore, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (state.isResolving) "Resolving…" else "Resolve")
            }

            OutlinedButton(
                onClick = { vm.deepInspect(url, headers, cookies) },
                enabled = !state.isDeepInspecting && url.isNotBlank(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.isDeepInspecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.FindInPage, null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (state.isDeepInspecting) "Probing…" else "Inspect")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { vm.inspect(url, headers) },
                enabled = !state.inspecting && url.isNotBlank(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.inspecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.FindInPage, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.inspecting) "Reading…" else "Quick probe")
            }

            Button(
                onClick = { onOpenDirectDownload(url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Direct Download")
            }
        }

        if (url.startsWith("http://", true)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp))
                    Text("HTTP is supported, but it is not encrypted. Avoid sending passwords, cookies or authorization headers.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        TextButton({ showAdvanced = !showAdvanced }) {
            Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.Tune, null)
            Spacer(Modifier.width(6.dp))
            Text(if (showAdvanced) "Hide request headers & cookies" else "Custom request headers & cookies")
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = headersText,
                onValueChange = { headersText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Headers (Key: Value per line)") },
                placeholder = { Text("User-Agent: Mozilla/5.0...\nReferer: https://example.com") },
                minLines = 2,
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = cookiesText,
                onValueChange = { cookiesText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cookies (name=value per line)") },
                placeholder = { Text("session_id=xyz123\nauth_token=abc") },
                minLines = 2,
                shape = RoundedCornerShape(12.dp)
            )
        }

        state.preview?.let { preview ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preview.fileName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${formatBytes(preview.size)} • ${preview.mimeType}", style = MaterialTheme.typography.bodySmall)
                        }
                        AssistChip(onClick = {}, label = { Text(if (preview.supportsRanges) "Resume" else "Single stream") })
                    }
                    Text(preview.finalUrl, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            onOpenDownloadItem(
                                ResolvedItem(
                                    label = preview.fileName,
                                    url = preview.finalUrl,
                                    filename = preview.fileName,
                                    size = preview.size,
                                    mimeType = preview.mimeType,
                                    type = SourceType.HTTP,
                                    headers = headers
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download this file")
                    }
                }
            }
        }

        // Display Resolve Result
        when (val res = state.resolveResult) {
            is ResolveResult.Success -> {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Resolved Items (${res.results.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton({ vm.clearResolveResult() }) { Text("Clear") }
                }

                res.results.forEach { item ->
                    ResolvedItemCard(item, onDownload = {
                        onOpenDownloadItem(item)
                    }, onInspect = {
                        vm.deepInspect(item.url, headers, cookies)
                    })
                }
            }
            is ResolveResult.Failed -> {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Resolution Failed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Text(res.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            is ResolveResult.Unsupported -> {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No specific resolver matched this link.", fontWeight = FontWeight.SemiBold)
                        Text("You can still use Link Inspector to inspect headers or probe download feasibility.")
                    }
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun ResolvedItemCard(item: ResolvedItem, onDownload: () -> Unit, onInspect: () -> Unit) {
    val context = LocalContext.current
    val (typeColor, typeIcon) = when (item.type) {
        SourceType.MAGNET -> OmniTorrent to Icons.Default.CloudDownload
        SourceType.HLS, SourceType.DASH -> OmniVideo to Icons.Default.Movie
        else -> MaterialTheme.colorScheme.primary to Icons.AutoMirrored.Filled.InsertDriveFile
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = typeColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = typeColor.copy(alpha = 0.12f),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                item.type.name,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = typeColor
                            )
                        }
                        Text(
                            item.label ?: item.filename ?: "Resolved Item",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${item.size?.let { formatBytes(it) } ?: "Size unknown"} • ${item.mimeType ?: "stream"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    item.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Action Row: [Copy Link], [Share], [Open Browser], [Inspect] ... [Download]
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("URL", item.url))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy link", Modifier.size(20.dp))
                    }

                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, item.url)
                        }
                        context.startActivity(Intent.createChooser(share, "Share Link"))
                    }) {
                        Icon(Icons.Default.Share, "Share link", Modifier.size(20.dp))
                    }

                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser", Modifier.size(20.dp))
                    }

                    IconButton(onClick = onInspect) {
                        Icon(Icons.Default.Analytics, "Inspect Link", Modifier.size(20.dp))
                    }
                }

                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download")
                }
            }
        }
    }
}

@Composable
private fun LinkInspectionDialog(
    inspection: LinkInspection,
    onDismiss: () -> Unit,
    onDownload: (url: String, filename: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Analytics, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Link Inspector")
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (inspection.httpStatus in 200..299) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "HTTP ${inspection.httpStatus}",
                            fontWeight = FontWeight.Bold,
                            color = if (inspection.httpStatus in 200..299) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                        Text("${inspection.latencyMs} ms", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Text("Final URL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(inspection.finalUrl, style = MaterialTheme.typography.bodySmall)

                if (inspection.redirectCount > 0) {
                    Text("Redirect Chain (${inspection.redirectCount} hops)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    inspection.redirectChain.forEach { hop ->
                        Text("• HTTP ${hop.statusCode} → ${hop.url.take(60)}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider()

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Filename", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(inspection.filename ?: "unknown", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Content-Length", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(inspection.contentLength?.let { formatBytes(it) } ?: "unknown", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Content-Type", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(inspection.contentType ?: "unknown", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Resume (Ranges)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(if (inspection.acceptRanges) "Yes (Accept-Ranges)" else "No / Single stream", style = MaterialTheme.typography.bodySmall)
                }
                inspection.server?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Server", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                inspection.tlsVersion?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TLS Version", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDownload(inspection.finalUrl, inspection.filename ?: "download")
            }) {
                Text("Download File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AddDirectDownloadDialog(
    initialUrl: String,
    state: MainUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var connections by rememberSaveable { mutableIntStateOf(state.settings.connections) }
    var wifiOnly by rememberSaveable { mutableStateOf(state.settings.wifiOnly) }
    var startNow by rememberSaveable { mutableStateOf(true) }
    var folder by rememberSaveable { mutableStateOf(state.settings.defaultTreeUri) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var headersText by rememberSaveable { mutableStateOf("") }
    var checksum by rememberSaveable { mutableStateOf("") }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeTreePermission(context, it)
            folder = it.toString()
            vm.setDefaultTree(it.toString())
        }
    }

    LaunchedEffect(state.preview) {
        state.preview?.let { preview ->
            if (fileName.isBlank()) {
                fileName = preview.fileName
            }
        }
    }

    val headers = remember(headersText) {
        headersText.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                else null
            }.toMap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Direct Download")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Download URL (HTTP / HTTPS)") },
                    placeholder = { Text("https://example.com/file.zip") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                                if (clip.isNotBlank()) url = clip
                            }) {
                                Icon(Icons.Default.ContentPaste, "Paste")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { vm.inspect(url.trim(), headers) },
                        enabled = !state.inspecting && url.trim().isNotBlank()
                    ) {
                        if (state.inspecting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Inspecting…")
                        } else {
                            Icon(Icons.Default.FindInPage, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Probe file metadata")
                        }
                    }
                }

                state.preview?.let { preview ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(preview.fileName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(formatBytes(preview.size), style = MaterialTheme.typography.bodySmall)
                                Text("•", style = MaterialTheme.typography.bodySmall)
                                Text(preview.mimeType, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(if (preview.supportsRanges) "Multi-threaded / Resume" else "Single stream") }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name (optional)") },
                    placeholder = { Text(state.preview?.fileName ?: "auto-detect from URL") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Text("Save Destination", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                FolderButton(folder, { treePicker.launch(null) })
                if (folder.isBlank() && state.settings.defaultTreeUri.isBlank()) {
                    Text("Select a folder to save this download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                SettingSlider("Connections", connections, 1..16) { connections = it }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi only")
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Start immediately")
                    Switch(checked = startNow, onCheckedChange = { startNow = it })
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.Tune, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (showAdvanced) "Hide advanced options" else "Headers & Checksum")
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("Headers (Key: Value per line)") },
                        placeholder = { Text("User-Agent: ...\nReferer: ...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = checksum,
                        onValueChange = { checksum = it },
                        label = { Text("Expected SHA-256 (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalFolder = folder.ifBlank { state.settings.defaultTreeUri }
                    val finalName = fileName.ifBlank { state.preview?.fileName.orEmpty() }
                    vm.add(
                        url = url.trim(),
                        fileName = finalName,
                        treeUri = finalFolder,
                        connections = connections,
                        headers = headers,
                        checksum = checksum.takeIf { it.isNotBlank() },
                        wifiOnly = wifiOnly,
                        startNow = startNow
                    )
                    onDismiss()
                },
                enabled = url.trim().isNotBlank() && (folder.isNotBlank() || state.settings.defaultTreeUri.isNotBlank())
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Start Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DownloadItemDialog(
    item: ResolvedItem,
    state: MainUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var fileName by rememberSaveable(item) { mutableStateOf(item.filename.orEmpty()) }
    var folder by rememberSaveable { mutableStateOf(state.settings.defaultTreeUri) }
    var connections by rememberSaveable { mutableIntStateOf(state.settings.connections) }
    var wifiOnly by rememberSaveable { mutableStateOf(state.settings.wifiOnly) }
    var startNow by rememberSaveable { mutableStateOf(true) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeTreePermission(context, it)
            folder = it.toString()
            vm.setDefaultTree(it.toString())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (item.type) {
                        SourceType.MAGNET -> Icons.Default.CloudDownload
                        SourceType.HLS, SourceType.DASH -> Icons.Default.Movie
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text("Download File")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(item.label.orEmpty().ifBlank { item.filename ?: "Download File" }, fontWeight = FontWeight.Bold)
                Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(item.type.name) })
                    item.size?.let {
                        Text(formatBytes(it), style = MaterialTheme.typography.bodySmall)
                    }
                    item.mimeType?.let {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name") },
                    placeholder = { Text(item.filename ?: "download") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Text("Save Destination", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                FolderButton(folder, { treePicker.launch(null) })
                if (folder.isBlank() && state.settings.defaultTreeUri.isBlank()) {
                    Text("Select a folder to save this download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (item.type == SourceType.HTTP) {
                    SettingSlider("Connections", connections, 1..16) { connections = it }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi only")
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Start immediately")
                    Switch(checked = startNow, onCheckedChange = { startNow = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalFolder = folder.ifBlank { state.settings.defaultTreeUri }
                    vm.addResolvedItem(
                        item = item,
                        fileName = fileName.ifBlank { item.filename },
                        treeUri = finalFolder,
                        connections = connections,
                        wifiOnly = wifiOnly,
                        startNow = startNow
                    )
                    onDismiss()
                },
                enabled = folder.isNotBlank() || state.settings.defaultTreeUri.isNotBlank()
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Start Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ExtensionsScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var urlInstallOpen by rememberSaveable { mutableStateOf(false) }
    var remoteScriptUrl by rememberSaveable { mutableStateOf("") }
    var scriptCode by rememberSaveable { mutableStateOf("") }
    var extensionTab by rememberSaveable { mutableIntStateOf(0) }
    var debugUrl by rememberSaveable { mutableStateOf("") }
    var pendingInstall by remember { mutableStateOf<UserscriptMetadata?>(null) }

    LaunchedEffect(state.pendingUrlInstall) {
        state.pendingUrlInstall?.let {
            pendingInstall = it
            vm.clearPendingUrlInstall()
        }
    }

    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        result.append(buffer, 0, count)
                        require(result.length <= 1_000_000) { "Userscript is larger than 1 MB" }
                    }
                    result.toString()
                } ?: error("Could not read the selected file")
            }.onSuccess { loaded ->
                scriptCode = loaded
                editorOpen = true
            }.onFailure { Toast.makeText(context, it.message ?: "Could not read userscript", Toast.LENGTH_LONG).show() }
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Extension, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Extensions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Userscripts & Custom Resolvers", color = MaterialTheme.colorScheme.primary)
            }
        }

        Text(
            "Install resolver-focused .user.js scripts. Every script runs in an isolated WebView, can contact only reviewed @connect domains, and may return links but cannot start downloads.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PrimaryTabRow(selectedTabIndex = extensionTab) {
            listOf("Installed", "Discover", "Developer").forEachIndexed { index, label ->
                Tab(selected = extensionTab == index, onClick = { extensionTab = index }, text = { Text(label) })
            }
        }

        when (extensionTab) {
        0 -> {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scriptPicker.launch(arrayOf("text/javascript", "text/plain", "application/javascript")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Import file")
            }
            OutlinedButton(onClick = { scriptCode = ""; editorOpen = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Code, null)
                Spacer(Modifier.width(8.dp))
                Text("Paste script")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { urlInstallOpen = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Link, null)
                Spacer(Modifier.width(8.dp))
                Text("Install URL")
            }
            OutlinedButton(
                onClick = { vm.checkForExtensionUpdates() },
                modifier = Modifier.weight(1f),
                enabled = !state.isCheckingExtensionUpdates
            ) {
                if (state.isCheckingExtensionUpdates) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isCheckingExtensionUpdates) "Checking…" else "Check updates")
            }
        }

        // Extension Updates Banner
        if (state.availableExtensionUpdates.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text("${state.availableExtensionUpdates.size} Extension Update(s) Available", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text("Remote updates can be pushed and installed without releasing an APK update.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(
                        onClick = { vm.applyAllExtensionUpdates() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DownloadDone, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Update all extensions")
                    }
                }
            }
        }

        Text("Installed Resolvers (${state.installedScripts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Direct URL Resolver", fontWeight = FontWeight.Bold)
                    AssistChip({}, { Text("Built-in") })
                }
                Text("Handles direct media, binary files, ISO, APK, archives, and BitTorrent magnets.", style = MaterialTheme.typography.bodySmall)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Redirect & Shortener Resolver", fontWeight = FontWeight.Bold)
                    AssistChip({}, { Text("Built-in") })
                }
                Text("Follows HTTP redirects up to 5 hops with circular loop detection.", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.installedScripts.sortedWith(compareByDescending<UserscriptMetadata> { it.builtIn }.thenBy { it.name }).forEach { script ->
            val update = state.availableExtensionUpdates.firstOrNull { it.scriptId == script.id }
            UserscriptCard(script, update, vm)
        }

        if (state.installedScripts.isEmpty()) {
            Text("No userscript resolvers installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        1 -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Curated extensions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Reviewed resolver scripts maintained with OmniDL. Installation still shows every permission before activation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = { vm.loadDiscoverCatalog() },
                    enabled = !state.isFetchingDiscover
                ) {
                    if (state.isFetchingDiscover) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Catalog")
                    }
                }
            }
            val catalog = state.discoverExtensions.ifEmpty { vm.extensionCatalog }
            catalog.forEach { extension ->
                val installed = state.installedScripts.any { it.id == extension.id || it.name.equals(extension.name, ignoreCase = true) }
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(42.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Extension, null) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(extension.name, fontWeight = FontWeight.Bold)
                                Text("v${extension.version} • ${extension.category ?: "resolver"}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (installed) AssistChip(onClick = {}, label = { Text("Installed") }, leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) })
                        }
                        extension.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("${extension.matches.size + extension.includes.size} site rule(s) • ${extension.connects.joinToString().ifBlank { "direct" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!installed) {
                            Button(
                                onClick = {
                                    if (extension.rawScript.isNotBlank()) {
                                        pendingInstall = extension
                                    } else if (!extension.downloadUrl.isNullOrBlank()) {
                                        vm.fetchAndReviewScriptUrl(extension.downloadUrl)
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Review & install")
                            }
                        }
                    }
                }
            }
        }
        else -> {
            Text("Resolver playground", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Test a script against one URL before installing it. Tests use the same permissions, network sandbox and result limits as installed extensions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(debugUrl, { debugUrl = it }, Modifier.fillMaxWidth(), label = { Text("Test URL") }, leadingIcon = { Icon(Icons.Default.Link, null) }, singleLine = true)
            OutlinedTextField(scriptCode, { scriptCode = it }, Modifier.fillMaxWidth(), label = { Text("Userscript source") }, minLines = 10, maxLines = 16)
            Button(onClick = { vm.testScript(scriptCode, debugUrl) }, enabled = !state.isDebugging && scriptCode.isNotBlank() && debugUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (state.isDebugging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp)); Text(if (state.isDebugging) "Running…" else "Run safely")
            }
            state.debugResult?.let { result ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (result.success) "${result.resolvedItems.size} result(s)" else "Test failed", fontWeight = FontWeight.Bold, color = if (result.success) Color(0xFF16803A) else MaterialTheme.colorScheme.error)
                        Text("${result.executionTimeMs} ms • ${result.networkCalls.size} network call(s)", style = MaterialTheme.typography.labelSmall)
                        result.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        result.resolvedItems.take(5).forEach { Text("• ${it.filename ?: it.url}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        result.logs.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text("Import userscript") },
            text = {
                OutlinedTextField(
                    value = scriptCode,
                    onValueChange = { scriptCode = it },
                    label = { Text(".user.js source") },
                    minLines = 10,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = vm.parseScriptForReview(scriptCode)
                    if (parsed == null) Toast.makeText(context, "Invalid userscript metadata block", Toast.LENGTH_LONG).show()
                    else { pendingInstall = parsed; editorOpen = false }
                }, enabled = scriptCode.isNotBlank()) { Text("Review permissions") }
            },
            dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("Cancel") } }
        )
    }

    pendingInstall?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            icon = { Icon(Icons.Default.Security, null) },
            title = { Text("Install ${script.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${script.version}${script.author?.let { " • $it" }.orEmpty()}")
                    Text("Runs on", fontWeight = FontWeight.Bold)
                    Text((script.matches + script.includes).joinToString("\n").ifBlank { "No URL matches declared" }, style = MaterialTheme.typography.bodySmall)
                    Text("Network access", fontWeight = FontWeight.Bold)
                    Text(script.connects.joinToString("\n").ifBlank { "Only matched page hosts" }, style = MaterialTheme.typography.bodySmall)
                    Text("API permissions", fontWeight = FontWeight.Bold)
                    Text(script.grants.joinToString().ifBlank { "none" }, style = MaterialTheme.typography.bodySmall)
                    if (script.connects.any { it.trim() == "*" }) {
                        Text("Warning: this script requests access to every public host.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (vm.installScript(script.rawScript)) pendingInstall = null
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { pendingInstall = null }) { Text("Cancel") } }
        )
    }

    if (urlInstallOpen) {
        AlertDialog(
            onDismissRequest = { urlInstallOpen = false },
            title = { Text("Install from URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the remote URL of a .user.js resolver script to fetch and install dynamically.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = remoteScriptUrl,
                        onValueChange = { remoteScriptUrl = it },
                        label = { Text("Userscript URL") },
                        placeholder = { Text("https://example.com/resolver.user.js") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                                if (clip.isNotBlank()) remoteScriptUrl = clip
                            }) {
                                Icon(Icons.Default.ContentPaste, "Paste")
                            }
                        }
                    )
                    if (state.isFetchingUrlScript) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching userscript from network…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.fetchAndReviewScriptUrl(remoteScriptUrl)
                        urlInstallOpen = false
                    },
                    enabled = remoteScriptUrl.isNotBlank() && !state.isFetchingUrlScript
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Fetch & Review")
                }
            },
            dismissButton = {
                TextButton(onClick = { urlInstallOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (state.isFetchingUrlScript && !urlInstallOpen) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Fetching Extension") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text("Downloading userscript from repository…", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun UserscriptCard(script: UserscriptMetadata, update: ExtensionUpdateInfo?, vm: MainViewModel) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(script.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("v${script.version}${script.category?.let { " • $it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                }
                if (script.builtIn) AssistChip(onClick = {}, label = { Text("Built-in") })
                Switch(checked = script.enabled, onCheckedChange = { vm.setScriptEnabled(script.id, it) })
            }
            script.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "${script.matches.size + script.includes.size} URL rule(s) • ${script.connects.size} network rule(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Show update badge and button if an update is available
            update?.let { up ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Update to v${up.newVersion} available", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            if (up.newPermissions.isNotEmpty()) {
                                Text("New permissions: ${up.newPermissions.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Button(onClick = { vm.applyExtensionUpdate(up) }) {
                            Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Update")
                        }
                    }
                }
            }

            if (!script.builtIn) {
                TextButton(onClick = { vm.uninstallScript(script.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Uninstall")
                }
            }
        }
    }
}

@Composable
private fun TorrentScreen(initialMagnet: String, state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var source by rememberSaveable(initialMagnet) { mutableStateOf(initialMagnet) }
    var folder by remember(state.settings.defaultTreeUri) { mutableStateOf(state.settings.defaultTreeUri) }
    val clipboardManager = LocalClipboardManager.current
    val clipboardText = remember { clipboardManager.getText()?.text?.trim().orEmpty() }
    val showClipboardMagnet = remember(clipboardText, source) {
        clipboardText.isNotBlank() &&
            clipboardText.startsWith("magnet:?", ignoreCase = true) &&
            clipboardText != source
    }

    LaunchedEffect(source) {
        val value = source.trim()
        if (value.startsWith("magnet:?", true) || value.startsWith("content:") || value.substringBefore('?').endsWith(".torrent", true)) {
            kotlinx.coroutines.delay(650)
            vm.inspectTorrent(value)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source = it.toString()
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeTreePermission(context, it)
            folder = it.toString()
            vm.setDefaultTree(it.toString())
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(18.dp), color = OmniTorrent.copy(alpha = 0.14f), modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudDownload, null, Modifier.size(30.dp), tint = OmniTorrent) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("BitTorrent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("P2P Transfers • libtorrent4j", color = OmniTorrent, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Paste a magnet link or choose a .torrent file. OmniDL inspects its metadata and file tree before downloading.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        if (showClipboardMagnet) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = OmniTorrent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, OmniTorrent.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth().clickable {
                    source = clipboardText
                    vm.clearTorrentMetadata()
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp), tint = OmniTorrent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Paste magnet from clipboard", style = MaterialTheme.typography.labelSmall, color = OmniTorrent, fontWeight = FontWeight.Bold)
                        Text(clipboardText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        OutlinedTextField(
            value = source,
            onValueChange = { source = it; vm.clearTorrentMetadata() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Magnet link or content URI") },
            leadingIcon = { Icon(Icons.Default.Link, null) },
            trailingIcon = {
                if (source.isNotBlank()) IconButton({ source = ""; vm.clearTorrentMetadata() }) { Icon(Icons.Default.Close, "Clear") }
            },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(16.dp)
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().clickable {
                filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream"))
            }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FileOpen, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Select .torrent File", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Browse device storage for a torrent file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state.isTorrentInspecting) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Column { Text("Fetching torrent metadata", fontWeight = FontWeight.Bold); Text("Finding peers and reading the file list…", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        state.torrentMetadata?.takeIf { it.source == source.trim() }?.let { metadata ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(metadata.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${formatBytes(metadata.totalBytes)} • ${metadata.files.size} ${if (metadata.files.size == 1) "file" else "files"} • ${metadata.trackerCount} trackers", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                    metadata.files.take(6).forEach { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(file.path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(12.dp)); Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (metadata.files.size > 6) Text("+ ${metadata.files.size - 6} more files", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Hash ${metadata.infoHash.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        FolderButton(folder, { treePicker.launch(null) })
        if (folder.isNotBlank()) Text("This folder is remembered for future downloads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val ready = state.torrentMetadata?.source == source.trim()
        Button(
            { if (ready) vm.addTorrent(source, folder) else vm.inspectTorrent(source) },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = source.isNotBlank() && !state.isTorrentInspecting && (!ready || folder.isNotBlank())
        ) {
            Icon(if (ready) Icons.Default.Download else Icons.Default.Search, null)
            Spacer(Modifier.width(8.dp))
            Text(if (ready) "Download now" else "Get metadata")
        }
        HorizontalDivider()
        Text("Download and share only content you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeTreePermission(context, it)
            vm.setDefaultTree(it.toString())
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingSlider("Simultaneous downloads", state.settings.maxConcurrent, 1..8, vm::setMax)
                SettingSlider("HTTP connections per download", state.settings.connections, 1..16, vm::setConnections)
                SettingSwitch("Eco mode", "Lower memory and battery use", state.settings.ecoMode, vm::setEcoMode)
                SettingSwitch("Wi-Fi only", "Avoid mobile data by default", state.settings.wifiOnly, vm::setWifiOnly)
                SettingSwitch("Auto-resume", "Continue interrupted transfers", state.settings.autoResume, vm::setAutoResume)
            }
        }
        Text("Application updates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingSwitch("Automatic checks", "Check GitHub Releases daily", state.settings.automaticUpdateChecks, vm::setAutomaticUpdateChecks)
                OutlinedButton(vm::checkForUpdates, Modifier.fillMaxWidth(), enabled = !state.isCheckingForUpdates) {
                    if (state.isCheckingForUpdates) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isCheckingForUpdates) "Checking…" else "Check now")
                }
            }
        }
        state.availableUpdate?.let { update ->
            val progress = state.updateProgress
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(update.title, fontWeight = FontWeight.Bold)
                            Text("Version ${update.version}${if (update.size > 0) " • ${formatBytes(update.size)}" else ""}", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (update.notes.isNotBlank() && progress == null) {
                        Text(update.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }

                    if (progress != null) {
                        if (progress.error != null) {
                            Text(progress.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = vm::downloadUpdate,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Retry download")
                            }
                        } else if (progress.isCompleted && progress.readyToInstallApk != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF16803A), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Download verified (SHA-256)", fontWeight = FontWeight.SemiBold, color = Color(0xFF16803A), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = vm::installDownloadedUpdate,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16803A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.SystemUpdate, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Tap to install update now")
                            }
                        } else {
                            // In progress
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (progress.totalBytes > 0) {
                                    LinearProgressIndicator(
                                        progress = { progress.progressFraction },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "${progress.percentage}% (${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val metrics = buildList {
                                        if (progress.speedBytesPerSecond > 0) add("↓ ${formatBytes(progress.speedBytesPerSecond)}/s")
                                        progress.etaSeconds?.let { if (it > 0) add("ETA ${formatDuration(it)}") }
                                    }.joinToString(" • ")
                                    Text(metrics, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                OutlinedButton(
                                    onClick = vm::cancelUpdateDownload,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = vm::downloadUpdate,
                            enabled = update.sha256 != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (update.sha256 != null) "Download & install update" else "Digest unavailable")
                        }
                    }
                }
            }
        }
        Text("Storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        FolderButton(state.settings.defaultTreeUri, { treePicker.launch(null) })
        Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("SYSTEM", "LIGHT", "DARK").forEachIndexed { i, value ->
                SegmentedButton(
                    selected = state.settings.theme == value,
                    onClick = { vm.setTheme(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(value.lowercase().replaceFirstChar { it.titlecase() })
                }
            }
        }
        HorizontalDivider()
        Text("Omni Downloader Pro • v${BuildConfig.VERSION_NAME} • All Engines Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderButton(folder: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Folder, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (folder.isBlank()) "Choose Download Folder" else "Download Destination",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (folder.isBlank()) "No directory set (tap to configure)" else folder.takeLast(60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (folder.isBlank()) "Set" else "Change", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, trailingContent = { Switch(checked, onChange) })
}

@Composable
private fun SettingSlider(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(pending.toInt().toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = { onChange(pending.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Default.Downloading,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(82.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> Color(0xFF16803A)
    else -> MaterialTheme.colorScheme.primary
}

private fun statusLabel(status: DownloadStatus) = when (status) {
    DownloadStatus.WAITING -> "Queued"
    DownloadStatus.RESOLVING -> "Preparing"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

private fun takeTreePermission(context: Context, uri: Uri) =
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty().ifBlank { "Torrent download" }

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", value, units[i])
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}
