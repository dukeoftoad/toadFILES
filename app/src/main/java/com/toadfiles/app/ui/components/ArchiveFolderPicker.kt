package com.toadfiles.app.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal folder picker for the v1.7.3 "Archive folder" setting (no reusable
 * picker existed anywhere in ui/). Browses the directories of external storage,
 * navigates up/down, and confirms with "Use this folder"; "Same folder as source"
 * resets the preference to blank (v1.7.2 behavior). Labels are sentence case,
 * like every other dialog in the app.
 */
@Suppress("DEPRECATION") // Environment.getExternalStorageDirectory(): primary shared storage is all this picker needs.
@Composable
fun ArchiveFolderPicker(
    initial: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val root = remember {
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
    }
    var dir by remember { mutableStateOf(File(initial.ifBlank { root })) }

    // Audit A6: the listing runs two syscalls per entry on the main thread whenever the
    // user navigates — moved onto IO via LaunchedEffect. The previous folder's list
    // stays visible until the fresh one lands (no blink), matching the pull-to-refresh
    // discipline everywhere else in the app.
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(dir) {
        subdirs = withContext(Dispatchers.IO) {
            runCatching {
                dir.listFiles()
                    ?.filter { it.isDirectory && it.canRead() }
                    ?.sortedBy { it.name.lowercase() }
            }.getOrNull() ?: emptyList()
        }
    }
    val canGoUp = dir.absolutePath != root

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive folder") },
        text = {
            Column {
                Text(
                    dir.absolutePath,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 288.dp)) {
                    if (canGoUp) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { dir = dir.parentFile ?: dir }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("Up one level", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    items(subdirs, key = { it.absolutePath }) { sub ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { dir = sub }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(sub.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (subdirs.isEmpty()) {
                        item {
                            Text(
                                "No subfolders here",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                TextButton(onClick = { onSelect("") }) {
                    Text("Same folder as source")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(dir.absolutePath) }) { Text("Use this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
