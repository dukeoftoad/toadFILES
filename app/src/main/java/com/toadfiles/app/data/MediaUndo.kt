package com.toadfiles.app.data

import com.toadfiles.app.domain.Trash
import java.io.File
import java.nio.file.Files

/**
 * Honest accounting of one undo batch (snackbar "Undo" after a delete-to-trash):
 * - [restored] payloads moved back out of the trash;
 * - [renamed] restores that collided at the origin and landed as "name (1).ext" (D4);
 * - [gone] payloads that had already vanished from the trash (N5) — nothing was
 *   restored for them and the trash no longer holds them either;
 * - [restoredPaths] the on-disk paths that came back, in restore order (also handed
 *   to the media source for re-indexing, so the files reappear immediately).
 * [stillInTrash] covers the rest: payloads whose restore was refused (they remain
 * listed and restorable in the Trash page).
 */
data class MediaUndoOutcome(
    val requested: Int,
    val restored: Int,
    val renamed: Int,
    val gone: Int,
    val restoredPaths: List<String> = emptyList(),
) {
    /** Restore refusals: the payload is still in the trash and a retry is safe. */
    val stillInTrash: Int get() = requested - restored - gone
}

/**
 * The one restore-undo flow behind the media screens' "Undo" snackbar (Categories,
 * Recent; Browse runs the same loop inline). Per payload: restore through [Trash],
 * count collision renames and already-gone items honestly, then re-index the restored
 * paths via [MediaQuerySource.rescan] (a real repository hands them to the platform
 * media scanner, so the files reappear in Recent/Categories and system galleries).
 * Suspended so callers run it on their IO dispatcher; blocking IO only.
 */
object MediaUndo {

    suspend fun restore(trashed: List<File>, media: MediaQuerySource): MediaUndoOutcome {
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
        media.rescan(restoredPaths)
        return MediaUndoOutcome(
            requested = trashed.size,
            restored = restored,
            renamed = renamed,
            gone = gone,
            restoredPaths = restoredPaths,
        )
    }

    /**
     * The honest delete summary shared by the media screens: null only when every
     * requested item was moved (the screen's Undo snackbar reports that success),
     * otherwise a "Moved …" line that accounts for what the pre-filter skipped
     * (entries without an on-disk path or with a vanished file) and what the trash
     * outright failed to move — mirroring Browse's partial-success tone.
     *
     * @param moved payloads now in the trash; @param total requested entries;
     * @param skipped entries never handed to the trash (no path / file gone);
     * @param failed trash failures (moved + skipped + failed always equals total).
     */
    fun deleteSummary(moved: Int, total: Int, skipped: Int, failed: Int): String? = when {
        moved == total -> null // full success — the undo snackbar reports it
        failed == 0 -> "Moved $moved to trash · $skipped had no on-disk path"
        skipped == 0 -> "Moved $moved of $total to trash ($failed failed)"
        else -> "Moved $moved of $total to trash ($failed failed, $skipped had no on-disk path)"
    }
}
