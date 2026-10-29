package com.toadfiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.domain.AccentColor

/**
 * App theme. Two layers of color choice:
 *
 * 1. When the user enables Material You dynamic color on Android 12+, the wallpaper-derived
 *    scheme wins regardless of accent. This mirrors how Pixel/Oxygen apps behave and avoids
 *    clashing with the rest of the system.
 * 2. Otherwise, the user's chosen [AccentColor] picks a hand-tuned [ColorScheme] from
 *    [AccentPalettes]. The default is [AccentColor.TOAD] so upgraders keep their familiar
 *    moss/mud look from v1.0–v1.3.
 *
 * Typography and shapes are shared by every accent so the visual rhythm doesn't shift.
 */
@Composable
fun ToadTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    accentColor: AccentColor = AccentColor.TOAD,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> AccentPalettes.schemeFor(accentColor, dark = darkTheme)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ToadTypography,
        shapes = ToadShapes,
        content = content,
    )
}

private val ToadTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

private val ToadShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
