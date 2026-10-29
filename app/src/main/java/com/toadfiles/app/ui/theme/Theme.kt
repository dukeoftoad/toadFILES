package com.toadfiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadfiles.app.data.ThemeMode

// Original toad palette: damp moss greens with a warm mud accent.
private val MossGreen = Color(0xFF4E7A46)
private val MossGreenDark = Color(0xFFA5C89A)
private val MudAmber = Color(0xFF8A6D3B)
private val MudAmberDark = Color(0xFFD8BC84)

private val LightColors = lightColorScheme(
    primary = MossGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EBC8),
    onPrimaryContainer = Color(0xFF0B2009),
    secondary = Color(0xFF55624C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E7CB),
    onSecondaryContainer = Color(0xFF131F0E),
    tertiary = MudAmber,
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

private val DarkColors = darkColorScheme(
    primary = MossGreenDark,
    onPrimary = Color(0xFF14310E),
    primaryContainer = Color(0xFF2C4927),
    onPrimaryContainer = Color(0xFFBCE2AE),
    secondary = Color(0xFFBCCBB0),
    onSecondary = Color(0xFF273421),
    secondaryContainer = Color(0xFF3D4A36),
    onSecondaryContainer = Color(0xFFD8E7CB),
    tertiary = MudAmberDark,
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

@Composable
fun ToadTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
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
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ToadTypography,
        shapes = ToadShapes,
        content = content,
    )
}
