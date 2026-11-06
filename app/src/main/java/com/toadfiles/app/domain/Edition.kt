package com.toadfiles.app.domain

import com.toadfiles.app.BuildConfig

/**
 * The edition gate (docs/LITE_EDITION_PLAN.md): true in the `lite` product flavor,
 * false in `full`.
 *
 * [BuildConfig.EDITION] is a compile-time `String` constant injected per flavor, so
 * every `if (IS_LITE)` branch is constant-folded: the Kotlin compiler treats the
 * comparison of two constants as constant, and R8 strips the dead side of the branch
 * from the release build — lite's dex genuinely loses the stripped screens' code
 * rather than hiding it. Never replace this with a runtime setting; the whole point
 * is that Lite is a build, not a toggle.
 */
val IS_LITE: Boolean
    get() = BuildConfig.EDITION == "lite"
