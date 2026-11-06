package com.toadfiles.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.ui.components.ProgressOverlay
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ProgressOverlay UI test on the JVM — isolated compose content (AlertDialog, no nav
 * shell, no scrolling), following the repo's established primitives (see
 * FileActionSheetTest / TransferBarTest): text existence via fetchSemanticsNode counts,
 * because assertIsDisplayed is unreliable on dialog windows under Robolectric.
 *
 * Contract under test (the v1.9.x progress-bar fixes): a determinate op shows HOW FAR
 * (percent) and WHAT is happening ("N of M items" — proper plural, never "item(s)" —
 * plus the current entry name), an unknown-total op stays honest with "Working…" and
 * the current entry, and Cancel is always reachable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProgressOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(
        done: Int,
        total: Int,
        currentItem: String? = null,
        onCancel: () -> Unit = {},
        bytesDone: Long = -1L,
        bytesTotal: Long = -1L,
    ) {
        compose.setContent {
            ToadTheme {
                ProgressOverlay(
                    text = "Extracting pack.zip",
                    done = done,
                    total = total,
                    currentItem = currentItem,
                    onCancel = onCancel,
                    bytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                )
            }
        }
    }

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun determinateShowsCountPercentAndCurrentItem() {
        render(done = 2, total = 4, currentItem = "photos/day1.jpg")

        // The detail line: count + live entry name, one glance instead of a bare bar.
        assertTrue(hasText("2 of 4 items — photos/day1.jpg"))
        assertTrue(hasText("50%"))
        assertFalse(hasText("Working…"))
    }

    @Test
    fun determinateWithoutCurrentItemShowsJustTheCount() {
        render(done = 1, total = 1)

        // Proper singular — "1 of 1 item", never "1 of 1 item(s)".
        assertTrue(hasText("1 of 1 item"))
        assertFalse(hasText("1 of 1 items"))
        assertTrue(hasText("100%"))
    }

    @Test
    fun byteProgressDrivesPercentAndShowsTallyPlusFileCount() {
        // Zip extraction reports bytes (owner report "441 of 2"): the percent comes from
        // bytes, the tally line carries the live entry, and the item count stays on its
        // own line so "how many files" is still answered.
        render(
            done = 1,
            total = 2,
            currentItem = "toadGALLERY-0.4.5-alpha-engine-debug.apk",
            bytesDone = 45_000_000L,
            bytesTotal = 79_900_000L,
        )

        assertTrue(hasText("42.9 MB of 76.2 MB — toadGALLERY-0.4.5-alpha-engine-debug.apk"))
        assertTrue(hasText("File 1 of 2"))
        assertTrue(hasText("56%"))
        assertFalse(hasText("1 of 2 items"))
    }

    @Test
    fun itemCountOpsKeepTheItemDerivedPercent() {
        // Copy/move/compress pass no bytes: the item count stays the percent source.
        render(done = 2, total = 4, currentItem = "photos/day1.jpg")

        assertTrue(hasText("2 of 4 items — photos/day1.jpg"))
        assertTrue(hasText("50%"))
    }

    @Test
    fun unknownTotalStaysIndeterminateAndNamesTheCurrentEntry() {
        render(done = 0, total = 0, currentItem = "merging.7z.002")

        assertTrue(hasText("Working…"))
        assertTrue(hasText("merging.7z.002"))
        // No fake percent when the total is unknown.
        assertFalse(hasText("0%"))
    }

    @Test
    fun cancelIsReachableAndFires() {
        var cancelled = false
        render(done = 1, total = 4, onCancel = { cancelled = true })

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertTrue(cancelled)
    }
}
