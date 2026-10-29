package com.toadfiles.app.domain

/**
 * User-selectable accent color. Used by [com.toadfiles.app.ui.theme.ToadTheme] when dynamic
 * (wallpaper-based) color is OFF, and by the launcher icon's tinted background.
 *
 * [TOAD] is the original moss-green palette shipped with v1.0–v1.3 and stays the default,
 * so existing users see no visual regression when they upgrade. The other six entries
 * match the Material You accent set so users can pick a familiar hue.
 *
 * Persisted by name in DataStore; [SettingsRepository] falls back to [TOAD] for unknown
 * values, so adding or removing entries later never breaks a user's preferences.
 */
enum class AccentColor {
    TOAD,
    BLUE,
    PURPLE,
    GREEN,
    ORANGE,
    RED,
    PINK,
    ;

    /** Stable on-disk identifier (== name) and human label for the picker UI. */
    val label: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
