package com.toadfiles.app.ui.settings

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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import com.toadfiles.app.domain.AppFont
import com.toadfiles.app.domain.AppTheme
import com.toadfiles.app.domain.BackKeyAction
import com.toadfiles.app.domain.MotionStyle
import com.toadfiles.app.domain.NavStyle
import com.toadfiles.app.ui.theme.AccentPalettes
import com.toadfiles.app.ui.theme.AppIconPalettes
import com.toadfiles.app.ui.theme.ToadThemes
import com.toadfiles.app.ui.theme.fontFamilyFor

/**
 * Dedicated Appearance subpage. Since the v1.11 redesign it offers:
 *
 *  - **Theme** — one full visual identity per entry ([AppTheme]): the Dark forest
 *    redesign, Material You (wallpaper colors), Google's Material 3 baseline,
 *    Slate + Neon and the Classic palette (which keeps the seven accent colors),
 *    plus the light / dark / follow-system selector;
 *  - **Font** — [AppFont]: the device's system typeface, the bundled Manrope, the
 *    unique chunky Toad font, Inter, Space Grotesk or JetBrains Mono — every entry
 *    previews in its own letterforms;
 *  - **Motion** — [MotionStyle]: Springy toad, Calm & precise or Cinematic, applied
 *    app-wide through `LocalToadMotion`;
 *  - **Navigation** and **App icon** as before (same SettingsViewModel setters,
 *    same DataStore keys, same `appearance_scroll` test seam).
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
        // these avoids a double status-bar pad.
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
                .padding(horizontal = 16.dp)
                // Test seam: the ONLY vertical scroller on this screen. UI tests scroll
                // to the Navigation card through this tag — an unqualified scroll target
                // can latch onto the horizontal accent LazyRow and hang (CI, Nov 2026).
                .testTag("appearance_scroll"),
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            // Light / dark / system selector.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { vm.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // One card per visual identity.
            AppTheme.entries.forEach { theme ->
                ThemeCard(
                    theme = theme,
                    selected = settings.appTheme == theme,
                    onClick = { vm.setAppTheme(theme) },
                )
                Spacer(Modifier.height(8.dp))
            }

            // The accent-color picker belongs to the Classic identity only.
            if (settings.appTheme == AppTheme.CLASSIC) {
                Spacer(Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Accent Color", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Toad is the original moss & mud palette.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(AccentColor.entries) { accent ->
                                AccentSwatch(
                                    accent = accent,
                                    selected = settings.accentColor == accent,
                                    enabled = true,
                                    onClick = { vm.setAccent(accent) },
                                )
                            }
                        }
                    }
                }
            }
            // ---- Font ----
            // The typeface picker: every entry previews in its own family (the name
            // row and the "Aa" chip), so the card shows exactly what picking it does
            // to the whole app — no guessing from a dropdown label.
            Spacer(Modifier.height(32.dp))

            Text("Font", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    AppFont.entries.forEachIndexed { index, font ->
                        FontRow(
                            font = font,
                            selected = settings.appFont == font,
                            onClick = { vm.setAppFont(font) },
                        )
                        if (index != AppFont.entries.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))

            Text("Motion", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    MotionStyle.entries.forEachIndexed { index, style ->
                        SelectableRow(
                            title = style.label,
                            subtitle = style.description,
                            selected = settings.motionStyle == style,
                            onClick = { vm.setMotionStyle(style) },
                        )
                        if (index != MotionStyle.entries.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))

            Text("Navigation", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    ChoiceRow(
                        title = "Back Key Action",
                        subtitle = "What the system back key does while browsing folders",
                        choices = BackKeyAction.entries,
                        selected = settings.backKeyAction,
                        label = ::backKeyLabel,
                        onSelect = { vm.setBackKeyAction(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ChoiceRow(
                        title = "Navigation Style",
                        subtitle = "Bottom bar tabs or a side menu for moving between screens",
                        choices = NavStyle.entries,
                        selected = settings.navStyle,
                        label = ::navStyleLabel,
                        onSelect = { vm.setNavStyle(it) },
                    )
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

/** One visual-identity card: palette preview + name + description + selected check. */
@Composable
private fun ThemeCard(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val swatches = ToadThemes.swatches(theme)
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini preview: the theme's background with its accent dots.
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(swatches[0]),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                swatches.drop(1).forEach { color ->
                    Box(
                        Modifier
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.label, style = MaterialTheme.typography.titleSmall)
            Text(
                theme.description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One font entry: an "Aa" chip plus the entry's own name rendered in that
 * family — the honest preview — with a description and the selection radio.
 */
@Composable
private fun FontRow(
    font: AppFont,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val family = fontFamilyFor(font)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Aa",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = family),
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                font.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
            )
            Text(
                font.description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** Simple selectable row with title + description and a trailing check. */
@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
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

/**
 * Label + explanatory subtitle + current value + dropdown, opened by tapping the row.
 * The Appearance-tab twin of the ChoiceRow used on the main Settings list, restyled to
 * this page's card idiom (no tonal icon chip).
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            label(selected),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 3.dp,
        ) {
            choices.forEach { choice ->
                val isSelected = choice == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            label(choice),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onSelect(choice); open = false },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun backKeyLabel(action: BackKeyAction) = when (action) {
    BackKeyAction.PARENT -> "Back To Parent Folder"
    BackKeyAction.EXIT -> "Exit App"
}

private fun navStyleLabel(style: NavStyle) = when (style) {
    NavStyle.BOTTOM_BAR -> "Bottom Bar"
    NavStyle.SIDE_MENU -> "Side Menu"
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
