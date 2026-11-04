package com.toadfiles.app.domain

/**
 * User-selectable app-wide visual identity (Appearance tab). One entry per full
 * look, not just an accent swap:
 *
 *  - [DARK_FOREST]  the redesigned toad look — damp moss greens on near-black with
 *    amber highlights; the default for new installs and for users who hadn't
 *    customized their theme.
 *  - [MATERIAL_YOU] wallpaper-derived dynamic color.
 *  - [MATERIAL3]    Google's reference M3 baseline palette.
 *  - [SLATE_NEON]   graphite on OLED black with one electric-green accent.
 *  - [CLASSIC]      the original moss & mud palette incl. its seven accent colors.
 *
 * Schemes live in `ui.theme.ToadThemes`; this enum stays in `domain` beside the
 * other user-selectable knobs ([AccentColor], [NavStyle], …) so persistence never
 * depends on the UI layer.
 *
 * Persisted by name in DataStore; [SettingsRepository] falls back to [DEFAULT] for
 * unknown values, so renaming entries later can never break a user's startup.
 */
enum class AppTheme(val label: String, val description: String) {
    DARK_FOREST(
        "Dark forest",
        "Moss on near-black with amber highlights — the new toad look",
    ),
    MATERIAL_YOU(
        "Material You",
        "Colors pulled from your wallpaper, like the rest of the system",
    ),
    MATERIAL3(
        "Material 3",
        "Google's reference palette — Material as designed",
    ),
    SLATE_NEON(
        "Slate + Neon",
        "Graphite on OLED black with a single electric green",
    ),
    CLASSIC(
        "Classic toad",
        "The original moss & mud palette, with the accent-color picker",
    ),
    ;

    companion object {
        /** The redesign's default look. */
        val DEFAULT = DARK_FOREST
    }
}
