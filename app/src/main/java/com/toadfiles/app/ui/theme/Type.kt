package com.toadfiles.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.toadfiles.app.R
import com.toadfiles.app.domain.AppFont

/**
 * Bundled Manrope (SIL OFL) — a rounded, friendly geometric sans that carries the
 * toadFILES brand: soft like the mascot, neutral enough for dense file listings.
 * Five static weights ship in res/font; the M3 scale below is tuned for them
 * (Manrope runs slightly wide, so display/title styles tighten the tracking and
 * body styles keep generous line heights for long file names).
 */
val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/*
 * The v1.11 font picker's other families. All are static instancing of Google
 * Fonts variable TTFs, subsetted to Latin and de-hinted by
 * scripts/make_toadfiles_fonts.py (sources + OFL texts: docs/licenses).
 *
 *  - Inter: the neutral screen workhorse; optical size pinned to 14 (text).
 *  - Space Grotesk: quirky techy sans; its weight axis tops out at Bold, so
 *    display styles asking for ExtraBold resolve to Bold via best-match.
 *  - JetBrains Mono: monospace, same width for every glyph.
 *  - Toad: Baloo 2 renamed per the OFL (Modified Version, Reserved Font Name) —
 *    chunky and bouncy, the mascot as letterforms.
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

val SpaceGroteskFamily = FontFamily(
    Font(R.font.spacegrotesk_regular, FontWeight.Normal),
    Font(R.font.spacegrotesk_medium, FontWeight.Medium),
    Font(R.font.spacegrotesk_semibold, FontWeight.SemiBold),
    Font(R.font.spacegrotesk_bold, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrainsmono_bold, FontWeight.Bold),
    Font(R.font.jetbrainsmono_extrabold, FontWeight.ExtraBold),
)

/** The unique toad font — Baloo 2 (SIL OFL), renamed and rebranded for toadFILES. */
val ToadFamily = FontFamily(
    Font(R.font.toad_regular, FontWeight.Normal),
    Font(R.font.toad_medium, FontWeight.Medium),
    Font(R.font.toad_semibold, FontWeight.SemiBold),
    Font(R.font.toad_bold, FontWeight.Bold),
    Font(R.font.toad_extrabold, FontWeight.ExtraBold),
)

/** The [FontFamily] a picker entry renders with; [AppFont.SYSTEM] is the platform default. */
fun fontFamilyFor(font: AppFont): FontFamily = when (font) {
    AppFont.SYSTEM -> FontFamily.Default
    AppFont.MANROPE -> ManropeFamily
    AppFont.TOAD -> ToadFamily
    AppFont.INTER -> InterFamily
    AppFont.SPACE_GROTESK -> SpaceGroteskFamily
    AppFont.JETBRAINS_MONO -> JetBrainsMonoFamily
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
 */
val ToadTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = ManropeFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
)
