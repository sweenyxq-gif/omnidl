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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidownloader.domain.inspector.LinkInspection
import com.omnidownloader.domain.model.*
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.resolver.ResolvedItem
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
    val context = LocalContext.current
    val colors = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(primary = Color(0xFFA8B4FF), secondary = Color(0xFF7DD3FC))
    } else {
        lightColorScheme(primary = Color(0xFF4457C4), secondary = Color(0xFF0369A1), surfaceVariant = Color(0xFFE8EAF6))
    }

    MaterialTheme(colorScheme = colors) {
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

        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                viewModel.clearMessage()
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(destination.label, fontWeight = FontWeight.Bold)
                            if (destination == Destination.DOWNLOADS && state.tasks.isNotEmpty()) {
                                Text(
                                    "${state.tasks.count { it.status == DownloadStatus.DOWNLOADING }} active • ${formatBytes(state.tasks.sumOf { it.downloadedBytes })}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (destination == Destination.DOWNLOADS) {
                            IconButton(viewModel::pauseAll) { Icon(Icons.Default.Pause, "Pause all") }
                            IconButton(viewModel::resumeAll) { Icon(Icons.Default.PlayArrow, "Resume all") }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
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
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (destination == Destination.DOWNLOADS) {
                    FloatingActionButton(onClick = { destination = Destination.RESOLVE }) {
                        Icon(Icons.Default.Add, "Resolve or Download")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (destination) {
                    Destination.DOWNLOADS -> DownloadsScreen(state, viewModel)
                    Destination.RESOLVE -> ResolveScreen(if (torrentInput) "" else initialUrl, state, viewModel)
                    Destination.TORRENTS -> TorrentScreen(if (torrentInput) initialUrl else "", state, viewModel)
                    Destination.EXTENSIONS -> ExtensionsScreen()
                    Destination.SETTINGS -> SettingsScreen(state, viewModel)
                }

                // Link Inspector Dialog
                state.linkInspection?.let { inspection ->
                    LinkInspectionDialog(inspection, onDismiss = { viewModel.clearInspection() }) { url, filename ->
                        viewModel.addResolvedItem(
                            ResolvedItem(
                                label = "Inspected file",
                                url = url,
                                filename = filename,
                                type = SourceType.HTTP
                            )
                        )
                        viewModel.clearInspection()
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(state: MainUiState, vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { DownloadTab.entries.size }
    var query by rememberSaveable { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear search") }
            },
            placeholder = { Text("Search downloads") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp)
        )

        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp, divider = {}) {
            DownloadTab.entries.forEachIndexed { index, tab ->
                val count = state.tasks.count { matchesTab(it, tab) }
                Tab(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.label)
                            if (count > 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text(count.toString()) }
                            }
                        }
                    }
                )
            }
        }

        if (pager.currentPage == DownloadTab.COMPLETED.ordinal && state.tasks.any { it.status == DownloadStatus.COMPLETED }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(vm::clearCompleted) {
                    Icon(Icons.Default.CleaningServices, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Clear history")
                }
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
            val tab = DownloadTab.entries[page]
            val tasks = state.tasks.filter { matchesTab(it, tab) && (query.isBlank() || it.fileName.contains(query, true)) }
            if (tasks.isEmpty()) {
                EmptyState(
                    when {
                        query.isNotBlank() -> "No matching downloads"
                        tab == DownloadTab.ACTIVE -> "No active downloads"
                        tab == DownloadTab.QUEUED -> "Your queue is clear"
                        tab == DownloadTab.COMPLETED -> "Finished downloads appear here"
                        else -> "No failed downloads"
                    }
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tasks, key = { it.id }) { DownloadCard(it, vm) }
                }
            }
        }
    }
}

private fun matchesTab(task: DownloadTask, tab: DownloadTab) = when (tab) {
    DownloadTab.ACTIVE -> task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING, DownloadStatus.PAUSED)
    DownloadTab.QUEUED -> task.status == DownloadStatus.WAITING
    DownloadTab.COMPLETED -> task.status == DownloadStatus.COMPLETED
    DownloadTab.FAILED -> task.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)
}

@Composable
private fun DownloadCard(task: DownloadTask, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (task.source is DownloadSource.Magnet || task.source is DownloadSource.TorrentFile)
                                Icons.Default.CloudDownload
                            else Icons.AutoMirrored.Filled.InsertDriveFile,
                            null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.fileName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLabel(task.status), style = MaterialTheme.typography.labelMedium, color = statusColor(task.status))
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (task.status == DownloadStatus.COMPLETED) "Remove from history" else "Delete task") },
                            onClick = { menu = false; confirmDelete = true },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                        )
                    }
                }
            }
            if (task.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatBytes(task.downloadedBytes)} of ${formatBytes(task.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                    Text("${(task.downloadedBytes * 100 / task.totalBytes).coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            } else if (task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            task.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (task.status !in setOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED, DownloadStatus.WAITING)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING -> TextButton({ vm.pause(task.id) }) {
                            Icon(Icons.Default.Pause, null); Text("Pause")
                        }
                        DownloadStatus.PAUSED, DownloadStatus.FAILED -> TextButton({ vm.resume(task.id) }) {
                            Icon(Icons.Default.PlayArrow, null); Text("Resume")
                        }
                        else -> Unit
                    }
                    TextButton({ vm.cancel(task.id) }) { Icon(Icons.Default.Close, null); Text("Cancel") }
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
private fun ResolveScreen(initialUrl: String, state: MainUiState, vm: MainViewModel) {
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

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Resolve & Inspect Link", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Resolving never starts a download automatically. Inspect link metadata or choose streams to download.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                        vm.addResolvedItem(item)
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
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when (item.type) {
                                SourceType.MAGNET -> Icons.Default.CloudDownload
                                SourceType.HLS, SourceType.DASH -> Icons.Default.Movie
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            },
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label ?: item.filename ?: "Resolved Item", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.type.name} • ${item.size?.let { formatBytes(it) } ?: "Size unknown"} • ${item.mimeType ?: "stream"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Action Row: [Copy Link], [Share], [Open Browser], [Inspect], [Download]
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
                        Icon(Icons.Default.ContentCopy, "Copy link")
                    }

                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, item.url)
                        }
                        context.startActivity(Intent.createChooser(share, "Share Link"))
                    }) {
                        Icon(Icons.Default.Share, "Share link")
                    }

                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser")
                    }

                    IconButton(onClick = onInspect) {
                        Icon(Icons.Default.Search, "Inspect Link")
                    }
                }

                Button(onClick = onDownload, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Download, null)
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
private fun ExtensionsScreen() {
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
            "OmniDownloader supports custom Tampermonkey-compatible .user.js and .omni packages. Extensions run in a secure JavaScript sandbox with explicit permissions.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Installed Resolvers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

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

        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Generic Webpage Extractor", fontWeight = FontWeight.Bold)
                    AssistChip({}, { Text("Built-in") })
                }
                Text("Scrapes HTML for OpenGraph, HTML5 video/audio, HLS (.m3u8), DASH (.mpd), and direct links.", style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()
        Text("Phase 3 & 4: Userscript Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Userscript sandboxed runtime and .omni ZIP package installation ready to be activated in Phase 3.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TorrentScreen(initialMagnet: String, state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var source by rememberSaveable(initialMagnet) { mutableStateOf(initialMagnet) }
    var name by rememberSaveable { mutableStateOf("") }
    var folder by remember(state.settings.defaultTreeUri) { mutableStateOf(state.settings.defaultTreeUri) }
    var startNow by remember { mutableStateOf(true) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source = it.toString()
            if (name.isBlank()) name = displayName(context, it).removeSuffix(".torrent")
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takeTreePermission(context, it)
            folder = it.toString()
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(58.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudDownload, null, Modifier.size(32.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("BitTorrent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Phase 2 • libtorrent engine", color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("Add a magnet link or open a .torrent file. Downloads resume from verified pieces after interruption.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            source, { source = it },
            Modifier.fillMaxWidth(),
            label = { Text("Magnet link") },
            leadingIcon = { Icon(Icons.Default.Link, null) },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(16.dp)
        )
        OutlinedButton(
            { filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) },
            Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.FileOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Choose .torrent file")
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Download name (optional)") }, singleLine = true)
        FolderButton(folder, { treePicker.launch(null) })
        ListItem(
            headlineContent = { Text("Start immediately") },
            supportingContent = { Text(if (startNow) "Start discovering peers now" else "Add paused") },
            trailingContent = { Switch(startNow, { startNow = it }) },
            leadingContent = { Icon(Icons.Default.PlayCircle, null) }
        )
        Button(
            { vm.addTorrent(source, name, folder, startNow) },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = source.isNotBlank() && folder.isNotBlank()
        ) {
            Icon(Icons.Default.CloudDownload, null)
            Spacer(Modifier.width(8.dp))
            Text("Add torrent")
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
        SettingSlider("Simultaneous downloads", state.settings.maxConcurrent, 1..8, vm::setMax)
        SettingSlider("HTTP connections per download", state.settings.connections, 1..16, vm::setConnections)
        SettingSwitch("Wi-Fi only by default", "Avoid mobile data", state.settings.wifiOnly, vm::setWifiOnly)
        SettingSwitch("Auto-resume", "Continue interrupted transfers after restart", state.settings.autoResume, vm::setAutoResume)
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
        Text("Omni Downloader 0.2.0 • Phase 1 & 2 Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderButton(folder: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick, Modifier.fillMaxWidth().height(50.dp)) {
            Icon(Icons.Default.Folder, null)
            Spacer(Modifier.width(8.dp))
            Text(if (folder.isBlank()) "Choose save folder" else "Change save folder")
        }
        if (folder.isNotBlank()) {
            Text(folder.takeLast(72), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, trailingContent = { Switch(checked, onChange) })
}

@Composable
private fun SettingSlider(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(value.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(76.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Downloading, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
