package com.toadfiles.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.toadfiles.app.R
import com.toadfiles.app.domain.AppFont

/*
 * The font picker's full-edition families (docs/LITE_EDITION_PLAN.md §3 phase 1):
 * these five static-instanced Google Fonts families ship ONLY in the full flavor —
 * they are 1.9 MB of the 4.59 MB APK, and lite falls back to the system typeface.
 * All are subsetted to Latin and de-hinted by scripts/make_toadfiles_fonts.py
 * (sources + OFL texts: docs/licenses).
 *
 *  - Manrope: rounded and friendly, the toadFILES brand family and the picker default.
 *  - Inter: the neutral screen workhorse; optical size pinned to 14 (text).
 *  - Space Grotesk: quirky techy sans; its weight axis tops out at Bold, so
 *    display styles asking for ExtraBold resolve to Bold via best-match.
 *  - JetBrains Mono: monospace, same width for every glyph.
 */
val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

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

/**
 * The [FontFamily] for the four full-only picker entries, or null when this flavor
 * doesn't bundle them (lite — the caller falls back to the system typeface).
 * [AppFont.SYSTEM] and [AppFont.TOAD] never route through here: Toad ships in
 * src/main and both editions can use it.
 */
internal fun bundledFontFamilyOrNull(font: AppFont): FontFamily? = when (font) {
    AppFont.MANROPE -> ManropeFamily
    AppFont.INTER -> InterFamily
    AppFont.SPACE_GROTESK -> SpaceGroteskFamily
    AppFont.JETBRAINS_MONO -> JetBrainsMonoFamily
    else -> null
}
