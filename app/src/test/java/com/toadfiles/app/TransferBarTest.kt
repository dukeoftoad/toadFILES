package com.toadfiles.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.ui.components.TransferBar
import com.toadfiles.app.ui.theme.ToadTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * TransferBar UI test on the JVM — isolated compose content: no nav shell, no scrolling,
 * none of the Robolectric scroll/animation flakiness. Follows the repo's established
 * primitives (see FileActionSheetTest): existence via fetchSemanticsNode(s), enabled-state
 * via the Disabled semantics key instead of viewport-dependent assertions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TransferBarTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(
        mode: Clipboard.Mode,
        count: Int = 1,
        blockedReason: String? = null,
        onPaste: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            ToadTheme {
                TransferBar(
                    mode = mode,
                    count = count,
                    blockedReason = blockedReason,
                    onPaste = onPaste,
                    onCancel = onCancel,
                )
            }
        }
    }

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun node(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()

    @Test
    fun moveModeShowsActionFirstTitleAndMoveIcon() {
        render(Clipboard.Mode.MOVE, count = 2)

        assertTrue(hasText("Move to here"))
        node("transfer_icon_move") // exists — the drive-file-move intent icon
        assertFalse(hasText("1 item"))
        assertTrue(hasText("2 items")) // proper plural, never "2 item(s)"
    }

    @Test
    fun copyModeShowsCopyTitleAndCopyIcon() {
        render(Clipboard.Mode.COPY, count = 1)

        assertTrue(hasText("Copy to here"))
        node("transfer_icon_copy")
        assertTrue(hasText("1 item"))
        assertFalse(hasText("1 items"))
    }

    @Test
    fun blockedReasonDisablesPasteAndExplainsWhy() {
        render(
            Clipboard.Mode.MOVE,
            count = 1,
            blockedReason = "You can't move a folder into itself",
        )

        assertTrue(hasText("You can't move a folder into itself"))
        val paste = node("transfer_paste")
        assertTrue(paste.config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun unblockedPasteIsEnabled() {
        render(Clipboard.Mode.COPY, count = 3)

        val paste = node("transfer_paste")
        assertFalse(paste.config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun cancelCallsOnCancel() {
        var cancelled = false
        render(Clipboard.Mode.MOVE, onCancel = { cancelled = true })

        compose.onNodeWithTag("transfer_cancel").performClick()
        compose.waitForIdle()

        assertTrue(cancelled)
    }
}
