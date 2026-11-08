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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.domain.AppFont
import com.toadfiles.app.domain.AppTheme
import com.toadfiles.app.domain.MotionStyle
import com.toadfiles.app.domain.TextScale

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
 * 5. [TextScale] — app-wide text-size multiplier (v2.4.0), applied through
 *    [LocalDensity]'s fontScale so every sp-based size — every Material component,
 *    every `Text(...)`, every bundled-font letterform — scales together with the
 *    device's own font setting. [TextScale.STANDARD] is the no-op.
 *
 * [performanceMode] (Settings, "Performance mode") is the one-knob overload switch:
 * it forces [MotionStyle.NONE] regardless of the Appearance choice and publishes
 * [LocalPerformanceMode] so screens can skip their own recurring costs (video
 * thumbnail decoding, decorative art) without per-feature settings.
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
    textScale: TextScale = TextScale.DEFAULT,
    performanceMode: Boolean = false,
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
    // Compose with the device's font scale: a 1.0 factor (TextScale.STANDARD) leaves
    // LocalDensity untouched, so the system accessibility setting always wins first;
    // a non-1.0 pick multiplies on top of it, app-wide, at the theme root. Density is
    // an interface, so the override rebuilds it with the constructor rather than copy().
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * textScale.factor,
    )
    CompositionLocalProvider(
        LocalToadMotion provides ToadMotion.forStyle(
            if (performanceMode) MotionStyle.NONE else motionStyle,
        ),
        LocalPerformanceMode provides performanceMode,
        LocalDensity provides scaledDensity,
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

/** True while the user's "Performance mode" is on: screens read this to skip
 *  recurring work the motion specs can't cover (video thumbnails, decorations). */
val LocalPerformanceMode = staticCompositionLocalOf { false }
