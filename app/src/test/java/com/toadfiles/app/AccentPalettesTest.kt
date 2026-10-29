package com.toadfiles.app

import androidx.compose.material3.ColorScheme
import com.toadfiles.app.domain.AccentColor
import com.toadfiles.app.ui.theme.AccentPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the accent-palette table — no Robolectric needed because ColorScheme
 * values are plain objects. The key invariant we guard: every AccentColor has a working
 * (light, dark) pair, the TOAD branch matches the v1.3 moss/mud palette exactly, and the
 * swatch helper exposes a representative color for the picker UI.
 */
class AccentPalettesTest {

    @Test
    fun `every accent returns a non-null scheme in both light and dark mode`() {
        AccentColor.entries.forEach { accent ->
            val light: ColorScheme = AccentPalettes.schemeFor(accent, dark = false)
            val dark: ColorScheme = AccentPalettes.schemeFor(accent, dark = true)
            assertNotNull("Light scheme for $accent is null", light)
            assertNotNull("Dark scheme for $accent is null", dark)
        }
    }

    @Test
    fun `light and dark schemes differ for every accent`() {
        AccentColor.entries.forEach { accent ->
            val light = AccentPalettes.schemeFor(accent, dark = false)
            val dark = AccentPalettes.schemeFor(accent, dark = true)
            assertNotEquals("Light vs dark should differ for $accent", light.primary, dark.primary)
        }
    }

    @Test
    fun `TOAD light scheme preserves the v1 moss-green primary`() {
        // v1.0–v1.3 used #4E7A46 as the moss-green primary. v1.4 must keep that exact value
        // as the default so upgraders see no visual regression.
        val toadLight = AccentPalettes.schemeFor(AccentColor.TOAD, dark = false)
        assertEquals(0xFF4E7A46.toInt(), toadLight.primary.value.toInt())
    }

    @Test
    fun `TOAD dark scheme preserves the v1 light-moss primary`() {
        val toadDark = AccentPalettes.schemeFor(AccentColor.TOAD, dark = true)
        assertEquals(0xFFA5C89A.toInt(), toadDark.primary.value.toInt())
    }

    @Test
    fun `every accent exposes a distinct swatch for the picker`() {
        val swatches = AccentColor.entries.map { AccentPalettes.swatch(it) }
        assertEquals(AccentColor.entries.size, swatches.size)
        // Pairwise distinct — no two accents share a swatch
        val asSet = swatches.toSet()
        assertEquals(swatches.size, asSet.size)
    }

    @Test
    fun `TOAD swatch matches its light primary, so picker matches the active theme`() {
        val toadLight = AccentPalettes.schemeFor(AccentColor.TOAD, dark = false)
        val toadSwatch = AccentPalettes.swatch(AccentColor.TOAD)
        assertEquals(toadLight.primary, toadSwatch)
    }

    @Test
    fun `accent labels are uppercased first-letter of the enum name`() {
        // Defensive: if someone adds a multi-word enum like DEEP_BLUE, the label rule should
        // still produce a sensible string. We assert the existing convention here so a future
        // contributor sees the failure when their label doesn't match.
        AccentColor.entries.forEach { accent ->
            val expected = accent.name.lowercase().replaceFirstChar { it.uppercase() }
            assertEquals(expected, accent.label)
        }
        assertTrue(AccentColor.entries.size >= 7)
    }
}
