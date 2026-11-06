package com.toadfiles.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.toadfiles.app.R
import com.toadfiles.app.domain.AppFont

/*
 * The v1.11 font picker's families. The TOAD family (Baloo 2 renamed per the OFL —
 * Modified Version, Reserved Font Name) ships in src/main/res/font and exists in both
 * editions; the other four families (Manrope, Inter, Space Grotesk, JetBrains Mono —
 * static instancing of Google Fonts variable TTFs, subsetted to Latin and de-hinted
 * by scripts/make_toadfiles_fonts.py, sources + OFL texts in docs/licenses) are
 * full-edition resources: their declarations live in src/full/…/BundledFonts.kt and
 * lite resolves them to the system typeface via [bundledFontFamilyOrNull]
 * (docs/LITE_EDITION_PLAN.md §3 phase 1 — fonts are 41% of the APK).
 */

/** The unique toad font — Baloo 2 (SIL OFL), renamed and rebranded for toadFILES. */
val ToadFamily = FontFamily(
    Font(R.font.toad_regular, FontWeight.Normal),
    Font(R.font.toad_medium, FontWeight.Medium),
    Font(R.font.toad_semibold, FontWeight.SemiBold),
    Font(R.font.toad_bold, FontWeight.Bold),
    Font(R.font.toad_extrabold, FontWeight.ExtraBold),
)

/** The [FontFamily] a picker entry renders with; [AppFont.SYSTEM] is the platform default.
 *  A full-only family requested in lite resolves to the platform default as well —
 *  the persisted enum name survives, the letterforms degrade gracefully. */
fun fontFamilyFor(font: AppFont): FontFamily = when (font) {
    AppFont.SYSTEM -> FontFamily.Default
    AppFont.TOAD -> ToadFamily
    else -> bundledFontFamilyOrNull(font) ?: FontFamily.Default
}

/**
 * The v1.11 font picker's entry point: the tuned scale above with the chosen
 * family swapped into every role (weights keep their per-role tuning — the
 * family only changes the letterforms). [AppFont.DEFAULT] reproduces the
 * shipped Manrope look exactly.
 */
fun toadTypographyFor(font: AppFont): Typography =
    withFamily(ToadTypography, fontFamilyFor(font))

/** Swaps [family] into every M3 role of [t], keeping sizes, weights and tracking. */
private fun withFamily(t: Typography, family: FontFamily): Typography = Typography(
    displayLarge = t.displayLarge.copy(fontFamily = family),
    displayMedium = t.displayMedium.copy(fontFamily = family),
    displaySmall = t.displaySmall.copy(fontFamily = family),
    headlineLarge = t.headlineLarge.copy(fontFamily = family),
    headlineMedium = t.headlineMedium.copy(fontFamily = family),
    headlineSmall = t.headlineSmall.copy(fontFamily = family),
    titleLarge = t.titleLarge.copy(fontFamily = family),
    titleMedium = t.titleMedium.copy(fontFamily = family),
    titleSmall = t.titleSmall.copy(fontFamily = family),
    bodyLarge = t.bodyLarge.copy(fontFamily = family),
    bodyMedium = t.bodyMedium.copy(fontFamily = family),
    bodySmall = t.bodySmall.copy(fontFamily = family),
    labelLarge = t.labelLarge.copy(fontFamily = family),
    labelMedium = t.labelMedium.copy(fontFamily = family),
    labelSmall = t.labelSmall.copy(fontFamily = family),
)

/**
 * The redesign's type scale. Every M3 role is defined so no style silently falls
 * back to the platform default mid-screen. Headline/display styles use ExtraBold
 * with tight tracking; body stays Regular for readability at small sizes.
 *
 * The per-role families here are INERT placeholders: [toadTypographyFor] swaps the
 * user's picked family into every role via [withFamily] before anything renders.
 * The literal is the platform default so the base scale compiles in both editions.
 */
val ToadTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold,
        fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
)
