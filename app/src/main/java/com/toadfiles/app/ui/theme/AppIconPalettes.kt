package com.toadfiles.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.toadfiles.app.R
import com.toadfiles.app.domain.AppIconColor
import com.toadfiles.app.domain.AppIconDesign

/**
 * Preview values for the in-app app-icon picker (Appearance screen): the pastel
 * background tints the launcher aliases use, and the foreground drawable of each
 * icon design.
 *
 * [background] mirrors res/values/icon_colors.xml (`icon_bg_*`) exactly — a mismatch
 * would preview a different tint than the launcher actually shows. [foregroundRes]
 * maps each design to its vector; CLASSIC reuses the original v1.0 foreground, the
 * other five have dedicated `ic_launcher_fg_*` drawables.
 */
object AppIconPalettes {

    /** Background tint of the (design, color) launcher tile. Must match icon_colors.xml. */
    fun background(color: AppIconColor): Color = when (color) {
        AppIconColor.GREEN -> Color(0xFFEAF4E4)
        AppIconColor.TEAL -> Color(0xFFD8EFEB)
        AppIconColor.BLUE -> Color(0xFFDCE9F9)
        AppIconColor.LILAC -> Color(0xFFE7E0F6)
        AppIconColor.AMBER -> Color(0xFFFAEED2)
        AppIconColor.ROSE -> Color(0xFFF9E0E6)
    }

    /** Foreground drawable of the design. CLASSIC reuses the foreground shipped in v1.0. */
    fun foregroundRes(design: AppIconDesign): Int = when (design) {
        AppIconDesign.CLASSIC -> R.drawable.ic_launcher_foreground
        AppIconDesign.FACE -> R.drawable.ic_launcher_fg_face
        AppIconDesign.DOC -> R.drawable.ic_launcher_fg_doc
        AppIconDesign.CROWN -> R.drawable.ic_launcher_fg_crown
        AppIconDesign.PIXEL -> R.drawable.ic_launcher_fg_pixel
        AppIconDesign.SD -> R.drawable.ic_launcher_fg_sd
    }
}
