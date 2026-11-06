package com.toadfiles.app.domain

import java.io.File
import java.nio.file.Files

/**
 * The one undo engine (audit A22): restores a delete batch (the snackbar "Undo") from
 * the captured trash payload list. This exact loop was byte-identical in the
 * Browse, Categories and Recent ViewModels and had already started drifting in its
 * surroundings; the engine keeps one honest-accounting implementation that all three
 * surfaces share, so a fix here can never be forgotten on one surface.
 *
 * Honest accounting (N5): items already gone from the trash are reported as
 * "already gone", collision renames are counted (D4), and restored files are
 * re-scanned into the media index (D1) so they reappear immediately.
 *
 * Pure domain: only [Trash] and plain file IO; the media re-scan is injected as the
 * [rescan] callback (the real repository hands [MediaRepository.rescan], tests record
 * calls), so this file drags in no Android or data-layer types.
 *
 * Cancellation: [Trash.restore] is a plain rename — it cannot be interrupted
 * mid-item; the loop checks nothing between items on purpose. A cancelled undo
 * finishes the item it is on and stops at the caller's next suspension point
 * (the rescan callback), leaving the rest of the batch safely still in the trash.
 */
data class UndoOutcome(
    /** How many items the batch originally contained. */
    val requested: Int,
    /** Payloads moved back to their origin (or a collision-renamed variant, D4). */
    val restored: Int,
    /** Restored items whose on-disk name had to differ from the original (collision). */
    val renamed: Int,
    /** Payloads that vanished from the trash before the undo ran — reported, not restored. */
    val gone: Int,
    /** Absolute paths of the restored files, in batch order (feeds the media re-scan). */
    val restoredPaths: List<String>,
) {
    /** The snackbar copy the three surfaces show; identical wording to the old copies. */
    fun toastMessage(): String {
        val renamedNote = if (renamed > 0) ", $renamed renamed" else ""
        return when {
            gone > 0 -> "Restored $restored of $requested — $gone already gone$renamedNote"
            restored < requested ->
                "Restored $restored of $requested — the rest stays in Trash$renamedNote"
            else -> "Restored $restored ${Format.itemWord(restored)}$renamedNote"
        }
    }
}

/**
 * Restores [trashed] payloads and re-scans the restored paths into the media index.
 * Returns the structured [UndoOutcome] whose [UndoOutcome.toastMessage] is the toast
 * every surface shows. Empty batches return a zero outcome without touching anything
 * (the ViewModels keep their early return; this makes the engine safe to call alone).
 */
suspend fun undoDeleteBatch(
    trashed: List<File>,
    rescan: suspend (List<String>) -> Unit,
): UndoOutcome {
    if (trashed.isEmpty()) return UndoOutcome(0, 0, 0, 0, emptyList())
    var restored = 0
    var renamed = 0
    var gone = 0
    val restoredPaths = mutableListOf<String>()
    for (t in trashed) {
        // exists() follows symlinks — a dangling-link payload still restores (D7).
        if (!t.exists() && !Files.isSymbolicLink(t.toPath())) {
            gone++
            continue
        }
        val origin = Trash.originOf(t)
        val dest = Trash.restore(t) ?: continue // stays in the trash, still restorable
        restored++
        if (origin != null && dest.name != origin.substringAfterLast('/')) renamed++
        restoredPaths += dest.absolutePath
    }
    rescan(restoredPaths)
    return UndoOutcome(trashed.size, restored, renamed, gone, restoredPaths)
}
