package com.toadfiles.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.BuildConfig
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.Format

/**
 * Full About page. Replaces the tiny About card in Settings: it carries everything a
 * privacy-conscious user expects from a file manager — version/build, real storage
 * numbers, per-category counts, the OSS credits, an in-app changelog, the diagnostics
 * toggle, and a "clear locally-shared copies" action.
 *
 * Layout: a vertically scrolling column of cards. One concern per card so the page stays
 * navigable on small screens; the diagnostics toggle is in its own card next to the
 * privacy promise it controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    vm: AboutViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- Identity + version block ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("toadFILES", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A lightweight, fast, privacy-first Android file manager.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    VersionRow("Version", BuildConfig.VERSION_NAME)
                    VersionRow("Build", BuildConfig.VERSION_CODE.toString())
                    VersionRow("Min Android", "8.0 (API 26)")
                    VersionRow("Target Android", "API 36+")
                    VersionRow("Package", BuildConfig.APPLICATION_ID)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "toadFILES has no INTERNET permission by design. It never uploads files, " +
                            "favorites, settings or analytics. Share within toadFILES duplicates " +
                            "files into an app-private folder on this device — nothing leaves it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Storage usage ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Storage on this device", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val stats = state.storage
                    if (stats == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Reading storage…", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        LinearProgressIndicator(
                            progress = { stats.usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${Format.size(stats.usedBytes)} used of ${Format.size(stats.totalBytes)} · " +
                                "${Format.size(stats.freeBytes)} free",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- File counts by type ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Files on this device", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    if (state.categoryCounts.isEmpty() && state.loading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Counting…", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        val maxBytes = state.categoryCounts.maxOfOrNull { it.totalBytes } ?: 0L
                        state.categoryCounts.forEach { c ->
                            CategoryStatRow(
                                name = c.category.label,
                                count = c.count,
                                bytes = c.totalBytes,
                                fillFraction = if (maxBytes > 0L) {
                                    (c.totalBytes.toFloat() / maxBytes).coerceIn(0.05f, 1f)
                                } else 0f,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Locally shared folder ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Locally shared copies", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sharing in toadFILES duplicates files into an app-private folder. " +
                            "They stay there until you clear them, and never leave the device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${state.sharedCount} file(s) · ${Format.size(state.sharedBytes)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { vm.clearShared() },
                        enabled = state.sharedCount > 0,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear all shared copies")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Diagnostics toggle (privacy promise) ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Off by default. Even when on, toadFILES has no internet permission so it " +
                            "cannot actually send anything — this toggle exists to record your " +
                            "preference for any future diagnostic-data feature.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.diagnosticsEnabled) "Allowed (no network)" else "Off",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Switch(
                            checked = state.diagnosticsEnabled,
                            onCheckedChange = { vm.setDiagnostics(it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Inline changelog ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("What's new", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ChangelogEntries.forEach { (version, lines) ->
                        Text(
                            version,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        lines.forEach { line -> ChangelogBullet(line) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- OSS credits ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Open-source credits", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "toadFILES is built on the work of many open-source projects.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OssCredits.forEach { (name, license) ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                license,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "toadFILES itself is GPL-3.0. See the source at the repository you cloned it from.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CategoryStatRow(name: String, count: Int, bytes: Long, fillFraction: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "$count item(s) · ${Format.size(bytes)}",
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
private fun ChangelogBullet(text: String) {
    // 4-arg padding form: mixing `start` with `vertical` would be ambiguous (different
    // padding overloads) and is rejected by the Kotlin compiler, so we name top/bottom.
    Row(Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Hand-maintained changelog. Kept inside the app (not pulled from a remote feed) so it works
 * offline and respects the no-internet promise. Append a new pair at the top when shipping.
 */
private val ChangelogEntries: List<Pair<String, List<String>>> = listOf(
    "v1.4.0" to listOf(
        "Merged Recent into Home as a smart-recent list with a \"See all\" hop to the full view.",
        "Categories is back as its own tab — an organized grid with per-type icons, item counts and sizes.",
        "Long-press a file opens a Mi-Files-style action sheet (open, share, rename, copy, move, delete, details, zip, favorite, copy-path, open-with).",
        "Share is now local-only: copies into an app-private 'shared' folder, nothing leaves the device.",
        "Pick your accent color (toad, blue, purple, green, orange, red, pink) when dynamic color is off.",
        "About is a real page now: storage stats, per-category counts, OSS credits, changelog, diagnostics toggle, clear-shared-copies.",
        "More tests across all of the above.",
    ),
    "v1.3.0" to listOf(
        "Favorites that follow renames/moves and prune themselves when the target disappears.",
        "In-folder content search with configurable result limit.",
        "Trash chip on Browse for surfacing recently-deleted items.",
    ),
    "v1.2.0" to listOf(
        "30-day trash with undo; SD-card and OTG volume support; thumbnails + grid view on Browse.",
    ),
    "v1.1.0" to listOf(
        "APK install handoff, sort-order picker, item counts on Browse.",
    ),
    "v1.0.0" to listOf(
        "Initial release: privacy-first, no internet permission, fast browse.",
    ),
)

/** Open-source libraries toadFILES ships. Update when adding a new dependency. */
private val OssCredits: List<Pair<String, String>> = listOf(
    "AndroidX Core KTX" to "Apache-2.0",
    "AndroidX Lifecycle" to "Apache-2.0",
    "AndroidX Activity Compose" to "Apache-2.0",
    "AndroidX Compose" to "Apache-2.0",
    "AndroidX Navigation Compose" to "Apache-2.0",
    "AndroidX DataStore" to "Apache-2.0",
    "Coil3" to "Apache-2.0",
    "Kotlinx Coroutines" to "Apache-2.0",
    "Robolectric" to "MIT",
)
