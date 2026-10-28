package io.github.dukeoftoad.toadfiles.ui.recent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.dukeoftoad.toadfiles.ToadViewModelFactory
import io.github.dukeoftoad.toadfiles.data.MediaRepository
import io.github.dukeoftoad.toadfiles.domain.FileCategory
import io.github.dukeoftoad.toadfiles.domain.Format
import io.github.dukeoftoad.toadfiles.ui.components.EmptyState
import io.github.dukeoftoad.toadfiles.util.Intents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    vm: RecentViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
                title = { Text("Recent") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && state.entries.isEmpty() -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { CircularProgressIndicator() }

            state.entries.isEmpty() -> EmptyState(
                "No recent files reported by the system media library",
                Modifier.padding(padding),
            )

            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                    RecentRow(e) {
                        if (!Intents.openContent(context, e.uri, e.mime)) {
                            // Nothing installed can open this type; no fake feedback.
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(e: MediaRepository.MediaEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.category == FileCategory.IMAGES || e.category == FileCategory.VIDEOS) {
                AsyncImage(
                    model = e.uri,
                    contentDescription = e.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(52.dp),
                )
            } else {
                Card(shape = RoundedCornerShape(10.dp)) {
                    Row(
                        Modifier.size(52.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            e.name.substringAfterLast('.', "?").uppercase().take(4),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Text(
                e.name,
                maxLines = 1,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.size(e.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Format.date(e.dateModified),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
