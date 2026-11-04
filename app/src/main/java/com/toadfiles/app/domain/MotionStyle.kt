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
 *
 * The animation specs live in `ui.theme.ToadMotion`; this enum stays in `domain`
 * beside the other user-selectable knobs.
 */
enum class MotionStyle(val label: String, val description: String) {
    SPRINGY("Springy toad", "Springs with a soft bounce — the toad hops"),
    CALM("Calm & precise", "Quick fades and slides, no bounce"),
    CINEMATIC("Cinematic", "Longer arcs, screens scale in and out"),
    ;

    companion object {
        val DEFAULT = SPRINGY
    }
}
