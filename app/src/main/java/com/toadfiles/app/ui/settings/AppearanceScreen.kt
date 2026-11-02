package com.toadfiles.app.ui.settings

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadfiles.app.ToadViewModelFactory
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.AppIconColor
import com.toadfiles.app.domain.AppIconDesign
import com.toadfiles.app.ui.theme.AccentPalettes
import com.toadfiles.app.ui.theme.AppIconPalettes

/**
 * Dedicated Appearance subpage (v1.7.3): theme mode, dynamic color and accent color
 * moved out of the main Settings list. The main Settings screen keeps a single
 * "Appearance" card that navigates here.
 *
 * Route "appearance" is registered in ToadNavHost; Settings navigates here via its
 * onOpenAppearance callback (v1.7.3 integration).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    vm: SettingsViewModel = viewModel(factory = ToadViewModelFactory),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.padding(bottom = bottomPadding),
        // Inset source of truth is the outer shell Scaffold in ToadNavHost; zeroing
        // these avoids a double status-bar pad (the v1.7.3 top "dead band").
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Appearance") },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "Open navigation menu") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    }
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
            Text("Theme", style = MaterialTheme.typography.titleMedium,
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
                                    ThemeMode.SYSTEM -> "Follow System"
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
                            Text("Dynamic Color", style = MaterialTheme.typography.bodyLarge)
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
                    // Accent picker — only meaningful when dynamic color is OFF. When
                    // dynamic color is on the picker is shown disabled so the user can see
                    // what's available once they turn Material You off; the swatch they
                    // pre-select here will then apply immediately.
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Accent Color", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Used when dynamic color is off. Toad is the original palette.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(AccentColor.entries) { accent ->
                            AccentSwatch(
                                accent = accent,
                                selected = settings.accentColor == accent,
                                enabled = !settings.dynamicColor ||
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                                onClick = { vm.setAccent(accent) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))

            Text("App icon", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Design", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The drawing inside the icon. Classic is the original.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(AppIconDesign.entries) { d ->
                            IconDesignTile(
                                design = d,
                                // Live preview: every design is rendered on the CURRENT
                                // background tint, so picking a color re-tints all tiles.
                                previewColor = settings.appIconColor,
                                selected = settings.appIconDesign == d,
                                onClick = { vm.setAppIconDesign(d) },
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Background", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(AppIconColor.entries) { c ->
                            IconColorSwatch(
                                color = c,
                                selected = settings.appIconColor == c,
                                onClick = { vm.setAppIconColor(c) },
                            )
                        }
                    }
                    Text(
                        "The launcher may take a moment to show the new icon.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AccentSwatch(
    accent: AccentColor,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = AccentPalettes.swatch(accent)
    Column(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else Color.Transparent,
            modifier = Modifier.size(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (enabled) color else color.copy(alpha = 0.4f)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            accent.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One launcher-icon design tile: the design's foreground on the CURRENT background tint. */
@Composable
private fun IconDesignTile(
    design: AppIconDesign,
    previewColor: AppIconColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppIconPalettes.background(previewColor))
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    else Modifier
                )
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(AppIconPalettes.foregroundRes(design)),
                contentDescription = design.label,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            design.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One background-tint swatch; mirrors [AccentSwatch]'s selection treatment. */
@Composable
private fun IconColorSwatch(
    color: AppIconColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else Color.Transparent,
            modifier = Modifier.size(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AppIconPalettes.background(color)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            color.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
