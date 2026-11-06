package com.toadfiles.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.toFileItem
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.util.Intents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shared plumbing for the media screens' file ops (Recent + Categories) — audit 7.3:
 * both ViewModels used to carry byte-identical launch/progress/result/cancel blocks, and
 * both screens byte-identical sheet + overlay wiring. This file is that one copy; it is
 * deliberately NOT a new op framework:
 *  - [OpRunner] owns only the job lifecycle. The ViewModel keeps its own state shape and
 *    applies updates through callbacks, so the progress/result model stays the one it
 *    already shares with Browse.
 *  - [MediaOpOverlays] and [MediaEntryActionSheet] are thin wiring over the existing
 *    [ProgressOverlay]/[ResultDialog] components — since v1.7.3 the per-entry sheet is
 *    the SAME [FileActionSheet] Browse renders, fed through [toFileItem], so Browse and
 *    the media screens offer one action sheet, not two diverging ones.
 * Browse (filesystem flows) keeps its fuller op engine (conflict handling, paste batch
 * clearing, …) — consolidating against it is out of scope here.
 */

/**
 * Launches one file op at a time on [ioDispatcher] inside [scope] and reports progress
 * and the result back through callbacks. Carries no state of its own beyond the current
 * [Job]; the calling ViewModel owns all op state.
 *
 * Single-flight (audit v2.0.0 A1): a new [run] cancels the previous op and waits for it
 * to fully unwind before starting the new one, so two taps can never run two
 * concurrent extractions into the same destination, and the older op's late [onDone]
 * can never overwrite the newer result. [cancel] runs its [after] callback only after
 * the op coroutine has actually stopped, so an in-flight progress tick can no longer
 * resurrect the overlay after the user dismissed it.
 */
class OpRunner(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    /** True while an op is running. */
    val isBusy: Boolean
        get() = job?.isActive == true

    /**
     * Runs [op] on the IO dispatcher as the ONLY op: the previous one (if any) is
     * cancelled and fully joined first, inside the new coroutine. Every progress tick
     * goes to [onProgress]; when the op finishes without having been cancelled,
     * [onDone] receives the [OpResult] still inside the op coroutine (before it ends),
     * so the caller can swap the progress overlay for the result dialog in a single
     * state update and chain its own refresh, exactly as the old inline blocks did.
     * A cancelled run NEVER publishes [onDone] — the [ensureActive] guard before it
     * rethrows the cancellation instead.
     */
    fun run(
        op: suspend (onProgress: (OpProgress) -> Unit) -> OpResult,
        onProgress: (OpProgress) -> Unit,
        onDone: (OpResult) -> Unit,
    ) {
        val previous = job
        job = scope.launch(ioDispatcher) {
            // Audit A1: single-flight. Cancel-and-join (not just replace) so the old
            // op cannot interleave writes with this one or deliver a stale result.
            previous?.cancelAndJoin()
            try {
                val r = op { onProgress(it) }
                currentCoroutineContext().ensureActive() // a cancelled op publishes nothing
                onDone(r)
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    /**
     * Cancels the current op (cooperative, like Browse's cancelOp). [after] runs once
     * the op coroutine has fully stopped — on [scope]'s dispatcher — so clearing the
     * progress overlay cannot race a final in-flight tick (audit A1: the overlay used
     * to be cleared before the op stopped, and a tick landing after the clear would
     * resurrect a dead overlay). Safe when no op is running: [after] runs right away.
     */
    fun cancel(after: () -> Unit) {
        val current = job
        if (current == null) {
            after()
            return
        }
        current.cancel()
        scope.launch {
            current.join()
            after()
        }
    }

    /** Cancels any running op; call from the ViewModel's onCleared(). */
    fun dispose() {
        job?.cancel()
    }
}

/**
 * Progress overlay + result dialog for a running/finished op — the exact wiring both
 * media screens rendered inline. Shows the overlay while [progress] is set (percent,
 * "N of M items" and the live entry name when [OpProgress.total] is positive,
 * "Working…" plus the entry name otherwise) and the result dialog once [result] arrives.
 */
@Composable
fun MediaOpOverlays(
    progress: Pair<String, OpProgress>?,
    result: OpResult?,
    resultTitle: String,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
) {
    progress?.let { (title, p) ->
        ProgressOverlay(
            text = title,
            done = p.done,
            total = p.total,
            currentItem = p.currentItem,
            onCancel = onCancel,
            bytesDone = p.bytesDone,
            bytesTotal = p.bytesTotal,
        )
    }
    result?.let { r ->
        ResultDialog(
            summary = "$resultTitle: ${r.summary}",
            failures = r.failures,
            onDismiss = onDismissResult,
            resultTitle = resultTitle,
        )
    }
}

/**
 * [FileActionSheet] with the standard per-entry wiring shared by Recent and the
 * Categories drill-in: every action first closes the sheet (via [onDismiss]), then
 * performs it, toasting on failure exactly as before. Since v1.7.3 the sheet is the very
 * same component Browse renders (see [FileActionSheet]) — the entry is bridged with
 * [toFileItem] — which is what brings Categories to Browse-level per-file parity
 * (rename, zip, extract to…, properties) without a second sheet implementation.
 *
 * Path-dependent actions (show in folder, copy path, delete, extract, rename, zip) stay
 * offered only when [MediaRepository.MediaEntry.data] carries a real path; clipboard
 * copy/move and favorites are Browse-only (no paste target / favorites store here), so
 * their callbacks are simply not wired and the rows stay hidden.
 *
 * @param open attempts to open the entry; returns false when no app handled it.
 * @param toast surfaces failures/info through the screen's snackbar channel.
 * @param requestDelete starts the trash flow for this single entry.
 * @param onShowInFolder receives the entry's real file path; the navigation layer
 *        resolves the parent folder and hands Browse the file name to pinpoint.
 * @param onDetails opens the entry's properties/details view.
 * @param onExtract runs the zip extract op for this entry's on-disk path ("Extract here").
 * @param onRename opens the rename flow for this entry (Categories only).
 * @param onZip opens the zip flow for this entry's file (Categories only).
 * @param onExtractTo opens the named-destination extract flow (Categories only).
 */
@Composable
fun MediaEntryActionSheet(
    entry: MediaRepository.MediaEntry?,
    onDismiss: () -> Unit,
    open: (MediaRepository.MediaEntry) -> Boolean,
    onShowInFolder: (String) -> Unit,
    toast: (String) -> Unit,
    requestDelete: (MediaRepository.MediaEntry) -> Unit,
    onDetails: (MediaRepository.MediaEntry) -> Unit,
    onExtract: (File) -> Unit,
    onRename: ((MediaRepository.MediaEntry) -> Unit)? = null,
    onZip: ((File) -> Unit)? = null,
    onExtractTo: ((File) -> Unit)? = null,
) {
    val context = LocalContext.current
    entry?.let { e ->
        FileActionSheet(
            item = e.toFileItem(),
            onOpen = {
                onDismiss()
                if (!open(e)) toast("No app can open this file")
            },
            onOpenWith = {
                onDismiss()
                if (!Intents.openContentWith(context, e.uri, e.mime)) {
                    toast("No app can open this file")
                }
            },
            onShare = {
                onDismiss()
                if (!Intents.shareUris(context, listOf(e.uri), e.mime)) {
                    toast("Could not share this item")
                }
            },
            onShowInFolder = e.data?.let { path ->
                {
                    onDismiss()
                    // Full file path — the nav host resolves the parent and passes the
                    // name to Browse for the scroll-to + blink pinpoint.
                    onShowInFolder(path)
                }
            },
            onDelete = e.data?.let {
                {
                    onDismiss()
                    requestDelete(e)
                }
            },
            onDetails = {
                onDismiss()
                onDetails(e)
            },
            onCopyPath = e.data?.let { path ->
                {
                    onDismiss()
                    if (Intents.copyPathToClipboard(context, File(path))) {
                        toast("Path copied to clipboard")
                    } else {
                        toast("Could not copy the path")
                    }
                }
            },
            // The sheet itself only renders these for extractable archives; here we just
            // need a real on-disk path for the ZipSupport/ArchiveOps op.
            onExtract = e.data?.let { path ->
                {
                    onDismiss()
                    onExtract(File(path))
                }
            },
            onExtractTo = if (onExtractTo != null) {
                e.data?.let { path -> { onDismiss(); onExtractTo(File(path)) } }
            } else null,
            onRename = if (onRename != null) {
                e.data?.let { { onDismiss(); onRename(e) } }
            } else null,
            onZip = if (onZip != null) {
                e.data?.let { path -> { onDismiss(); onZip(File(path)) } }
            } else null,
            onDismiss = onDismiss,
        )
    }
}
