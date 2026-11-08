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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.BuildConfig
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.domain.IS_LITE
import com.toadfiles.app.ui.whatsnew.Changelog
import com.toadfiles.app.ui.whatsnew.ChangelogRelease
import com.toadfiles.app.util.Intents

/**
 * Full About page: identity, version/build and Android compatibility straight from
 * BuildConfig, what the app can do (implemented features only), why each manifest
 * permission exists, an accurate privacy statement, the bounded self-update flow,
 * a changelog preview, project/source links, and the OSS credits.
 *
 * Layout: a vertically scrolling column of cards — one concern per card so the page
 * stays navigable on small screens.
 *
 * NETWORK FACTS (identical in every claim on this page): the only endpoint toadFILES
 * ever contacts is the repo's releases/latest on api.github.com — once per app open
 * (silent, failure-invisible; see StartupUpdateCheck) and again whenever the user taps
 * "Check for updates". The update APK is fetched by the system DownloadManager only
 * after an explicit tap, then handed to the system installer. No telemetry, no ads,
 * no accounts; nothing about the device's files ever leaves it.
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
                    // From the build config, so these rows can never drift from the
                    // minSdk/targetSdk the APK was actually compiled with.
                    VersionRow("Min Android", "API ${BuildConfig.MIN_SDK_VERSION}")
                    VersionRow("Target Android", "API ${BuildConfig.TARGET_SDK_VERSION}")
                    VersionRow("Package", BuildConfig.APPLICATION_ID)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // Full edition's privacy story names its one network use;
                        // lite has none at all (no update checker, no INTERNET
                        // permission — docs/LITE_EDITION_PLAN.md §3 phase 2).
                        if (IS_LITE) {
                            "toadFILES Lite works entirely on this device: every file " +
                                "operation, favorite and setting stays local. It has no " +
                                "network access at all — the Lite edition ships without the " +
                                "update checker, so it never contacts anything. No " +
                                "telemetry, no ads, no accounts — nothing about your files " +
                                "ever leaves the device."
                        } else {
                            "toadFILES works entirely on this device: every file operation, " +
                                "favorite and setting stays local. The only network use is the " +
                                "optional update check — one silent request to api.github.com per " +
                                "app open, repeated only if you tap \"Check for updates\" below. An " +
                                "update APK is downloaded only after you tap \"Download & install\". " +
                                "No telemetry, no ads, no accounts — nothing about your files ever " +
                                "leaves the device."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Updates (GitHub Releases check: once per app open + user-initiated) ----
            // v1.8.1: the "Storage on this device" and "Files on this device" data
            // cards were removed from About — that live storage/usage information lives
            // on the Home storage card and the Storage page, where it belongs.
            // Lite hides the whole card (plan §3 phase 2): there is no checker, no
            // permission to run one, and updates arrive by sideloading instead.
            if (!IS_LITE) {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Updates", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "toadFILES contacts GitHub Releases once per app open (silently, in " +
                                "the background) and whenever you tap the button below. Nothing " +
                                "about this device is sent, and nothing downloads without a tap.",
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
            }

            Spacer(Modifier.height(16.dp))

            // ---- Inline changelog (parsed from the bundled CHANGELOG.md asset) ----
            // Lite ships no changelog asset and no What's New route (plan §3 phase 2);
            // the syncChangelog task only fills src/full/assets.
            if (!IS_LITE) {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("What's new", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        val latest = changelog?.firstOrNull()
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
            }

            Spacer(Modifier.height(16.dp))

            // ---- Capabilities (implemented only — nothing aspirational) ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Capabilities.forEach { line -> ChangelogBullet(line) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Permissions (every manifest permission, with its reason) ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Permissions.forEach { (name, reason) -> PermissionRow(name, reason) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Source & project ----
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Source & project", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "toadFILES is free software under GPL-3.0: you can use, study, " +
                            "change and share it. The full license ships as the LICENSE file " +
                            "in the repository.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { Intents.openUrl(context, REPO_URL) }) {
                        Text("Source code · github.com/dukeoftoad/toadFILES")
                    }
                    TextButton(onClick = { Intents.openUrl(context, RELEASES_URL) }) {
                        Text("Releases and tagged changelogs on GitHub")
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
                        "Build details and licenses for everything above are in the " +
                            "repository's dependency files.",
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
internal fun UpdatePanel(
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
internal fun UpdateAvailable(
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
        Spacer(Modifier.height(10.dp))
        // v2.6.0: real in-app progress. Determinate when the release's total size is
        // known (GitHub always sends Content-Length), indeterminate otherwise — the
        // bar never lies. The byte counters come from the system DownloadManager's
        // own watch loop; the notification shade keeps mirroring the same job.
        val fraction = available.downloadProgress
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .testTag(UPDATE_PROGRESS_BAR)
                    .semantics { contentDescription = DOWNLOAD_PROGRESS_DESC },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Downloading… ${(fraction * 100).toInt()}%" +
                    " — also visible in the notification shade.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UPDATE_PROGRESS_TEXT),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .testTag(UPDATE_PROGRESS_BAR)
                    .semantics { contentDescription = DOWNLOAD_PROGRESS_DESC },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Downloading… — also visible in the notification shade.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UPDATE_PROGRESS_TEXT),
            )
        }
    }
    if (available.downloadError != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            available.downloadError, // smart-cast: immutable same-module val, null-checked above
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(UPDATE_DOWNLOAD_ERROR),
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val buttonLabel = when {
            available.downloadedUri != null -> "Install downloaded APK"
            available.downloadError != null -> "Retry download" // the failed attempt kept everything else
            else -> "Download & install"
        }
        Button(
            onClick = onDownloadInstall,
            enabled = !available.downloadInProgress,
            modifier = Modifier.testTag(UPDATE_RETRY_BUTTON),
        ) {
            Text(buttonLabel)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { Intents.openUrl(context, available.htmlUrl) }) {
            Text("View on GitHub")
        }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onDismiss) { Text("Dismiss") }
}

internal const val UPDATE_PROGRESS_BAR = "update_progress_bar"
internal const val UPDATE_PROGRESS_TEXT = "update_progress_text"
internal const val UPDATE_DOWNLOAD_ERROR = "update_download_error"
internal const val UPDATE_RETRY_BUTTON = "update_retry_button"
private const val DOWNLOAD_PROGRESS_DESC = "Update download progress"

/** Every feature the app actually ships (verified against the code and CHANGELOG.md). */
private val Capabilities: List<String> = listOf(
    "Browse, open, rename, copy, move and delete files on internal storage, SD cards and OTG drives.",
    "Create and extract ZIP and 7z archives, including AES-256 password-protected 7z and split (.001, .002…) volumes.",
    "30-day trash with restore and undo — deletions land in toadFILES' trash, with batch restore and permanent-delete confirmations.",
    "Media browsing: photo/video thumbnails, category pages, recent files and a storage analyzer.",
    "Search by name — optionally inside small text files — plus favorites and quick-access pins that survive renames and moves.",
    "Share and open files with any app, and install APKs you keep in storage.",
    "Theming: Material You dynamic color, seven accent palettes, and six launcher icon designs with six background tints.",
)

/** Every manifest permission with a plain-language reason; kept in manifest order. */
private val Permissions: List<Pair<String, String>> = listOf(
    "INTERNET" to
        "Contacts api.github.com for the optional update check only — no other network use.",
    "All files access (MANAGE_EXTERNAL_STORAGE)" to
        "A file manager must read and change the files you act on. Nothing is uploaded, ever.",
    "Install unknown apps (REQUEST_INSTALL_PACKAGES)" to
        "Hands the update APK — or an APK you tapped — to the system installer. Android still " +
            "asks you to confirm every install.",
)

private const val REPO_URL = "https://github.com/dukeoftoad/toadFILES"
private const val RELEASES_URL = "$REPO_URL/releases"

@Composable
private fun PermissionRow(name: String, reason: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Open-source libraries the release APK ships — mirrors app/build.gradle.kts implementation deps. */
private val OssCredits: List<Pair<String, String>> = listOf(
    "AndroidX Core KTX" to "Apache-2.0",
    "AndroidX Lifecycle" to "Apache-2.0",
    "AndroidX Activity Compose" to "Apache-2.0",
    "AndroidX Compose (UI, Material 3, icons)" to "Apache-2.0",
    "AndroidX Navigation Compose" to "Apache-2.0",
    "AndroidX DataStore" to "Apache-2.0",
    "Coil 3 (images + video thumbs)" to "Apache-2.0",
    "Kotlinx Coroutines" to "Apache-2.0",
    "Apache Commons Compress (7z)" to "Apache-2.0",
    "XZ for Java (7z LZMA)" to "Public Domain",
)

