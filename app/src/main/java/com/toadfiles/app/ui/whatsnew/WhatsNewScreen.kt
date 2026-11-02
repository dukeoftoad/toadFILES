package com.toadfiles.app.ui.whatsnew

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.toadfiles.app.BuildConfig

/**
 * The full in-app changelog. Content is parsed from changelog.md, an asset generated at
 * build time from the repository's CHANGELOG.md (Gradle `syncChangelog`) — there are no
 * release notes hardcoded in the app, so this page and GitHub Releases share one source
 * of truth.
 *
 * Layout: the installed version's release is the hero card (always expanded, per-section
 * icons); older releases sit below as expandable cards. Honest fallbacks: a build with
 * no matching entry says so and lists everything that IS in the bundled changelog; an
 * unreadable asset says that too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // null = the bundled asset is still being read; empty = nothing usable in it.
    val releases by produceState<List<ChangelogRelease>?>(null) {
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
                title = { Text("What's new") },
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

            val parsed = releases
            when {
                parsed == null -> Unit // asset read in flight; resolves on the next frame or two

                parsed.orEmpty().isEmpty() -> Text(
                    "No changelog was bundled with this build — see CHANGELOG.md in the " +
                        "toadFILES repository for release notes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val all = parsed.orEmpty()
                    val installed = BuildConfig.VERSION_NAME
                    val hero = all.firstOrNull {
                        it.version == installed || it.version.removePrefix("v") == installed
                    }
                    if (hero != null) {
                        ReleaseCard(hero)
                        val older = all - hero
                        if (older.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            Text("Older releases", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            older.forEach { release -> CollapsibleReleaseCard(release) }
                        }
                    } else {
                        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "v$installed has no entry in the bundled changelog.",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Release notes for this build were not tagged yet — the " +
                                        "entries below are the ones this changelog ships.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        all.forEach { release -> CollapsibleReleaseCard(release) }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The installed version's release: header plus every section, always expanded. */
@Composable
private fun ReleaseCard(release: ChangelogRelease) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            ReleaseHeader(release)
            release.sections.forEach { section -> SectionBlock(section) }
        }
    }
}

/** An older release: collapsed to its header until tapped. */
@Composable
private fun CollapsibleReleaseCard(release: ChangelogRelease) {
    var expanded by rememberSaveable(release.version) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("v${release.version}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        release.date ?: "undated",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                release.sections.forEach { section -> SectionBlock(section) }
            }
        }
    }
}

@Composable
private fun ReleaseHeader(release: ChangelogRelease) {
    Text("v${release.version}", style = MaterialTheme.typography.titleMedium)
    Text(
        release.date ?: "undated",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** One `### Section`: its icon + title, then the bullet items under it. */
@Composable
private fun SectionBlock(section: ChangelogSection) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    ) {
        Icon(
            sectionIcon(section.title),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(section.title, style = MaterialTheme.typography.titleSmall)
    }
    section.items.forEach { item -> ChangelogBullet(item) }
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

/** Icon per Keep-a-Changelog section; unknown sections get a neutral notes glyph. */
private fun sectionIcon(title: String): ImageVector = when (title.lowercase()) {
    "added" -> Icons.Outlined.NewReleases
    "changed" -> Icons.Outlined.Autorenew
    "fixed" -> Icons.Outlined.Build
    "removed" -> Icons.Outlined.Delete
    "deprecated" -> Icons.Outlined.Warning
    "security" -> Icons.Outlined.Security
    else -> Icons.Outlined.Notes
}
