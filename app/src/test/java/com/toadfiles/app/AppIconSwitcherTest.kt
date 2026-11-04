package com.toadfiles.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.domain.AppIconColor
import com.toadfiles.app.domain.AppIconDesign
import com.toadfiles.app.domain.PackageManagerAppIconSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PackageManagerAppIconSwitcher: the component-name rule must match the 42
 * activity-alias names in AndroidManifest exactly — otherwise the switcher would flip
 * the state of components that don't exist and the launcher icon would never change.
 * Manifest sync is verified against the raw manifest source (Robolectric's
 * PackageManager does not resolve activity-aliases), and the switch sequence via the
 * pure [PackageManagerAppIconSwitcher.plan].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIconSwitcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newSwitcher() = PackageManagerAppIconSwitcher(context)

    /** The app's manifest source — unit tests run with the module directory as CWD. */
    private val manifestText: String by lazy {
        listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.exists() }
            .readText()
    }

    @Test
    fun `componentNameFor maps CLASSIC GREEN to the shipped default alias`() {
        val cn = newSwitcher().componentNameFor(AppIconDesign.CLASSIC, AppIconColor.GREEN)
        assertEquals("com.toadfiles.app", cn.packageName)
        assertEquals("com.toadfiles.app.LauncherClassicGreen", cn.className)
    }

    @Test
    fun `componentNameFor maps a couple more combos by hand`() {
        // Hand-written expectations, not derived from the same formula as the code.
        assertEquals(
            "com.toadfiles.app.LauncherFaceTeal",
            newSwitcher().componentNameFor(AppIconDesign.FACE, AppIconColor.TEAL).className,
        )
        assertEquals(
            "com.toadfiles.app.LauncherSdRose",
            newSwitcher().componentNameFor(AppIconDesign.SD, AppIconColor.ROSE).className,
        )
        assertEquals(
            "com.toadfiles.app.LauncherPixelAmber",
            newSwitcher().componentNameFor(AppIconDesign.PIXEL, AppIconColor.AMBER).className,
        )
    }

    @Test
    fun `all 42 combos produce distinct names that resolve to manifest aliases`() {
        val switcher = newSwitcher()
        val classNames = mutableSetOf<String>()
        for (d in AppIconDesign.entries) {
            for (c in AppIconColor.entries) {
                val cn = switcher.componentNameFor(d, c)
                assertEquals(context.packageName, cn.packageName)
                assertTrue(cn.className.startsWith("${context.packageName}.Launcher"))
                // Every mapped component must be declared as an activity-alias in the
                // manifest source — this is what keeps the rule in sync with it.
                assertTrue(
                    "manifest must declare alias ${cn.className}",
                    manifestText.contains("android:name=\".${cn.className.substringAfterLast('.')}\""),
                )
                classNames += cn.className
            }
        }
        assertEquals(42, AppIconDesign.entries.size * AppIconColor.entries.size)
        assertEquals(42, classNames.size)
        assertEquals(42, Regex("<activity-alias").findAll(manifestText).count())
    }

    @Test
    fun `apply plan enables the chosen alias first and disables the other 41`() {
        // The component names embed the app's real package name; make that assumption
        // explicit so a harness with a different packageName fails loudly here.
        assertEquals("com.toadfiles.app", context.packageName)

        val plan = newSwitcher().plan(AppIconDesign.FACE, AppIconColor.TEAL)

        // Exactly 42 component changes; the target is FIRST and the only ENABLED one.
        assertEquals(42, plan.size)
        assertEquals(
            ComponentName("com.toadfiles.app", "com.toadfiles.app.LauncherFaceTeal"),
            plan.first().first,
        )
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, plan.first().second)
        assertEquals(41, plan.drop(1).map { it.first }.toSet().size)
        assertTrue(
            plan.drop(1).all { it.second == PackageManager.COMPONENT_ENABLED_STATE_DISABLED },
        )
        // The shipped default is among the disabled ones, and the target never repeats.
        assertTrue(plan.drop(1).any { it.first.className.endsWith("LauncherClassicGreen") })
        assertFalse(plan.drop(1).any { it.first.className.endsWith("LauncherFaceTeal") })
    }
}
