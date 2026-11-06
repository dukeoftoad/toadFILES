package com.toadfiles.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import com.toadfiles.app.domain.AppFont

/**
 * Lite edition (docs/LITE_EDITION_PLAN.md §3 phase 1): only the Toad mascot font
 * ships (src/main/res/font) beside the platform typeface — the four full-only
 * families are 1.4 MB of the APK and resolve to the system font here. The
 * Appearance tab lists SYSTEM and TOAD only, and a persisted full-edition pick
 * (e.g. the MANROPE default carried over in a backup) degrades gracefully.
 */
internal fun bundledFontFamilyOrNull(font: AppFont): FontFamily? = null
