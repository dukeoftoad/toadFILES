package com.toadfiles.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.toadfiles.app.ui.whatsnew.Changelog
import com.toadfiles.app.ui.whatsnew.ChangelogRelease
import com.toadfiles.app.util.Intents

/**
 * Full About page. Replaces the tiny About card in Settings: it carries everything a
 * privacy-conscious user expects from a file manager — version/build, real storage
 * numbers, per-category counts, a user-initiated update check against GitHub Releases,
 * the OSS credits, and an in-app changelog.
 *
 * Layout: a vertically scrolling column of cards. One concern per card so the page stays
 * navigable on small screens. Updates: a silent, once-per-launch check contacts
 * api.github.com (no periodic polling, no telemetry); a downloaded update APK lands in the
 * app-private download dir and is handed to the system installer as a DownloadManager
 * content URI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenWhatsNew: () -> Unit = {},
    vm: AboutViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Release notes parsed from the changelog.md asset generated at build time from the
    // repository's CHANGELOG.md (Gradle syncChangelog); null while the read/parse runs.
    val changelog by produceState<List<ChangelogRelease>?>(null) {
        value = Changelog.fromAssets(context)
    }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost; zeroing
        // these avoids a double status-bar pad (the v1.7.3 top "dead band").
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
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
                    VersionRow("Min Android", "14 (API 34)")
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

            // ---- Updates (strictly user-initiated check against GitHub Releases) ----
            // v1.8.1: the "Storage on this device" and "Files on this device" data
            // cards were removed from About — that live storage/usage information lives
            // on the Home storage card and the Storage page, where it belongs.
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

            // ---- Inline changelog (parsed from the bundled CHANGELOG.md asset) ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("What's new", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val latest = changelog.firstOrNull()
                    if (latest != null) {
                        Text(
                            "v${latest.version}" + (latest.date?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        latest.allItems.take(LATEST_CHANGELOG_ITEMS).forEach { line ->
                            ChangelogBullet(line)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenWhatsNew) { Text("See all changes") }
                    } else {
                        Text(
                            "No changelog was bundled with this build — see CHANGELOG.md in " +
                                "the toadFILES repository.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

/** Bullets of the newest release shown on the About page before "See all changes". */
private const val LATEST_CHANGELOG_ITEMS = 4

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
                "Contacts api.github.com once per app open (silently, in the background) and " +
                    "shows the result here. No account, no telemetry, no periodic polling.",
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
