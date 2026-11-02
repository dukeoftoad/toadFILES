package com.toadfiles.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.domain.AppIconColor
import com.toadfiles.app.domain.AppIconDesign
import com.toadfiles.app.domain.PackageManagerAppIconSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PackageManagerAppIconSwitcher against Robolectric's real PackageManager and the
 * merged manifest (all 36 activity-aliases are parsed from it). The component-name
 * rule must match the manifest alias names exactly — otherwise the switcher would
 * flip the state of components that don't exist and the launcher icon would never
 * change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIconSwitcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newSwitcher() = PackageManagerAppIconSwitcher(context)

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
    fun `all 36 combos produce distinct names that resolve to manifest aliases`() {
        val switcher = newSwitcher()
        val pm = context.packageManager
        val classNames = mutableSetOf<String>()
        for (d in AppIconDesign.entries) {
            for (c in AppIconColor.entries) {
                val cn = switcher.componentNameFor(d, c)
                assertEquals(context.packageName, cn.packageName)
                assertTrue(cn.className.startsWith("${context.packageName}.Launcher"))
                // Every mapped component must be a real activity-alias in the merged
                // manifest — this is what keeps the rule in sync with AndroidManifest.
                assertNotNull("no manifest alias for ${cn.className}", pm.getActivity(cn, 0))
                classNames += cn.className
            }
        }
        assertEquals(36, AppIconDesign.entries.size * AppIconColor.entries.size)
        assertEquals(36, classNames.size)
    }

    @Test
    fun `apply enables the chosen alias and disables the shipped default`() {
        // The component names embed the app's real package name; make that assumption
        // explicit so a harness with a different packageName fails loudly here.
        assertEquals("com.toadfiles.app", context.packageName)

        newSwitcher().apply(AppIconDesign.FACE, AppIconColor.TEAL)

        val pm = context.packageManager
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(
                ComponentName("com.toadfiles.app", "com.toadfiles.app.LauncherFaceTeal"),
            ),
        )
        // The v1.8.2 default alias must be turned off, and so must a random other combo.
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(
                ComponentName("com.toadfiles.app", "com.toadfiles.app.LauncherClassicGreen"),
            ),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(
                ComponentName("com.toadfiles.app", "com.toadfiles.app.LauncherSdRose"),
            ),
        )
    }
}
