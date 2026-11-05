package com.toadfiles.app.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Tab re-tap → back to the tab's top level (v2.1.0, owner request).
 *
 * Switching tabs keeps each tab's state (an agreed feature: leave Categories in a
 * drill-in, come back later, you land where you were). The SECOND tap on the
 * already-active tab means "take me back up" — the standard Android re-tap contract
 * (YouTube, Play Store): Browse jumps back to the folder its tab started in,
 * Categories closes the drill-in onto the grid, Recent drops the type filter,
 * Trash clears the selection. Flat screens (Home, Settings, About) have nothing
 * to reset and ignore the signal.
 *
 * A tiny SharedFlow bus, because the tap handlers live in [ToadNavHost] while the
 * state that needs resetting lives in per-tab ViewModels the nav host never sees.
 * Screens subscribe with the route they own; events carry no payload, fire at most
 * in bursts of one tap, and are deliberately not buffered across subscribers —
 * `extraBufferCapacity = 4` only absorbs a re-tap landing before the collector is
 * up, and a missed event degrades to "the tab just stays where it is".
 */
object TabReselect {

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** The user re-tapped the tab whose base route is [route]. */
    fun reselect(route: String) {
        _events.tryEmit(route)
    }
}
