@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.omnidownloader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidownloader.domain.model.DownloadStatus
import com.omnidownloader.domain.model.DownloadTask
import com.omnidownloader.domain.model.DownloadSource
import com.omnidownloader.ui.theme.OmniMint
import com.omnidownloader.ui.theme.OmniSpeedCyan
import com.omnidownloader.ui.theme.OmniWarning

object OmniSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

@Composable
fun OmniSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun OmniSegmentedTabs(items: List<String>, selectedIndex: Int, counts: List<Int> = emptyList(), onSelected: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = OmniSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xs)
    ) {
        items.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                tween(180), label = "tabColor"
            )
            Row(
                Modifier.clip(RoundedCornerShape(9.dp)).background(background)
                    .clickable(role = Role.Tab) { onSelected(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                counts.getOrNull(index)?.takeIf { it > 0 }?.let {
                    Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun OmniSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search downloads") {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } }
            )
            AnimatedVisibility(value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton({ onValueChange("") }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Clear search", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
fun OmniStatusBadge(status: DownloadStatus) {
    val color = omniStatusColor(status)
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = .12f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(omniStatusLabel(status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun OmniDownloadProgress(progress: Float?, status: DownloadStatus, modifier: Modifier = Modifier) {
    val color = omniStatusColor(status)
    if (progress == null) LinearProgressIndicator(modifier = modifier.height(4.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = StrokeCap.Round)
    else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier.height(4.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = StrokeCap.Round)
}

@Composable
fun OmniStat(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun OmniSettingRow(icon: ImageVector, title: String, description: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.invoke()
    }
}

@Composable
fun omniStatusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING -> OmniSpeedCyan
    DownloadStatus.COMPLETED -> OmniMint
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.error
    DownloadStatus.WAITING -> OmniWarning
    DownloadStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun omniStatusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.WAITING -> "Queued"
    DownloadStatus.RESOLVING -> "Resolving"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

@Composable
fun OmniDownloadRow(
    task: DownloadTask,
    formatBytes: (Long) -> String,
    formatDuration: (Long) -> String,
    onClick: () -> Unit,
    onPrimaryAction: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = when {
        task.source is DownloadSource.Magnet || task.source is DownloadSource.TorrentFile -> com.omnidownloader.ui.theme.OmniTorrent
        task.mimeType.startsWith("video/") -> com.omnidownloader.ui.theme.OmniVideo
        task.mimeType.startsWith("audio/") -> com.omnidownloader.ui.theme.OmniAudio
        task.mimeType.startsWith("image/") -> OmniMint
        task.fileName.endsWith(".apk", true) -> com.omnidownloader.ui.theme.OmniAppGreen
        task.fileName.substringAfterLast('.', "").lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> com.omnidownloader.ui.theme.OmniArchive
        else -> MaterialTheme.colorScheme.secondary
    }
    val icon = when {
        task.source is DownloadSource.Magnet || task.source is DownloadSource.TorrentFile -> Icons.Default.CloudDownload
        task.mimeType.startsWith("video/") -> Icons.Default.Movie
        task.mimeType.startsWith("audio/") -> Icons.Default.MusicNote
        task.mimeType.startsWith("image/") -> Icons.Default.Image
        task.fileName.endsWith(".apk", true) -> Icons.Default.Android
        task.fileName.substringAfterLast('.', "").lowercase() in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Default.Archive
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val fraction = if (task.totalBytes > 0) (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) else null
    val percent = if (task.totalBytes > 0) (task.downloadedBytes * 100 / task.totalBytes).coerceIn(0, 100) else null
    val host = remember(task.source.value) { runCatching { android.net.Uri.parse(task.source.value).host }.getOrNull().orEmpty() }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(task.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(host.ifBlank { task.source.value }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OmniStatusBadge(task.status)
            IconButton(onMore, Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, "More actions", Modifier.size(19.dp)) }
        }

        if (task.status != DownloadStatus.COMPLETED || fraction != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (task.totalBytes > 0) "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}" else "Size unavailable",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                percent?.let { Text("$it%", style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Bold) }
            }
            OmniDownloadProgress(if (task.status == DownloadStatus.RESOLVING || (task.totalBytes <= 0 && task.status == DownloadStatus.DOWNLOADING)) null else fraction ?: 0f, task.status, Modifier.fillMaxWidth())
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    OmniStat("SPEED", "↓ ${formatBytes(task.speedBytesPerSecond)}/s", Modifier.weight(1.2f), OmniSpeedCyan)
                    OmniStat("ETA", task.etaSeconds?.let(formatDuration) ?: "—", Modifier.weight(.8f))
                    OmniStat("CONNECTIONS", task.connections.toString(), Modifier.weight(.8f))
                }
                DownloadStatus.COMPLETED -> OmniStat("SIZE", formatBytes(task.totalBytes), Modifier.weight(1f), OmniMint)
                DownloadStatus.FAILED -> Text(task.errorMessage ?: "Transfer failed", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                DownloadStatus.WAITING -> Text("Waiting for an available transfer slot", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DownloadStatus.PAUSED -> Text("Transfer is paused", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Spacer(Modifier.weight(1f))
            }
            val actionIcon = when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING -> Icons.Default.Pause
                DownloadStatus.COMPLETED -> Icons.AutoMirrored.Filled.OpenInNew
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> Icons.Default.PlayArrow
                else -> null
            }
            actionIcon?.let {
                FilledIconButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = omniStatusColor(task.status).copy(alpha = .16f), contentColor = omniStatusColor(task.status))
                ) { Icon(it, when (task.status) { DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING -> "Pause"; DownloadStatus.COMPLETED -> "Open"; DownloadStatus.FAILED -> "Retry"; else -> "Resume" }, Modifier.size(19.dp)) }
            }
        }
    }
}
