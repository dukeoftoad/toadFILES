package com.toadfiles.app.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files

/**
 * Recursive content counts for the Browse properties sheet. Size is deliberately NOT
 * computed here — [FileOps.sizeOf] stays the app's single symlink-safe size engine; this
 * walk only counts entries so the properties sheet can show "N files · M subfolders".
 *
 * Traversal policy mirrors [FileOps.sizeOf]: symbolic links are leaves (never expanded,
 * so a link back to an ancestor cannot loop the walk) and unreadable directories
 * contribute only what could be listed, never an error. [countEntries] checkpoints
 * cancellation per directory and per entry, like BrowseViewModel's search traversal.
 * [open] only so tests can inject a gated subclass (see BrowseViewModelTest).
 */
open class PropertiesBuilder {

    /** Recursive entry counts below [root] ([root] itself is not included). */
    data class Counts(val files: Int, val dirs: Int)

    open suspend fun countEntries(root: File): Counts {
        var files = 0
        var dirs = 0
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive() // cancellation checkpoint per directory
            val children = FileOps.listFilesSafe(stack.removeLast()) ?: continue
            for (c in children) {
                currentCoroutineContext().ensureActive() // cancellation checkpoint per entry
                if (Files.isSymbolicLink(c.toPath())) {
                    files++ // a link is a leaf: never expanded (same policy as FileOps)
                } else if (c.isDirectory) {
                    dirs++
                    stack.addLast(c)
                } else {
                    files++
                }
            }
        }
        return Counts(files, dirs)
    }
}
