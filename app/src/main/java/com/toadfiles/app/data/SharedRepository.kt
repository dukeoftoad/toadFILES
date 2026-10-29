package com.toadfiles.app.data

import android.content.Context
import com.toadfiles.app.domain.Format
import java.io.File
import java.io.IOException

/**
 * Local-only share. Mirrors the privacy promise of toadFILES: "share" never hands files to
 * another app over the network or via ACTION_SEND — instead it duplicates the chosen files
 * into an app-private "shared" directory under [Context.getExternalFilesDir]. From there the
 * user can copy them out via USB, push to another app via a later system share, or just keep
 * a snapshot. Nothing is uploaded; nothing leaves the device.
 *
 * Why an app-private directory rather than a public one:
 *  - On Android 10+ the public Downloads/Media directories are scoped; writing there needs
 *    MediaStore round-trips that defeat the "local copy" simplicity we want.
 *  - An app-private dir under getExternalFilesDir is wiped on uninstall, so shared copies
 *    can't outlive toadFILES. That matches the user's expectation of "share is a copy in this
 *    app", and keeps the directory growing without limit only while the user actively uses it.
 *
 * Conflict handling: when a destination already exists with the same name, an " (n)" suffix
 * is appended before the extension — the same scheme Browse uses for conflict-rename. This
 * means "sharing the same file twice" produces two snapshots, not an overwrite.
 */
class SharedRepository(context: Context) {

    private val baseDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "shared").apply {
        if (!exists()) mkdirs()
    }

    /** Snapshot of every file currently held in the local shared folder. */
    fun list(): List<File> = baseDir.listFiles().orEmpty()
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }

    /** Bytes consumed by all snapshots. Used by About so the user sees the cost. */
    fun usedBytes(): Long = list().sumOf { it.length() }

    /**
     * Copy [sources] into the shared folder. Returns a per-item report so the caller can show
     * "shared 3 of 5" honestly, the same way copy/move operations already do in Browse.
     *
     * Failures (read/IO error on the source, or a source that vanished mid-copy) are
     * reported but never abort the batch — one bad file shouldn't lose the rest.
     */
    fun copyIn(sources: List<File>): SharedResult {
        if (sources.isEmpty()) return SharedResult(0, 0, emptyList())
        var copied = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        for (src in sources) {
            if (!src.exists() || !src.canRead()) {
                skipped++
                failures += "${src.name}: missing or unreadable"
                continue
            }
            if (src.isDirectory) {
                skipped++
                failures += "${src.name}: directories cannot be shared"
                continue
            }
            val dest = uniqueDest(src.name)
            try {
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            } catch (e: IOException) {
                skipped++
                failures += "${src.name}: ${e.message ?: "I/O error"}"
                // Best-effort cleanup of a half-written file.
                runCatching { if (dest.exists() && dest.length() < src.length()) dest.delete() }
            }
        }
        return SharedResult(copied, skipped, failures)
    }

    /** Removes one snapshot. Returns false if the file no longer exists. */
    fun remove(name: String): Boolean {
        val f = File(baseDir, name)
        // Path safety: reject path separators in the name, otherwise [File] could escape baseDir.
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return false
        return f.isFile && f.delete()
    }

    /** Wipes every snapshot. Returns the count removed. */
    fun clear(): Int {
        val files = list()
        var n = 0
        for (f in files) if (f.delete()) n++
        return n
    }

    /** Human label "3 files · 12 MB" used by About and snackbars. */
    fun describe(): String {
        val files = list()
        return "${files.size} file(s) · ${Format.size(files.sumOf { it.length() })}"
    }

    private fun uniqueDest(rawName: String): File {
        val safe = rawName.ifBlank { "untitled" }
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var candidate = File(baseDir, safe)
        var n = 1
        while (candidate.exists()) {
            candidate = File(baseDir, "$stem ($n)$ext")
            n++
        }
        return candidate
    }
}

/** Result of a local-only share batch. Same shape as Browse's OpResult so the ResultDialog UI can be reused. */
data class SharedResult(
    val copied: Int,
    val skipped: Int,
    val failures: List<String>,
) {
    val summary: String
        get() = when {
            copied == 0 && skipped == 0 -> "No files shared"
            skipped == 0 -> "$copied file(s) copied to the shared folder"
            else -> "$copied file(s) shared, $skipped skipped"
        }
}
