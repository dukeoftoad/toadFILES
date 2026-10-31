package com.toadfiles.app

import com.toadfiles.app.ui.components.VolumeChoice
import com.toadfiles.app.ui.components.compressConfirmEnabled
import com.toadfiles.app.ui.components.customVolumeTextToBytes
import com.toadfiles.app.ui.components.volumeChoiceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the v1.8.0 compress dialog's validation helpers (the dialog
 * itself is thin VM-backed UI; these are the rules that gate it). The same helpers
 * back Browse and Categories, so one suite covers both surfaces.
 */
class CompressDialogLogicTest {

    // ---------- volume choice mapping ----------

    @Test
    fun `volumeChoiceFor maps presets back from bytes`() {
        assertEquals(VolumeChoice.NONE, volumeChoiceFor(null, custom = false))
        assertEquals(VolumeChoice.MB5, volumeChoiceFor(5L * 1024 * 1024, custom = false))
        assertEquals(VolumeChoice.MB10, volumeChoiceFor(10L * 1024 * 1024, custom = false))
        assertEquals(VolumeChoice.MB50, volumeChoiceFor(50L * 1024 * 1024, custom = false))
        assertEquals(VolumeChoice.MB100, volumeChoiceFor(100L * 1024 * 1024, custom = false))
        assertEquals(VolumeChoice.MB700, volumeChoiceFor(700L * 1024 * 1024, custom = false))
        assertEquals(VolumeChoice.GB1, volumeChoiceFor(1024L * 1024 * 1024, custom = false))
        // The Custom… flag wins over whatever preset bytes are still stored.
        assertEquals(VolumeChoice.CUSTOM, volumeChoiceFor(5L * 1024 * 1024, custom = true))
        // Unknown byte values (only reachable through stale state) fall back to No.
        assertEquals(VolumeChoice.NONE, volumeChoiceFor(12345L, custom = false))
    }

    @Test
    fun `every preset maps to its exact byte size`() {
        val mb = 1024L * 1024
        assertEquals(null, VolumeChoice.NONE.bytes)
        assertEquals(5 * mb, VolumeChoice.MB5.bytes)
        assertEquals(10 * mb, VolumeChoice.MB10.bytes)
        assertEquals(50 * mb, VolumeChoice.MB50.bytes)
        assertEquals(100 * mb, VolumeChoice.MB100.bytes)
        assertEquals(700 * mb, VolumeChoice.MB700.bytes)
        assertEquals(1024 * mb, VolumeChoice.GB1.bytes)
        assertEquals(null, VolumeChoice.CUSTOM.bytes)
    }

    @Test
    fun `customVolumeTextToBytes maps megabytes text to bytes`() {
        val mb = 1024L * 1024
        assertEquals(5 * mb, customVolumeTextToBytes("5"))
        assertEquals(7 * mb, customVolumeTextToBytes(" 7 ")) // trimmed
        // Blank / non-numeric / non-positive custom sizes mean "no split", never a
        // blocked OK button.
        assertEquals(null, customVolumeTextToBytes(""))
        assertEquals(null, customVolumeTextToBytes("abc"))
        assertEquals(null, customVolumeTextToBytes("0"))
        assertEquals(null, customVolumeTextToBytes("-3"))
        assertEquals(null, customVolumeTextToBytes("1.5"))
    }

    // ---------- OK button gating ----------

    @Test
    fun `compressConfirmEnabled requires a non-blank name`() {
        assertFalse(compressConfirmEnabled("", encrypted = false, password = "", passwordConfirm = ""))
        assertFalse(compressConfirmEnabled("  ", encrypted = false, password = "", passwordConfirm = ""))
        assertTrue(compressConfirmEnabled("pack", encrypted = false, password = "", passwordConfirm = ""))
    }

    @Test
    fun `compressConfirmEnabled blocks on password mismatch when encrypting`() {
        // OK disabled on a blank password…
        assertFalse(compressConfirmEnabled("pack", encrypted = true, password = "", passwordConfirm = ""))
        // …or a mismatch…
        assertFalse(compressConfirmEnabled("pack", encrypted = true, password = "one", passwordConfirm = "two"))
        // …and enabled on a matching pair.
        assertTrue(compressConfirmEnabled("pack", encrypted = true, password = "one", passwordConfirm = "one"))
    }

    @Test
    fun `compressConfirmEnabled ignores password fields when encryption is off`() {
        // Leftover drafts (the ViewModel clears them anyway) never block a plain run.
        assertTrue(compressConfirmEnabled("pack", encrypted = false, password = "one", passwordConfirm = "two"))
    }
}
