package com.toadfiles.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.OpProgress
import com.toadfiles.app.domain.OpResult
import com.toadfiles.app.util.Intents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shared plumbing for the media screens' zip-extract op (Recent + Categories) — audit 7.3:
 * both ViewModels used to carry byte-identical launch/progress/result/cancel blocks, and
 * both screens byte-identical sheet + overlay wiring. This file is that one copy; it is
 * deliberately NOT a new op framework:
 *  - [OpRunner] owns only the job lifecycle. The ViewModel keeps its own state shape and
 *    applies updates through callbacks, so the progress/result model stays the one it
 *    already shares with Browse.
 *  - [MediaOpOverlays] and [MediaEntryActionSheet] are thin wiring over the existing
 *    [ProgressOverlay]/[ResultDialog]/[MediaActionSheet] components with the exact same
 *    strings, guards and callback order the screens had before.
 * Browse (filesystem flows) keeps its fuller op engine (conflict handling, paste batch
 * clearing, …) — consolidating against it is out of scope here.
 */

/**
 * Launches one file op at a time on [ioDispatcher] inside [scope] and reports progress
 * and the result back through callbacks. Carries no state of its own beyond the current
 * [Job]; the calling ViewModel owns all op state.
 */
class OpRunner(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    /**
     * Runs [op] on the IO dispatcher. Every progress tick goes to [onProgress]; when the
     * op finishes, [onDone] receives the [OpResult] still inside the op coroutine (before
     * it ends), so the caller can swap the progress overlay for the result dialog in a
     * single state update and chain its own refresh, exactly as the old inline blocks did.
     * Like the ViewModels' former `opJob` handling, a new run replaces the job reference
     * without cancelling a still-running previous one.
     */
    fun run(
        op: suspend (onProgress: (OpProgress) -> Unit) -> OpResult,
        onProgress: (OpProgress) -> Unit,
        onDone: (OpResult) -> Unit,
    ) {
        job = scope.launch(ioDispatcher) {
            val r = op { onProgress(it) }
            onDone(r)
        }
    }

    /** Cancels the current op (cooperative, like Browse's cancelOp), then runs [after]. */
    fun cancel(after: () -> Unit) {
        job?.cancel()
        after()
    }

    /** Cancels any running op; call from the ViewModel's onCleared(). */
    fun dispose() {
        job?.cancel()
    }
}

/**
 * Progress overlay + result dialog for a running/finished op — the exact wiring both
 * media screens rendered inline. Shows the overlay while [progress] is set (fraction
 * only when [OpProgress.total] is positive, indeterminate otherwise) and the result
 * dialog once [result] arrives.
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
            fraction = if (p.total > 0) p.done.toFloat() / p.total else null,
            onCancel = onCancel,
        )
    }
    result?.let { r ->
        ResultDialog(
            summary = "$resultTitle: ${r.summary}",
            failures = r.failures,
            onDismiss = onDismissResult,
        )
    }
}

/**
 * [MediaActionSheet] with the standard per-entry wiring shared by Recent and the
 * Categories drill-in: every action first closes the sheet (via [onDismiss], which the
 * caller also binds to [MediaActionSheet.onDismiss]), then performs it, toasting on
 * failure exactly as before. Path-dependent actions (show in folder, copy path, delete,
 * extract) stay offered only when [MediaRepository.MediaEntry.data] carries a real path.
 *
 * @param open attempts to open the entry; returns false when no app handled it.
 * @param toast surfaces failures/info through the screen's snackbar channel.
 * @param requestDelete starts the trash flow for this single entry.
 * @param onDetails opens the entry's details dialog.
 * @param onExtract runs the zip extract op for this entry's on-disk path.
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
) {
    val context = LocalContext.current
    entry?.let { e ->
        MediaActionSheet(
            entry = e,
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
                    val parent = File(path).parentFile
                    if (parent != null && parent.isDirectory) onShowInFolder(parent.absolutePath)
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
            // The sheet itself only renders this for .zip entries; here we just need a
            // real on-disk path for the ZipSupport op.
            onExtract = e.data?.let { path ->
                {
                    onDismiss()
                    onExtract(File(path))
                }
            },
            onDismiss = onDismiss,
        )
    }
}
