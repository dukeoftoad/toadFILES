package com.toadfiles.app.ui.analyzer

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.FileItem
import com.toadfiles.app.domain.FilterMode
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.StorageRoot
import com.toadfiles.app.ui.browse.PropertiesDetail
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.ui.components.PropertiesSheet
import com.toadfiles.app.ui.components.filterIconFor
import com.toadfiles.app.ui.components.iconFor
import java.io.File

/**
 * MI Files / Google Files style storage analyzer: category breakdown, biggest files and
 * largest folders for one volume, from a single on-demand recursive walk.
 *
 * TODO(integration): register the route in ToadNavHost (Routes + composable), e.g.
 * ```
 * const val STORAGE_ANALYZER = "storage_analyzer?root={root}"
 * fun storageAnalyzer(root: String = "") = "storage_analyzer?root=${Uri.encode(root)}"
 *
 * composable(
 *     route = Routes.STORAGE_ANALYZER,
 *     arguments = listOf(navArgument("root") { type = NavType.StringType; defaultValue = "" }),
 * ) { entry ->
 *     StorageAnalyzerScreen(
 *         bottomPadding = padding.calculateBottomPadding(),
 *         onBack = { navController.popBackStack() },
 *         onOpenFolder = { navController.navigateSingleTop(Routes.browse(it)) },
 *         initialRoot = entry.arguments?.getString("root").orEmpty(),
 *     )
 * }
 * ```
 * and open it from StorageScreen's "Analyze storage" card via the onOpenAnalyzer
 * callback. StorageAnalyzerViewModel must also be registered in ToadViewModelFactory
 * (done in this branch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    initialRoot: String = "",
    vm: StorageAnalyzerViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Deep link: start scanning straight away when a root was passed via the route.
    LaunchedEffect(initialRoot) {
        if (initialRoot.isNotBlank()) vm.autoScan(File(initialRoot))
    }

    // Read-only info view for a tapped big file (action sheets are owned by Browse).
    var pendingProperties by remember { mutableStateOf<File?>(null) }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
                title = { Text("Analyze storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val root = state.root
                        if (root != null) vm.scan(root) else vm.loadVolumes()
                    }) { Icon(Icons.Outlined.Refresh, "Refresh") }
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
            // ---- Error state (unreadable / missing folder, permission gap) ----
            if (state.error != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Could not analyze",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                state.error ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.root != null) {
                                TextButton(onClick = { vm.scan(state.root!!) }) { Text("Try again") }
                            }
                        }
                    }
                }
            }

            // ---- Intro: pick a volume to analyze ----
            if (!state.scanning && state.result == null && state.error == null) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                "What's eating your storage?",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Pick a volume for a full breakdown: sizes by category, " +
                                    "biggest files and largest folders. The scan runs on " +
                                    "demand and can be cancelled at any time.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            if (state.volumesLoading) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Looking for volumes…",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            } else {
                                state.volumes.forEach { root ->
                                    VolumeAnalyzeRow(root = root, onAnalyze = { vm.scan(root.dir) })
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ---- Scan progress (files scanned count, cancellable) ----
            if (state.scanning) {
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Scanning \"${state.root?.name ?: ""}\" — " +
                                    "${state.scannedFiles} file(s) · ${Format.size(state.scannedBytes)} so far",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { vm.cancelScan() }) { Text("Cancel") }
                        }
                    }
                }
            }

            val result = state.result
            if (result != null) {
                // ---- Empty state ----
                if (result.fileCount == 0) {
                    item {
                        EmptyState("Nothing found to analyze in this folder.")
                    }
                } else {
                    // ---- Summary ----
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Scanned", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${result.fileCount} file(s) · ${Format.size(result.totalBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.reset() }) { Text("Change volume") }
                        }
                    }

                    // ---- Category breakdown: horizontal bars, MI Files style ----
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("By category", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(10.dp))
                                val maxBytes = result.categories.maxOfOrNull { it.sizeBytes } ?: 1L
                                result.categories.forEach { c ->
                                    CategoryBarRow(
                                        usage = c,
                                        // Bar length is relative to the biggest category
                                        // (same treatment as the Storage screen's media rows).
                                        fillFraction = if (maxBytes > 0L) {
                                            (c.sizeBytes.toFloat() / maxBytes).coerceIn(0.03f, 1f)
                                        } else 0f,
                                        percentText = percentOf(c.sizeBytes, result.totalBytes),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }

                    // ---- Biggest files ----
                    if (result.biggestFiles.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text("Biggest files", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tap a file for details.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        items(result.biggestFiles, key = { it.file.path }) { f ->
                            BigFileRow(
                                bigFile = f,
                                onClick = { pendingProperties = f.file },
                            )
                        }
                    }

                    // ---- Largest folders (recursive sizes) ----
                    if (result.largestFolders.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text("Largest folders", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Sizes include everything inside each folder.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        items(result.largestFolders, key = { it.dir.path }) { u ->
                            LargeFolderRow(
                                usage = u,
                                rootPath = state.root?.absolutePath,
                                onOpen = { onOpenFolder(u.dir.path) },
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Read-only properties view for the tapped big file. Plain files need no walk, so
    // the detail is available immediately.
    pendingProperties?.let { file ->
        PropertiesSheet(
            file = file,
            detail = PropertiesDetail(
                sizeText = Format.size(file.length()),
                sizeBytes = file.length(),
                fileCount = 0,
                dirCount = 0,
            ),
            onDismiss = { pendingProperties = null },
        )
    }
}

/** Integer percent of the total, with "<1%" so small-but-real buckets never read as zero. */
private fun percentOf(size: Long, total: Long): String {
    if (total <= 0 || size <= 0) return "0%"
    val pct = size * 100 / total
    return if (pct == 0L) "<1%" else "$pct%"
}

@Composable
private fun VolumeAnalyzeRow(root: StorageRoot, onAnalyze: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onAnalyze)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(root.label, style = MaterialTheme.typography.bodyLarge)
            val stats = root.stats
            Text(
                if (stats != null) {
                    "${Format.size(stats.usedBytes)} used of ${Format.size(stats.totalBytes)}"
                } else "Mounted volume",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAnalyze) { Text("Analyze") }
    }
}

@Composable
private fun CategoryBarRow(usage: CategoryUsage, fillFraction: Float, percentText: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                filterIconFor(filterModeOf(usage.category)),
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                usage.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Format.size(usage.sizeBytes)} · $percentText",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fillFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }
}

@Composable
private fun BigFileRow(bigFile: BigFile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(
                FileItem(
                    file = bigFile.file,
                    name = bigFile.name,
                    isDir = false,
                    size = bigFile.sizeBytes,
                    lastModified = bigFile.lastModified,
                )
            ),
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                bigFile.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                bigFile.file.parent ?: "/",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Format.size(bigFile.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LargeFolderRow(
    usage: FolderUsage,
    rootPath: String?,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(usage.dir.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                relativePath(usage.dir, rootPath) + " · ${usage.fileCount} file(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Format.size(usage.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Folder location relative to the scanned root, e.g. "DCIM/Camera". */
private fun relativePath(dir: File, rootPath: String?): String {
    val abs = dir.absolutePath
    if (rootPath == null || !abs.startsWith("$rootPath/")) return abs
    return abs.removePrefix("$rootPath/")
}

/** Maps an analyzer category onto the shared filter icons; "Other" gets the generic one. */
private fun filterModeOf(category: FileCategory?): FilterMode = when (category) {
    FileCategory.IMAGES -> FilterMode.IMAGES
    FileCategory.VIDEOS -> FilterMode.VIDEOS
    FileCategory.AUDIO -> FilterMode.AUDIO
    FileCategory.DOCS -> FilterMode.DOCS
    FileCategory.ARCHIVES -> FilterMode.ARCHIVES
    FileCategory.APKS -> FilterMode.APKS
    null -> FilterMode.ALL
}
