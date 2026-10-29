package com.toadfiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toadfiles.app.domain.ConflictStrategy
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.SortMode
import com.toadfiles.app.domain.SortOrder
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    isFavorite: Boolean,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else Color.Transparent
    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(item),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle ?: run {
                        if (item.isDir) "Folder" else "${Format.size(item.size)} · ${Format.date(item.lastModified)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFavorite) {
                Text("★", color = MaterialTheme.colorScheme.tertiary)
            }
            if (selected) {
                Checkbox(checked = true, onCheckedChange = null)
            }
        }
    }
}

fun iconFor(item: FileItem): ImageVector = if (item.isDir) {
    Icons.Outlined.Folder
} else {
    when (item.name.substringAfterLast('.', "").lowercase()) {
        in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg") -> Icons.Outlined.Image
        in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v") -> Icons.Outlined.Videocam
        in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "amr") -> Icons.Outlined.Audiotrack
        in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> Icons.Outlined.FolderZip
        "apk" -> Icons.Outlined.InstallMobile
        in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv") ->
            Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

fun filterIconFor(mode: FilterMode): ImageVector = when (mode) {
    FilterMode.ALL -> Icons.Outlined.InsertDriveFile
    FilterMode.DOCS -> Icons.Outlined.Description
    FilterMode.IMAGES -> Icons.Outlined.Image
    FilterMode.VIDEOS -> Icons.Outlined.Videocam
    FilterMode.AUDIO -> Icons.Outlined.Audiotrack
    FilterMode.ARCHIVES -> Icons.Outlined.FolderZip
    FilterMode.APKS -> Icons.Outlined.InstallMobile
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = true,
                colors = if (destructive) androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ) else androidx.compose.material3.ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConflictDialog(
    conflicts: List<String>,
    targetName: String,
    onDecide: (ConflictStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conflicts in \"$targetName\"") },
        text = {
            Column {
                Text(
                    "${conflicts.size} item(s) already exist here:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    conflicts.take(5).joinToString("\n") { "• $it" } +
                        if (conflicts.size > 5) "\n… and ${conflicts.size - 5} more" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(12.dp))
                ConflictStrategy.entries.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDecide(s) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = false, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (s) {
                                ConflictStrategy.RENAME -> "Keep both (add \"(1)\", \"(2)\", …)"
                                ConflictStrategy.REPLACE -> "Replace existing"
                                ConflictStrategy.SKIP -> "Skip conflicting items"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ProgressOverlay(text: String, fraction: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text) },
        text = {
            Column {
                if (fraction != null && fraction >= 0f) {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Working…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun ResultDialog(
    summary: String,
    failures: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Operation finished") },
        text = {
            Column {
                Text(summary, style = MaterialTheme.typography.bodyLarge)
                if (failures.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Details:", style = MaterialTheme.typography.titleMedium)
                    Text(
                        failures.take(10).joinToString("\n") { "• $it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun SortMenu(
    expanded: Boolean,
    current: SortMode,
    currentOrder: SortOrder,
    onSelect: (SortMode) -> Unit,
    onSelectOrder: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMode.entries.forEach { mode ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (mode == current) "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} ✓" else mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                onClick = { onSelect(mode) },
            )
        }
        androidx.compose.material3.HorizontalDivider()
        SortOrder.entries.forEach { order ->
            val label = if (order == SortOrder.ASC) "Ascending" else "Descending"
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (order == currentOrder) "$label ✓" else label) },
                onClick = { onSelectOrder(order) },
            )
        }
    }
}
