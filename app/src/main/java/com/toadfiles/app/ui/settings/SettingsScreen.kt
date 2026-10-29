package com.toadfiles.app.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.BuildConfig
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.domain.SortMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Text("Appearance", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setTheme(mode) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.themeMode == mode, onClick = { vm.setTheme(mode) })
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "Follow system"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dynamic color", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                    "Use wallpaper-based Material You colors"
                                else "Requires Android 12+",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { vm.setDynamic(it) },
                            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        )
                    }
                }
            }

            Text("Browsing", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { sortMenuOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Default sort", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                settings.sortMode.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Change",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { vm.setSort(m); sortMenuOpen = false },
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show hidden files", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Names starting with a dot",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.showHidden, onCheckedChange = { vm.setShowHidden(it) })
                    }
                }
            }

            if (settings.favorites.isNotEmpty()) {
                Text("Favorites", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        settings.favorites.sorted().forEach { path ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = { vm.removeFavorite(path) }) {
                                    Text("Remove", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Text("About", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("toadFILES ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A lightweight, fast, privacy-first file manager. " +
                            "toadFILES works entirely on this device: it requests no internet permission, " +
                            "contains no analytics, no ads and no cloud sync. Favorites and preferences " +
                            "are stored locally with DataStore. Licensed under GPL-3.0.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Storage root: ${android.os.Environment.getExternalStorageDirectory().path}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
