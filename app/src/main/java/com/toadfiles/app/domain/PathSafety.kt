package com.toadfiles.app.domain

import java.io.File
import java.io.IOException

/**
 * Path-traversal and collision guards shared by file operations and ZIP extraction.
 * All functions are pure or read-only so they can be unit tested on the JVM.
 */
object PathSafety {

    /** True when [child] equals [ancestor] or lives underneath it (canonical comparison). */
    fun isInside(child: File, ancestor: File): Boolean {
        val c = child.canonicalFile
        val a = ancestor.canonicalFile
        if (c == a) return true
        var p: File? = c.parentFile
        while (p != null) {
            if (p == a) return true
            p = p.parentFile
        }
        return false
    }

    /** Guard against copying/moving a folder into itself or one of its descendants. */
    fun rejectSelfTarget(sources: List<File>, targetDir: File) {
        for (s in sources) {
            if (s.isDirectory && isInside(targetDir, s)) {
                throw IOException("Cannot move or copy \"${s.name}\" into itself or a subfolder.")
            }
        }
    }

    /**
     * Returns a non-colliding name inside [dir]: "report.pdf" -> "report (1).pdf", etc.
     * Race windows are handled by callers (an insert may still fail, which is reported honestly).
     */
    fun uniqueName(dir: File, name: String): String {
        val candidate = File(dir, name)
        if (!candidate.exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val next = "$base ($i)$ext"
            if (!File(dir, next).exists()) return next
            i++
        }
    }

    /**
     * Validates a ZIP entry name (audit P3.1 / §9.2 "ZIP filename validation").
     *
     * Rejects: blank names, NUL bytes, absolute paths, drive-letter / colon vectors
     * (also NTFS alternate-data streams), and any path component equal to ".."
     * (classic ZIP-slip, including backslash separators used by Windows-created archives).
     *
     * Harmless names that merely *contain* two dots ("release..notes.txt", "a..b/c")
     * remain valid: only whole ".." components escape the destination.
     */
    fun isSafeEntryName(entryName: String): Boolean {
        if (entryName.isBlank()) return false
        if (entryName.indexOf('\u0000') >= 0) return false
        val normalised = entryName.replace('\\', '/')
        if (normalised.startsWith("/")) return false // absolute path
        if (normalised.contains(':')) return false   // "C:\..." drives / "file:stream" vectors
        val components = normalised.split('/').dropLastWhile { it.isEmpty() }
        if (components.isEmpty() || components.all { it == "." }) return false // resolves to the root itself
        for (c in components) {
            if (c == "..") return false // parent traversal (ZIP-slip)
        }
        return true
    }

    /**
     * Secondary containment check using canonical paths at extraction time.
     * Canonicalisation resolves symlinks and ".." components, so both a hostile
     * entry name that slipped through validation and a pre-planted symlink are caught.
     */
    fun ensureInside(parent: File, candidate: File) {
        if (!isInside(candidate, parent)) {
            throw IOException("Blocked path traversal: ${candidate.path}")
        }
    }
}
