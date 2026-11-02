package com.toadfiles.app.domain

import com.toadfiles.app.data.MediaRepository
import java.io.File
import java.nio.file.Files

/**
 * Structured outcome of moving media-backed rows (Categories drill-in, Recent) into the
 * trash, so both surfaces can report honestly and never let a stale cached path destroy
 * data:
 * - [movedFiles] are the trash payloads (restore targets for the undo snackbar);
 * - [movedEntries] are exactly the entries whose file was moved into the trash — the
 *   only entries whose (now stale) MediaStore row may be dropped;
 * - [staleEntries] have a cached path that no longer exists (file moved or renamed
 *   outside the app). They are NEVER trashed and NEVER row-dropped: the row's CURRENT
 *   DATA may point at a real file, so they simply stay visible and are reported;
 * - [failedEntries] could not be moved (trash refused, or the move was rolled back
 *   after a metadata failure). The file is still at its original path, so these must
 *   not be row-dropped either;
 * - [noPathCount] counts rows without any DATA value — nothing to move, reported.
 */
data class MediaTrashResult(
    val movedFiles: List<File> = emptyList(),
    val movedEntries: List<MediaRepository.MediaEntry> = emptyList(),
    val staleEntries: List<MediaRepository.MediaEntry> = emptyList(),
    val failedEntries: List<MediaRepository.MediaEntry> = emptyList(),
    val noPathCount: Int = 0,
)

/**
 * One shared delete-to-trash pipeline for media-backed surfaces (Categories, Recent).
 * Classifies each entry by its cached DATA path, moves only the files that really
 * exist on disk into the [Trash], and maps every per-file outcome of [Trash.trash]
 * back to its entry so callers can report skipped/failed items instead of silently
 * filtering them out — the old per-surface filters hid exactly the entries whose row
 * then got hard-dropped by row cleanup, losing the file everywhere.
 *
 * Pure JVM (plain [File]/[Files] IO only): the only Android type involved is the
 * [MediaRepository.MediaEntry] value the callers already hold, so the whole pipeline
 * is unit-testable.
 */
object MediaTrashOps {

    /**
     * Moves the trashable subset of [entries] into the trash on [roots].
     * [trash] is injectable so tests can fake [Trash.trash]; production uses the real one.
     */
    fun deleteToTrash(
        entries: List<MediaRepository.MediaEntry>,
        roots: List<File>,
        trash: (List<File>, List<File>) -> TrashResult = Trash::trash,
    ): MediaTrashResult {
        val noPathCount = entries.count { it.data == null }
        val trashable = mutableListOf<Pair<MediaRepository.MediaEntry, File>>()
        val stale = mutableListOf<MediaRepository.MediaEntry>()
        for (e in entries) {
            val path = e.data ?: continue
            val f = File(path)
            // exists() follows symlinks — a dangling link is still trashable (D7). A
            // cached path that is neither a file nor a link means the file was moved
            // or renamed OUTSIDE the app: never trash it, never drop its row — the
            // row's current DATA may point at a real file (TOCTOU data-loss guard).
            if (f.exists() || Files.isSymbolicLink(f.toPath())) trashable += e to f
            else stale += e
        }
        val result = trash(trashable.map { it.second }, roots)
        // TrashResult reports rolledBack/failed by ORIGINAL path and moved by trash
        // target path, so the not-moved set partitions the trashable sources exactly
        // (every input ends up in exactly one bucket). A rollback means the payload
        // came back to its original path — for the user it "could not be moved", and
        // its row must stay, just like a straight failure.
        val notMoved = (result.failed + result.rolledBack).toHashSet()
        return MediaTrashResult(
            movedFiles = result.moved,
            movedEntries = trashable.mapNotNull { (e, f) -> if (f !in notMoved) e else null },
            staleEntries = stale,
            failedEntries = trashable.mapNotNull { (e, f) -> if (f in notMoved) e else null },
            noPathCount = noPathCount,
        )
    }

    /**
     * The honest partial-outcome toast for a delete batch, or null when everything the
     * batch could move was moved — full success is reported by the screen's Undo
     * snackbar (Browse parity) instead of a plain toast. Every skipped/failed bucket
     * gets its reason, merged into one concise line.
     */
    fun toastFor(result: MediaTrashResult): String? {
        val notes = buildList {
            if (result.noPathCount > 0) add("${result.noPathCount} had no on-disk path")
            if (result.staleEntries.isNotEmpty()) {
                add("${result.staleEntries.size} were moved or renamed outside the app and were skipped")
            }
            if (result.failedEntries.isNotEmpty()) add("${result.failedEntries.size} could not be moved")
        }
        if (notes.isEmpty()) return null
        return (listOf("Moved ${result.movedEntries.size} to trash") + notes).joinToString(" · ")
    }
}
