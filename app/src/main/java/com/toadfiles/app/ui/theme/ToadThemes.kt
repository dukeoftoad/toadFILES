package com.toadfiles.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.toadfiles.app.domain.AppTheme

/**
 * The five themes' color schemes. [AppTheme.MATERIAL_YOU] resolves to the platform
 * dynamic scheme at runtime and [AppTheme.CLASSIC] delegates to [AccentPalettes]; the
 * other three are hand-tuned (light, dark) pairs below.
 */
object ToadThemes {

    // -----------------------------------------------------------------------
    // Toadmark — shared ink surfaces with frog-lime, emerald and pond-teal accents.
    // -----------------------------------------------------------------------

    val DarkForestDark: ColorScheme = darkColorScheme(
        primary = Color(0xFFA3E635),
        onPrimary = Color(0xFF14210A),
        primaryContainer = Color(0xFF2E5312),
        onPrimaryContainer = Color(0xFFD9F99D),
        secondary = Color(0xFF34D399),
        onSecondary = Color(0xFF062A20),
        secondaryContainer = Color(0xFF18362A),
        onSecondaryContainer = Color(0xFFD1FAE5),
        tertiary = Color(0xFF2DD4BF),
        onTertiary = Color(0xFF003B35),
        tertiaryContainer = Color(0xFF11443D),
        onTertiaryContainer = Color(0xFFC9F2E7),
        background = Color(0xFF0B100F),
        onBackground = Color(0xFFF3F4F6),
        surface = Color(0xFF131A18),
        onSurface = Color(0xFFF3F4F6),
        surfaceVariant = Color(0xFF1A2320),
        onSurfaceVariant = Color(0xFFB8C7C0),
        outline = Color(0xFF53645B),
        outlineVariant = Color(0xFF334139),
        scrim = Color.Black,
        inverseSurface = Color(0xFFF3F4F6),
        inverseOnSurface = Color(0xFF0B100F),
        inversePrimary = Color(0xFF446E17),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        surfaceContainerLowest = Color(0xFF070B0A),
        surfaceContainerLow = Color(0xFF0F1614),
        surfaceContainer = Color(0xFF131A18),
        surfaceContainerHigh = Color(0xFF1A2320),
        surfaceContainerHighest = Color(0xFF212D29),
    )

    val DarkForestLight: ColorScheme = lightColorScheme(
        primary = Color(0xFF446E17),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3EFB4),
        onPrimaryContainer = Color(0xFF102000),
        secondary = Color(0xFF0F766E),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFC9F2E7),
        onSecondaryContainer = Color(0xFF003B35),
        tertiary = Color(0xFF8F5C00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE8B0),
        onTertiaryContainer = Color(0xFF2D1800),
        background = Color(0xFFF6F1E0),
        onBackground = Color(0xFF2B2B20),
        surface = Color(0xFFFEFAEC),
        onSurface = Color(0xFF2B2B20),
        surfaceVariant = Color(0xFFE9E2C0),
        onSurfaceVariant = Color(0xFF615E4F),
        outline = Color(0xFFA9A38B),
        outlineVariant = Color(0xFFD8D1B8),
        inverseSurface = Color(0xFF2B2B20),
        inverseOnSurface = Color(0xFFF6F1E0),
        inversePrimary = Color(0xFFA3E635),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFBF5E5),
        surfaceContainer = Color(0xFFF6F1E0),
        surfaceContainerHigh = Color(0xFFECE5CC),
        surfaceContainerHighest = Color(0xFFE3DCC2),
    )

    // -----------------------------------------------------------------------
    // Material 3 — Google's baseline reference palette, from the M3 spec.
    // -----------------------------------------------------------------------

    val Material3Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458),
        onSecondaryContainer = Color(0xFFE8DEF8),
        tertiary = Color(0xFFEFB8C8),
        onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48),
        onTertiaryContainer = Color(0xFFFFD8E4),
        background = Color(0xFF141218),
        onBackground = Color(0xFFE6E0E9),
        surface = Color(0xFF141218),
        onSurface = Color(0xFFE6E0E9),
        surfaceVariant = Color(0xFF49454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF49454F),
        inverseSurface = Color(0xFFE6E0E9),
        inverseOnSurface = Color(0xFF332D41),
        inversePrimary = Color(0xFF6750A4),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        surfaceContainerLowest = Color(0xFF0F0D13),
        surfaceContainerLow = Color(0xFF1D1B20),
        surfaceContainer = Color(0xFF211F26),
        surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerHighest = Color(0xFF36343B),
    )

    val Material3Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8DEF8),
        onSecondaryContainer = Color(0xFF1D192B),
        tertiary = Color(0xFF7D5260),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD8E4),
        onTertiaryContainer = Color(0xFF31111D),
        background = Color(0xFFFEF7FF),
        onBackground = Color(0xFF1D1B20),
        surface = Color(0xFFFEF7FF),
        onSurface = Color(0xFF1D1B20),
        surfaceVariant = Color(0xFFE7E0EC),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E),
        outlineVariant = Color(0xFFCAC4D0),
        inverseSurface = Color(0xFF322F35),
        inverseOnSurface = Color(0xFFF5EFF7),
        inversePrimary = Color(0xFFD0BCFF),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF3EDF7),
        surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerHighest = Color(0xFFE6E0E9),
    )

    // -----------------------------------------------------------------------
    // Slate + Neon — graphite surfaces, OLED-true blacks, exactly one electric
    // green and a cold cyan for tertiary contrast. The light pair is a cool
    // graphite paper with the same electric identity.
    // -----------------------------------------------------------------------

    val SlateNeonDark: ColorScheme = darkColorScheme(
        primary = Color(0xFF57F2A0),
        onPrimary = Color(0xFF00391E),
        primaryContainer = Color(0xFF005231),
        onPrimaryContainer = Color(0xFF7CFFBC),
        secondary = Color(0xFFB6C0BB),
        onSecondary = Color(0xFF222925),
        secondaryContainer = Color(0xFF38403C),
        onSecondaryContainer = Color(0xFFD2DCD6),
        tertiary = Color(0xFF82E3FF),
        onTertiary = Color(0xFF003441),
        tertiaryContainer = Color(0xFF004C5E),
        onTertiaryContainer = Color(0xFFB4EBFF),
        background = Color(0xFF0A0C0B),
        onBackground = Color(0xFFDDE5E0),
        surface = Color(0xFF0A0C0B),
        onSurface = Color(0xFFDDE5E0),
        surfaceVariant = Color(0xFF39423D),
        onSurfaceVariant = Color(0xFFB7C1BA),
        outline = Color(0xFF737D77),
        outlineVariant = Color(0xFF2A322D),
        inverseSurface = Color(0xFFDDE5E0),
        inverseOnSurface = Color(0xFF252B28),
        inversePrimary = Color(0xFF006D43),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        surfaceContainerLowest = Color(0xFF050605),
        surfaceContainerLow = Color(0xFF101312),
        surfaceContainer = Color(0xFF151918),
        surfaceContainerHigh = Color(0xFF1F2422),
        surfaceContainerHighest = Color(0xFF292F2C),
    )

    val SlateNeonLight: ColorScheme = lightColorScheme(
        primary = Color(0xFF006D43),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF89F8BC),
        onPrimaryContainer = Color(0xFF00210F),
        secondary = Color(0xFF4C635A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCEE9DC),
        onSecondaryContainer = Color(0xFF082018),
        tertiary = Color(0xFF00697F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB4EBFF),
        onTertiaryContainer = Color(0xFF001F28),
        background = Color(0xFFF4F6F3),
        onBackground = Color(0xFF171D1A),
        surface = Color(0xFFF4F6F3),
        onSurface = Color(0xFF171D1A),
        surfaceVariant = Color(0xFFDBE5DE),
        onSurfaceVariant = Color(0xFF404944),
        outline = Color(0xFF707973),
        outlineVariant = Color(0xFFBFC9C2),
        inverseSurface = Color(0xFF2B322E),
        inverseOnSurface = Color(0xFFECF2ED),
        inversePrimary = Color(0xFF6CFFB4),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEEF1EE),
        surfaceContainer = Color(0xFFE8EBE8),
        surfaceContainerHigh = Color(0xFFE2E5E2),
        surfaceContainerHighest = Color(0xFFDDDfDC),
    )

    /**
     * Resolve a theme to a concrete scheme. [MATERIAL_YOU] is handled by the
     * caller (it needs the Context); [CLASSIC] resolves through the accent
     * system so the seven accent colors keep working.
     */
    fun schemeFor(theme: AppTheme, dark: Boolean): ColorScheme? = when (theme) {
        AppTheme.DARK_FOREST -> if (dark) DarkForestDark else DarkForestLight
        AppTheme.MATERIAL3 -> if (dark) Material3Dark else Material3Light
        AppTheme.SLATE_NEON -> if (dark) SlateNeonDark else SlateNeonLight
        AppTheme.MATERIAL_YOU, AppTheme.CLASSIC -> null
    }

    /** A representative three-dot swatch for the theme picker cards. */
    fun swatches(theme: AppTheme): List<Color> = when (theme) {
        AppTheme.DARK_FOREST -> listOf(Color(0xFF0D120E), Color(0xFF9FE06B), Color(0xFFFFB855))
        AppTheme.MATERIAL_YOU -> listOf(Color(0xFF1C1B1F), Color(0xFFB69DF8), Color(0xFFFDACD0))
        AppTheme.MATERIAL3 -> listOf(Color(0xFF141218), Color(0xFFD0BCFF), Color(0xFFEFB8C8))
        AppTheme.SLATE_NEON -> listOf(Color(0xFF0A0C0B), Color(0xFF57F2A0), Color(0xFF82E3FF))
        AppTheme.CLASSIC -> listOf(Color(0xFF10140F), Color(0xFFA5C89A), Color(0xFFD8BC84))
    }
}
