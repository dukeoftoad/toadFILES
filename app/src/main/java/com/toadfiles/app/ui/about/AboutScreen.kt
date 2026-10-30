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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.BuildConfig
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.util.Intents

/**
 * Full About page. Replaces the tiny About card in Settings: it carries everything a
 * privacy-conscious user expects from a file manager — version/build, real storage
 * numbers, per-category counts, a user-initiated update check against GitHub Releases,
 * the OSS credits, and an in-app changelog.
 *
 * Layout: a vertically scrolling column of cards. One concern per card so the page stays
 * navigable on small screens. The update check is strictly opt-in (no background polling,
 * no auto-check at startup); a downloaded update APK lands in the app-private download
 * dir and is handed to the system installer as a DownloadManager content URI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    vm: AboutViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                        "toadFILES goes online in exactly one case: when you tap \"Check for " +
                            "updates\" below, it talks to GitHub Releases to fetch the newest " +
                            "version — and downloads the update APK only if you ask for it. It " +
                            "never uploads files, favorites, settings or analytics, and never " +
                            "contacts anything else in the background.",
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

            // ---- Updates (strictly user-initiated check against GitHub Releases) ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Updates", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "toadFILES checks GitHub Releases for a newer version only when you tap " +
                            "the button below — never in the background, and nothing about this " +
                            "device is sent or stored.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    VersionRow("Installed", BuildConfig.VERSION_NAME)
                    Spacer(Modifier.height(10.dp))
                    UpdatePanel(
                        update = state.update,
                        onCheck = vm::checkForUpdates,
                        onDownloadInstall = { vm.downloadAndInstall(context) },
                        onDismiss = vm::clearUpdateState,
                    )
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

private const val NOTES_PREVIEW_LINES = 4

/**
 * The update panel proper — every [UpdateState] rendered in the card's existing M3
 * style (spinner rows like the storage card, label/body text like the version rows).
 * All actions are explicit taps: check, download & install, open GitHub, dismiss.
 */
@Composable
private fun UpdatePanel(
    update: UpdateState,
    onCheck: () -> Unit,
    onDownloadInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (update) {
        is UpdateState.Idle -> {
            Text(
                "Checking contacts api.github.com once and shows the result here. No account, " +
                    "no telemetry, no auto-check on startup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCheck) { Text("Check for updates") }
        }

        is UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking GitHub Releases…", style = MaterialTheme.typography.labelMedium)
            }
        }

        is UpdateState.UpToDate -> {
            Text(
                "You're up to date (${update.currentVersion}).",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCheck) { Text("Check again") }
        }

        is UpdateState.Available -> UpdateAvailable(
            available = update,
            onDownloadInstall = onDownloadInstall,
            onDismiss = onDismiss,
        )

        is UpdateState.Error -> {
            Text(
                update.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCheck) { Text("Retry") }
        }
    }
}

@Composable
private fun UpdateAvailable(
    available: UpdateState.Available,
    onDownloadInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    Text(
        "toadFILES ${available.version} is available",
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(8.dp))
    VersionRow("New version", available.version)
    VersionRow("Size", Format.size(available.sizeBytes))
    available.notes?.let { notes ->
        Spacer(Modifier.height(8.dp))
        val lines = notes.split('\n')
        val truncated = !notesExpanded && lines.size > NOTES_PREVIEW_LINES
        Text(
            if (truncated) lines.take(NOTES_PREVIEW_LINES).joinToString("\n") + "\n…" else notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lines.size > NOTES_PREVIEW_LINES) {
            TextButton(onClick = { notesExpanded = !notesExpanded }) {
                Text(if (notesExpanded) "Show less" else "Show full notes")
            }
        }
    }
    if (available.needsInstallPermission) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Allow \"Install unknown apps\" for toadFILES in the Android screen that just " +
                "opened, then tap Install again — the APK is already downloaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (available.downloadInProgress) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Downloading… progress is shown in the notification shade.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onDownloadInstall, enabled = !available.downloadInProgress) {
            Text(if (available.downloadedUri != null) "Install downloaded APK" else "Download & install")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { Intents.openUrl(context, available.htmlUrl) }) {
            Text("View on GitHub")
        }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onDismiss) { Text("Dismiss") }
}

/**
 * Hand-maintained changelog. Kept inside the app (not pulled from a remote feed) so it works
 * offline and respects the no-internet promise. Append a new pair at the top when shipping.
 */
private val ChangelogEntries: List<Pair<String, List<String>>> = listOf(
    "v1.5.0" to listOf(
        "Real Android share: the system share sheet hands files to the app you pick (single or batch), replacing the old local-copy share.",
        "Open files with the standard \"Just once / Always\" chooser so you can pin a default app per file type.",
        "APK install fixed: toadFILES now walks you to the exact \"Install unknown apps\" toggle when Android requires it.",
        "Categories redesign: back from a category returns to the grid, photos and videos open as a thumbnail grid, long-press opens the action sheet, and multi-select batch-shares or deletes to trash.",
        "Recent is fully actionable: open, share, show in folder, delete to trash, details — plus multi-select and thumbnails.",
        "Home redesigned: hero storage card, quick-access icon grid, polished favorites and recent feeds.",
        "Hidden-files eye toggle right in the Browse toolbar; clearing a search restores the folder listing.",
        "Permanent trash deletions (single item and empty-all) now ask for confirmation.",
        "Storage page gains a media-by-type breakdown and trash usage; trashed files are hidden from Recent and Categories.",
    ),
    "v1.4.0" to listOf(
        "Merged Recent into Home as a smart-recent list with a \"See all\" hop to the full view.",
        "Categories is back as its own tab — an organized grid with per-type icons, item counts and sizes.",
        "Long-press a file opens a Mi-Files-style action sheet (open, share, rename, copy, move, delete, details, zip, favorite, copy-path, open-with).",
        "Share is now local-only: copies into an app-private 'shared' folder, nothing leaves the device.",
        "Pick your accent color (toad, blue, purple, green, orange, red, pink) when dynamic color is off.",
        "About is a real page now: storage stats, per-category counts, OSS credits, changelog, clear-shared-copies.",
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
