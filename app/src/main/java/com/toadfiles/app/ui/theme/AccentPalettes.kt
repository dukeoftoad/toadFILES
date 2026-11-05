package com.toadfiles.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.toadfiles.app.domain.AccentColor

/**
 * Per-accent Material 3 color schemes. Each accent defines a (light, dark) pair of
 * `ColorScheme`s that share a single seed color, built the same way as Material Theme
 * Builder's tonal palettes. Only the colors that visibly change with accent (primary,
 * secondary, tertiary, primaryContainer, surfaceVariant, outline) are overridden; the
 * rest stay at the Material 3 defaults so error states etc. don't break.
 *
 * `AccentColor.TOAD` exactly reproduces the v1.0–v1.3 moss/mud palette defined inline in
 * [Theme.kt] so existing users see no visual regression after upgrading.
 *
 * Audit A7 (v2.1.1): the redesigned UI reads the container roles (`surfaceContainer*`,
 * `background`) — TransferBar's drag handle, Settings/Appearance cards. None of the
 * accent schemes used to define them, so every scheme except the two TOAD ones fell
 * back to the M3 *baseline* palette for those roles: purple-tinted grays that visibly
 * clashed with warm CLASSIC surfaces in dark mode. Every scheme now derives `background`
 * and the five `surfaceContainer*` tones from its own surface/surfaceVariant pair (same
 * hue family, monotone in tone), so container surfaces stay in the accent's voice.
 */
object AccentPalettes {

    /** Linear interpolation a→b in sRGB; good enough for tone steps between two
     *  already-harmonized scheme colors. */
    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )

    /**
     * A7: fills the container-role holes every scheme had. Tones follow the M3
     * neutral ladder — light: lowest(100) low(96) container(94) high(92) highest(90)
     * with the surface near 98; dark: lowest(4) low(10) container(12) high(17)
     * highest(22) with the surface near 6–10 — approximated by mixing the scheme's
     * own surface toward its surfaceVariant (same accent hue) or, for the dark
     * lowest tone, toward black.
     */
    private fun ColorScheme.withDerivedSurfaces(dark: Boolean): ColorScheme {
        val s = surface
        val v = surfaceVariant
        return copy(
            background = s,
            onBackground = onSurface,
            surfaceContainerLowest = if (dark) mix(s, Color.Black, 0.30f) else mix(s, v, 0.15f),
            surfaceContainerLow = mix(s, v, if (dark) 0.14f else 0.32f),
            surfaceContainer = mix(s, v, if (dark) 0.22f else 0.45f),
            surfaceContainerHigh = mix(s, v, if (dark) 0.36f else 0.60f),
            surfaceContainerHighest = mix(s, v, if (dark) 0.52f else 0.80f),
        )
    }

    /** Original toad palette: damp moss greens with a warm mud accent. */
    private val ToadLight = lightColorScheme(
        primary = Color(0xFF4E7A46),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD0EBC8),
        onPrimaryContainer = Color(0xFF0B2009),
        secondary = Color(0xFF55624C),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD8E7CB),
        onSecondaryContainer = Color(0xFF131F0E),
        tertiary = Color(0xFF8A6D3B),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDDB1),
        onTertiaryContainer = Color(0xFF2C1700),
        background = Color(0xFFF6FAF4),
        onBackground = Color(0xFF181D17),
        surface = Color(0xFFF6FAF4),
        onSurface = Color(0xFF181D17),
        surfaceVariant = Color(0xFFDFE4D8),
        onSurfaceVariant = Color(0xFF43483F),
        outline = Color(0xFF73796E),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )
    private val ToadDark = darkColorScheme(
        primary = Color(0xFFA5C89A),
        onPrimary = Color(0xFF14310E),
        primaryContainer = Color(0xFF2C4927),
        onPrimaryContainer = Color(0xFFBCE2AE),
        secondary = Color(0xFFBCCBB0),
        onSecondary = Color(0xFF273421),
        secondaryContainer = Color(0xFF3D4A36),
        onSecondaryContainer = Color(0xFFD8E7CB),
        tertiary = Color(0xFFD8BC84),
        onTertiary = Color(0xFF2C1700),
        tertiaryContainer = Color(0xFF69421C),
        onTertiaryContainer = Color(0xFFFFDDB1),
        background = Color(0xFF10140F),
        onBackground = Color(0xFFDFE4D8),
        surface = Color(0xFF10140F),
        onSurface = Color(0xFFDFE4D8),
        surfaceVariant = Color(0xFF43483F),
        onSurfaceVariant = Color(0xFFC3C8BB),
        outline = Color(0xFF8D9286),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

    /** Light schemes for the six non-toad accents. */
    private val BlueLight = lightColorScheme(
        primary = Color(0xFF4364D9),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDBE1FF),
        onPrimaryContainer = Color(0xFF001A41),
        secondary = Color(0xFF575E71),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDBE2F9),
        onSecondaryContainer = Color(0xFF141B2C),
        tertiary = Color(0xFF705573),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFAD8FB),
        onTertiaryContainer = Color(0xFF27132A),
        surface = Color(0xFFF9F9FF),
        onSurface = Color(0xFF1A1B23),
        surfaceVariant = Color(0xFFE2E2EC),
        onSurfaceVariant = Color(0xFF45464F),
        outline = Color(0xFF75767F),
    )
    private val PurpleLight = lightColorScheme(
        primary = Color(0xFF7C4DFF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DDFF),
        onPrimaryContainer = Color(0xFF2A0057),
        secondary = Color(0xFF635B70),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE9DEF8),
        onSecondaryContainer = Color(0xFF1F192B),
        tertiary = Color(0xFF7E5260),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFD9E2),
        onTertiaryContainer = Color(0xFF31101D),
        surface = Color(0xFFFEF7FF),
        onSurface = Color(0xFF1D1A20),
        surfaceVariant = Color(0xFFE7E0EB),
        onSurfaceVariant = Color(0xFF49454E),
        outline = Color(0xFF7A757F),
    )
    private val GreenLight = lightColorScheme(
        primary = Color(0xFF2E7D32),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC8F0C8),
        onPrimaryContainer = Color(0xFF00210A),
        secondary = Color(0xFF52634F),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD5E8CD),
        onSecondaryContainer = Color(0xFF101F0F),
        tertiary = Color(0xFF386569),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFBCEBEF),
        onTertiaryContainer = Color(0xFF001F22),
        surface = Color(0xFFF7FBF2),
        onSurface = Color(0xFF181D17),
        surfaceVariant = Color(0xFFDEE4D8),
        onSurfaceVariant = Color(0xFF42483F),
        outline = Color(0xFF72796E),
    )
    private val OrangeLight = lightColorScheme(
        primary = Color(0xFF8E4A00),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDBC8),
        onPrimaryContainer = Color(0xFF2E1500),
        secondary = Color(0xFF76594A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDBC9),
        onSecondaryContainer = Color(0xFF2C180D),
        tertiary = Color(0xFF635F70),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE9E3F8),
        onTertiaryContainer = Color(0xFF1F1B2C),
        surface = Color(0xFFFFF8F4),
        onSurface = Color(0xFF221A14),
        surfaceVariant = Color(0xFFF4DED2),
        onSurfaceVariant = Color(0xFF52443A),
        outline = Color(0xFF847467),
    )
    private val RedLight = lightColorScheme(
        primary = Color(0xFFB3261E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD6),
        onPrimaryContainer = Color(0xFF410003),
        secondary = Color(0xFF775651),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDAD2),
        onSecondaryContainer = Color(0xFF2C1511),
        tertiary = Color(0xFF705C2E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFCDFA6),
        onTertiaryContainer = Color(0xFF251A00),
        surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF231916),
        surfaceVariant = Color(0xFFF5DDDA),
        onSurfaceVariant = Color(0xFF534340),
        outline = Color(0xFF85736F),
    )
    private val PinkLight = lightColorScheme(
        primary = Color(0xFFAD3064),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9E2),
        onPrimaryContainer = Color(0xFF3E001E),
        secondary = Color(0xFF745661),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFD9E2),
        onSecondaryContainer = Color(0xFF2B151C),
        tertiary = Color(0xFF7D5314),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDDB5),
        onTertiaryContainer = Color(0xFF2A1700),
        surface = Color(0xFFFFF8F7),
        onSurface = Color(0xFF22191C),
        surfaceVariant = Color(0xFFF3DDE1),
        onSurfaceVariant = Color(0xFF524347),
        outline = Color(0xFF847378),
    )

    /** Dark schemes for the six non-toad accents. */
    private val BlueDark = darkColorScheme(
        primary = Color(0xFFB4C5FF),
        onPrimary = Color(0xFF0F2C6E),
        primaryContainer = Color(0xFF29469E),
        onPrimaryContainer = Color(0xFFDBE1FF),
        secondary = Color(0xFFBFC6DC),
        onSecondary = Color(0xFF283041),
        secondaryContainer = Color(0xFF3F4759),
        onSecondaryContainer = Color(0xFFDBE2F9),
        tertiary = Color(0xFFDABFDC),
        onTertiary = Color(0xFF3D2840),
        tertiaryContainer = Color(0xFF553E57),
        onTertiaryContainer = Color(0xFFFAD8FB),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE2E2EC),
        surfaceVariant = Color(0xFF45464F),
        onSurfaceVariant = Color(0xFFC6C6D0),
        outline = Color(0xFF90909A),
    )
    private val PurpleDark = darkColorScheme(
        primary = Color(0xFFCFBCFF),
        onPrimary = Color(0xFF3A1C71),
        primaryContainer = Color(0xFF5235A8),
        onPrimaryContainer = Color(0xFFE9DDFF),
        secondary = Color(0xFFCDC2DB),
        onSecondary = Color(0xFF343143),
        secondaryContainer = Color(0xFF4B4759),
        onSecondaryContainer = Color(0xFFE9DEF8),
        tertiary = Color(0xFFEAB8C6),
        onTertiary = Color(0xFF4A2532),
        tertiaryContainer = Color(0xFF643B49),
        onTertiaryContainer = Color(0xFFFFD9E2),
        surface = Color(0xFF151218),
        onSurface = Color(0xFFE7E0EB),
        surfaceVariant = Color(0xFF49454E),
        onSurfaceVariant = Color(0xFFCAC4CF),
        outline = Color(0xFF948F99),
    )
    private val GreenDark = darkColorScheme(
        primary = Color(0xFF8FD48F),
        onPrimary = Color(0xFF003910),
        primaryContainer = Color(0xFF1B5B22),
        onPrimaryContainer = Color(0xFFAAEE9F),
        secondary = Color(0xFFBCCBAB),
        onSecondary = Color(0xFF273421),
        secondaryContainer = Color(0xFF3D4B36),
        onSecondaryContainer = Color(0xFFD5E8CD),
        tertiary = Color(0xFF9FCFD2),
        onTertiary = Color(0xFF003739),
        tertiaryContainer = Color(0xFF1E4D51),
        onTertiaryContainer = Color(0xFFBCEBEF),
        surface = Color(0xFF0F140D),
        onSurface = Color(0xFFDEE4D8),
        surfaceVariant = Color(0xFF42483F),
        onSurfaceVariant = Color(0xFFC2C8BC),
        outline = Color(0xFF8C9386),
    )
    private val OrangeDark = darkColorScheme(
        primary = Color(0xFFFFB684),
        onPrimary = Color(0xFF4E2400),
        primaryContainer = Color(0xFF6E3600),
        onPrimaryContainer = Color(0xFFFFDBC8),
        secondary = Color(0xFFE5BFA9),
        onSecondary = Color(0xFF432A1C),
        secondaryContainer = Color(0xFF5C402F),
        onSecondaryContainer = Color(0xFFFFDBC9),
        tertiary = Color(0xFFD2C2DD),
        onTertiary = Color(0xFF38314A),
        tertiaryContainer = Color(0xFF4F4762),
        onTertiaryContainer = Color(0xFFE9E3F8),
        surface = Color(0xFF1A1209),
        onSurface = Color(0xFFF4DED2),
        surfaceVariant = Color(0xFF52443A),
        onSurfaceVariant = Color(0xFFD7C3B5),
        outline = Color(0xFF9F8D80),
    )
    private val RedDark = darkColorScheme(
        primary = Color(0xFFFFB4A8),
        onPrimary = Color(0xFF690005),
        primaryContainer = Color(0xFF91131B),
        onPrimaryContainer = Color(0xFFFFDAD6),
        secondary = Color(0xFFE7BDB4),
        onSecondary = Color(0xFF442921),
        secondaryContainer = Color(0xFF5D3F36),
        onSecondaryContainer = Color(0xFFFFDAD2),
        tertiary = Color(0xFFE7C26D),
        onTertiary = Color(0xFF3F2D00),
        tertiaryContainer = Color(0xFF594319),
        onTertiaryContainer = Color(0xFFFCDFA6),
        surface = Color(0xFF1A0E0C),
        onSurface = Color(0xFFF5DDDA),
        surfaceVariant = Color(0xFF534340),
        onSurfaceVariant = Color(0xFFD8C2BE),
        outline = Color(0xFFA08C87),
    )
    private val PinkDark = darkColorScheme(
        primary = Color(0xFFFFB1C8),
        onPrimary = Color(0xFF660038),
        primaryContainer = Color(0xFF8E164F),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = Color(0xFFE5BDC8),
        onSecondary = Color(0xFF43292F),
        secondaryContainer = Color(0xFF5B3F46),
        onSecondaryContainer = Color(0xFFFFD9E2),
        tertiary = Color(0xFFF1BD73),
        onTertiary = Color(0xFF422C00),
        tertiaryContainer = Color(0xFF5F4217),
        onTertiaryContainer = Color(0xFFFFDDB5),
        surface = Color(0xFF1A0F12),
        onSurface = Color(0xFFF3DDE1),
        surfaceVariant = Color(0xFF524347),
        onSurfaceVariant = Color(0xFFD6C2C6),
        outline = Color(0xFF9F8C90),
    )

    fun schemeFor(accent: AccentColor, dark: Boolean): ColorScheme {
        val base = if (dark) {
            when (accent) {
                AccentColor.TOAD -> ToadDark
                AccentColor.BLUE -> BlueDark
                AccentColor.PURPLE -> PurpleDark
                AccentColor.GREEN -> GreenDark
                AccentColor.ORANGE -> OrangeDark
                AccentColor.RED -> RedDark
                AccentColor.PINK -> PinkDark
            }
        } else {
            when (accent) {
                AccentColor.TOAD -> ToadLight
                AccentColor.BLUE -> BlueLight
                AccentColor.PURPLE -> PurpleLight
                AccentColor.GREEN -> GreenLight
                AccentColor.ORANGE -> OrangeLight
                AccentColor.RED -> RedLight
                AccentColor.PINK -> PinkLight
            }
        }
        return base.withDerivedSurfaces(dark)
    }

    /** Solid representative swatch used by the Settings accent picker. */
    fun swatch(accent: AccentColor): Color = when (accent) {
        AccentColor.TOAD -> Color(0xFF4E7A46)
        AccentColor.BLUE -> Color(0xFF4364D9)
        AccentColor.PURPLE -> Color(0xFF7C4DFF)
        AccentColor.GREEN -> Color(0xFF2E7D32)
        AccentColor.ORANGE -> Color(0xFF8E4A00)
        AccentColor.RED -> Color(0xFFB3261E)
        AccentColor.PINK -> Color(0xFFAD3064)
    }
}
