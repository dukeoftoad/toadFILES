package com.toadfiles.app.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toadfiles.app.data.GitHubRelease

/**
 * The subtle, dismissible "update available" card shown once on app open when the
 * silent startup check (v1.7.1) found a newer release. Non-intrusive by design:
 * a single quiet line above the app content, no dialog, no sound, nothing downloads
 * until the user acts. "Update" deep-links into the EXISTING flow — it opens About,
 * whose update panel arrives already seeded with this release (AboutViewModel), so
 * the familiar "Download & install" sheet is one tap away.
 */
@Composable
fun UpdateBanner(
    release: GitHubRelease?,
    onOpenUpdateFlow: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (release == null) return // nothing to say — stays fully invisible
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "toadFILES ${release.tagName} available",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Update to the latest release — downloaded only if you ask.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOpenUpdateFlow) { Text("Update") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss update banner")
            }
        }
    }
}
