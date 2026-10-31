package com.toadfiles.app.domain

/**
 * v1.7.3 preference contract shared by Settings UI, archive flows and navigation.
 * UI (Agent 1) builds on these; archive flows (Agents 2/3/5) consume them.
 * Defaults preserve existing v1.7.2 behavior everywhere.
 */

/** What happens when the user taps a .zip archive file. */
enum class ZipOpenAction { MENU, EXTRACT, OPEN_WITH }

/** What happens when the user taps a .7z archive file. */
enum class SevenZipOpenAction { MENU, EXTRACT, OPEN_WITH }

/** Which of the two shipped navigation UX styles is active ("two UX designs"). */
enum class NavStyle { BOTTOM_BAR, SIDE_MENU }

/** What the system back key does inside the Browse tab. */
enum class BackKeyAction { PARENT, EXIT }

/**
 * Compression level offered by both zip and 7z creation — the six ZArchiver levels
 * (v1.8.0). Mapped to Deflater level (zip) / LZMA2 preset (7z) by the archive pipeline.
 * Not persisted in settings, so renaming the entries is a clean break.
 */
enum class CompressLevel(val label: String) {
    NONE("No compression"),
    FASTEST("Fastest"),
    FAST("Fast"),
    NORMAL("Normal"),
    MAXIMUM("Maximum"),
    ULTRA("Ultra"),
}
