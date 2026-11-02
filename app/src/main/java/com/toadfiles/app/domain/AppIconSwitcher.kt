package com.toadfiles.app.domain

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Applies a launcher-icon choice by flipping the enabled state of the 36
 * `<activity-alias>` entries in AndroidManifest (design x color). Exactly one alias is
 * left enabled at a time, and the home screen then shows that alias's icon.
 *
 * Implementations must never throw: [apply] runs on the app-startup path and on every
 * Appearance-screen change, and a failure should just leave the previous icon in place.
 */
interface AppIconSwitcher {
    /** Enable the alias for (design, color) and disable the other 35. Must not throw. */
    fun apply(design: AppIconDesign, color: AppIconColor)
}

/**
 * Production switcher backed by [PackageManager.setComponentEnabledSetting].
 *
 * The enabled alias is set FIRST, then the other 35 are disabled: at no point is the
 * app left without a launcher entry, which on some launchers would remove it from the
 * home screen and break pinned shortcuts. [PackageManager.DONT_KILL_APP] keeps the
 * running process alive across the switch.
 *
 * The whole body is wrapped in [runCatching]: this runs at app startup, and a
 * PackageManager failure must never take the app down.
 */
class PackageManagerAppIconSwitcher(private val context: Context) : AppIconSwitcher {

    override fun apply(design: AppIconDesign, color: AppIconColor) {
        runCatching {
            val pm = context.packageManager
            for ((component, state) in plan(design, color)) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Log.w(TAG, "could not switch launcher alias to $design/$color", it) }
    }

    /**
     * The exact sequence of component changes [apply] performs: the target alias
     * FIRST (enabled), then the other 35 (disabled) — at no point is the app left
     * without a launcher entry. Pure and unit-testable; [apply] only adds the
     * PackageManager call (Robolectric cannot observe alias component state).
     */
    fun plan(design: AppIconDesign, color: AppIconColor): List<Pair<ComponentName, Int>> {
        val target = componentNameFor(design, color)
        val disable = AppIconDesign.entries.asSequence()
            .flatMap { d -> AppIconColor.entries.asSequence().map { c -> d to c } }
            .filter { it.first != design || it.second != color }
            .map { componentNameFor(it.first, it.second) to PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
            .toList()
        return listOf(target to PackageManager.COMPONENT_ENABLED_STATE_ENABLED) + disable
    }

    /**
     * The alias of (design, color) in this app's package, e.g. (CLASSIC, GREEN) →
     * com.toadfiles.app.LauncherClassicGreen. Delegates to the pure companion
     * mapping [componentNameFor] with the Context's package name.
     */
    fun componentNameFor(design: AppIconDesign, color: AppIconColor): ComponentName =
        componentNameFor(context.packageName, design, color)

    companion object {
        private const val TAG = "AppIcon"

        /**
         * Pure, Context-free form of the mapping rule:
         * `<pkg>.Launcher<Design><Color>` with both enum names capitalized —
         * e.g. (FACE, TEAL) in com.toadfiles.app → com.toadfiles.app.LauncherFaceTeal.
         * Must stay in sync with the 36 activity-alias names in AndroidManifest.xml.
         */
        @JvmStatic
        fun componentNameFor(pkg: String, design: AppIconDesign, color: AppIconColor): ComponentName =
            ComponentName(pkg, "$pkg.Launcher${capitalized(design.name)}${capitalized(color.name)}")

        /** CLASSIC → "Classic", GREEN → "Green" (SD → "Sd") — matches the manifest suffixes. */
        private fun capitalized(name: String): String =
            name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
