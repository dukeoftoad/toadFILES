package com.toadfiles.app.domain

/**
 * User-selectable motion personality (Appearance tab), applied everywhere through
 * `ui.theme.LocalToadMotion`.
 *
 *  - [SPRINGY]   the toad hops: springs with a visible bounce drive the FAB, the
 *    transfer bar and screen entrances. Playful but never slow. Default.
 *  - [CALM]      fast eased fades and slides, zero overshoot. A serious tool.
 *  - [CINEMATIC] longer arcs with scale: screens grow out of the backstack and
 *    shrink back into it.
 *  - [NONE]      no animations at all: every transition lands in one frame and the
 *    splash is skipped entirely. Zero animation work on the UI thread — the
 *    lightest choice for low-end or battery-starved devices (Performance mode
 *    in Settings forces this style app-wide).
 *
 * The animation specs live in `ui.theme.ToadMotion`; this enum stays in `domain`
 * beside the other user-selectable knobs.
 */
enum class MotionStyle(val label: String, val description: String) {
    SPRINGY("Springy toad", "Springs with a soft bounce — the toad hops"),
    CALM("Calm & precise", "Quick fades and slides, no bounce"),
    CINEMATIC("Cinematic", "Longer arcs, screens scale in and out"),
    NONE("None", "No animations — everything appears instantly"),
    ;

    companion object {
        val DEFAULT = SPRINGY
    }
}
