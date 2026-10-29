package com.toadfiles.app.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.StorageRoot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    vm: StorageViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (state.roots.isEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
            }

            // One card per mounted volume: internal storage, SD cards, USB drives.
            items(state.roots, key = { it.dir.path }) { root ->
                VolumeCard(
                    root = root,
                    analyzing = state.analyzing,
                    onOpen = { onOpenFolder(root.dir.path) },
                    onAnalyze = { vm.analyzeTopLevel(root.dir) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.analyzedRoot != null)
                        "Top-level folders of \"${state.analyzedRoot!!.name}\""
                    else "Top-level folders",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Sizes are computed on demand by walking each folder — this can take a " +
                        "while on large libraries. Nothing runs in the background.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (state.analyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.cancelAnalyze() }) { Text("Cancel") }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Analyzed so far: ${Format.size(state.analyzedBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (state.analyzing && state.usages.isEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
            }

            items(state.usages, key = { it.dir.path }) { u ->
                val max = state.usages.firstOrNull()?.sizeBytes ?: 1L
                val fraction = if (max <= 0) 0f else u.sizeBytes.toFloat() / max
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(
                                u.dir.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                Format.size(u.sizeBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    }
                    IconButton(onClick = { onOpenFolder(u.dir.path) }) {
                        Icon(Icons.Outlined.FolderOpen, "Open")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun VolumeCard(
    root: StorageRoot,
    analyzing: Boolean,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val stats = root.stats
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    root.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (root.removable) {
                    Text(
                        "removable",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                IconButton(onClick = onOpen) { Icon(Icons.Outlined.FolderOpen, "Browse this volume") }
            }
            if (stats == null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                LinearProgressIndicator(
                    progress = { stats.usedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Format.size(stats.usedBytes)} used of ${Format.size(stats.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${Format.size(stats.freeBytes)} free",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!analyzing) {
                    TextButton(onClick = onAnalyze) { Text("Analyze this volume") }
                }
            }
        }
    }
}
