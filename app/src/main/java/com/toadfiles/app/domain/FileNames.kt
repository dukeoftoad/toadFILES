package com.toadfiles.app.domain

/**
 * Longest dot-tail we still treat as a real extension when a file name has to be
 * truncated for display. Real extensions are short (.pdf, .webp, .docx); a longer
 * dot-tail is more likely a date or version stamp ("backup.2026-11-08"), which
 * reads better truncated like the rest of the name than pinned to the end.
 */
const val MAX_DISPLAY_EXTENSION_LENGTH = 8

/**
 * The two halves of a file name for display purposes: [base] may be ellipsized
 * when the row runs out of room, while [extension] (dot included, "" when the
 * name has no usable extension) must always stay fully visible so the file type
 * survives truncation ("year_end_report_202….pdf", never "year_end_report_20…").
 */
data class FileNameParts(val base: String, val extension: String)

/**
 * Splits [name] into its truncatable base and always-visible extension.
 * Deliberately boring rules:
 *  - the LAST dot starts the extension, so "archive.tar.gz" pins ".gz";
 *  - a leading dot marks a hidden file, not an extension (".gitignore" has none);
 *  - a trailing dot ("file.") and over-long dot-tails stay part of the name;
 *  - empty names and folder-style names without dots split trivially.
 */
fun splitFileName(name: String): FileNameParts {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return FileNameParts(name, "")
    val ext = name.substring(dot)
    if (ext.length > MAX_DISPLAY_EXTENSION_LENGTH) return FileNameParts(name, "")
    return FileNameParts(name.substring(0, dot), ext)
}
