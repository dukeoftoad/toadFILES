package com.toadfiles.app.ui.categories

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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
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
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.domain.Format
import com.toadfiles.app.ui.components.EmptyState
import com.toadfiles.app.util.Intents
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onShowInFolder: (String) -> Unit,
    vm: CategoriesViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
                title = { Text(state.selected?.label ?: "Categories") },
                navigationIcon = {
                    IconButton(onClick = { if (state.selected != null) vm.close() else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.selected == null) {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Outlined.Refresh, "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.selected == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (state.loading && state.summaries.isEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    LazyColumn(Modifier.padding(16.dp)) {
                        items(state.summaries, key = { it.category.name }) { s ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                shape = RoundedCornerShape(18.dp),
                                onClick = { vm.open(s.category) },
                            ) {
                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.category.label, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${s.count} item(s) · ${Format.size(s.totalBytes)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (state.entriesLoading) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { CircularProgressIndicator() }
            } else if (state.entries.isEmpty()) {
                EmptyState("No ${state.selected!!.label.lowercase()} found in MediaStore")
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    items(state.entries, key = { "${it.category.name}#${it.id}" }) { e ->
                        MediaRow(
                            e,
                            onClick = {
                                if (!Intents.openContent(context, e.uri, e.mime)) {
                                    // No handler installed; do nothing rather than pretend.
                                }
                            },
                            onShare = { Intents.shareContent(context, e.uri, e.mime) },
                            onShowInFolder = {
                                val parent = e.data?.let { File(it).parentFile }
                                if (parent != null && parent.isDirectory) {
                                    onShowInFolder(parent.absolutePath)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    e: MediaRepository.MediaEntry,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onShowInFolder: () -> Unit,
) {
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
                    modifier = Modifier.size(52.dp),
                )
            } else {
                Card(shape = RoundedCornerShape(10.dp)) {
                    Column(
                        Modifier.size(52.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            e.name.substringAfterLast('.', "?").uppercase().take(4),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${Format.size(e.size)} · ${Format.date(e.dateModified)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShowInFolder, enabled = e.data != null) {
                Icon(Icons.Outlined.FolderOpen, "Show in folder")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, "Share")
            }
        }
    }
}
