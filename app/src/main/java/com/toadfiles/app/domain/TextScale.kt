package com.toadfiles.app.domain

/**
 * v2.4.0 text-size picker (Appearance tab). Multiplies every sp-based size app-wide
 * through [com.toadfiles.app.ui.theme.ToadTheme]'s `LocalDensity` override, so it
 * composes with the device's own font scale — a user on the system's "Large" who also
 * picks app "Large" gets both factors. [STANDARD] is the no-op (factor 1.0): the size
 * the app was designed for, unchanged.
 *
 * Discrete steps instead of a slider: the Appearance tab is a radio-card surface
 * (Theme, Font, Motion), and steps match that pattern exactly — and a finite set is
 * impossible to mis-tune into an unreadable state. The knob lives beside the Font card
 * and is available in **both** editions (Lite keeps it): text size is an accessibility
 * choice, not a feature the Lite diet strips.
 *
 * The factor is applied at the theme root, never inside individual composables, so
 * every Material component, every `Text(...)` and every bundled-font letterform scales
 * together — there is no second place to wire it.
 */
enum class TextScale(val label: String, val description: String, val factor: Float) {
    SMALL("Small", "Slightly smaller text — fits more on screen", 0.85f),
    STANDARD("Default", "The size the app was designed for", 1.0f),
    LARGE("Large", "Bigger text for comfortable reading", 1.15f),
    EXTRA_LARGE("Extra large", "Largest text — best for low vision", 1.3f),
    ;

    companion object {
        /** The shipped look: a 1.0 factor that leaves the system font scale untouched. */
        val DEFAULT = STANDARD
    }
}
