package com.toadfiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.AppFont
import com.toadfiles.app.domain.AppTheme
import com.toadfiles.app.domain.MotionStyle

/**
 * App theme. Four layers of choice, all set on the Appearance tab:
 *
 * 1. [AppTheme] — the visual identity. [AppTheme.MATERIAL_YOU] follows the wallpaper
 *    (dynamic color); [AppTheme.CLASSIC] keeps the original accent-color system; the
 *    other three are hand-tuned full palettes ([ToadThemes]).
 * 2. [ThemeMode] — light / dark / follow system, applied within every identity.
 * 3. [AppFont] — the typeface ([Type.kt]'s tuned scale, re-skinned per family;
 *    [AppFont.SYSTEM] follows the device font).
 * 4. [MotionStyle] — how the app moves, provided to every screen via
 *    [LocalToadMotion] so springs, durations and nav transitions follow the user's
 *    taste without per-screen hard-coding.
 *
 * Shapes are shared by every identity so the visual rhythm stays coherent.
 */
@Composable
fun ToadTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: AccentColor = AccentColor.TOAD,
    motionStyle: MotionStyle = MotionStyle.DEFAULT,
    appFont: AppFont = AppFont.DEFAULT,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when (appTheme) {
        AppTheme.MATERIAL_YOU ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                // Unreachable with minSdk 34, kept as a safe fallback.
                AccentPalettes.schemeFor(accentColor, dark = darkTheme)
            }
        AppTheme.CLASSIC -> AccentPalettes.schemeFor(accentColor, dark = darkTheme)
        else -> ToadThemes.schemeFor(appTheme, dark = darkTheme)
            ?: AccentPalettes.schemeFor(accentColor, dark = darkTheme)
    }
    CompositionLocalProvider(
        LocalToadMotion provides ToadMotion.forStyle(motionStyle),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = toadTypographyFor(appFont),
            shapes = ToadShapes,
            content = content,
        )
    }
}

/** Softer, rounder geometry for the redesign — bigger radii than the M3 defaults. */
private val ToadShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)
