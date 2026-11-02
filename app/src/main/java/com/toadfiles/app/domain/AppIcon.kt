package com.toadfiles.app.domain

/**
 * User-selectable launcher icon foreground (the drawing). Combined with an [AppIconColor]
 * it names one of the 36 `<activity-alias>` launcher entries declared in AndroidManifest;
 * [AppIconSwitcher] enables exactly that alias so the home screen shows this icon.
 *
 * [CLASSIC] reuses the foreground shipped since v1.0 and stays the default, so existing
 * users see no visual regression when they upgrade.
 *
 * Persisted by name in DataStore; [com.toadfiles.app.data.SettingsRepository] falls back
 * to [CLASSIC] for unknown values, so adding or removing designs later never breaks a
 * user's preferences.
 */
enum class AppIconDesign(val label: String) {
    CLASSIC("Classic"),
    FACE("Toad face"),
    DOC("Document"),
    CROWN("Crowned"),
    PIXEL("Pixel"),
    SD("SD card"),
}

/**
 * User-selectable launcher icon background tint. The six pastel tints match the
 * `icon_bg_*` colors in res/values/icon_colors.xml and are combined with an
 * [AppIconDesign] to pick the launcher activity-alias (see [AppIconSwitcher]).
 *
 * [GREEN] is the tint of the icon shipped with v1.8.2 and stays the default.
 *
 * Persisted by name in DataStore; [com.toadfiles.app.data.SettingsRepository] falls back
 * to [GREEN] for unknown values.
 */
enum class AppIconColor(val label: String) {
    GREEN("Green"),
    TEAL("Teal"),
    BLUE("Blue"),
    LILAC("Lilac"),
    AMBER("Amber"),
    ROSE("Rose"),
}
