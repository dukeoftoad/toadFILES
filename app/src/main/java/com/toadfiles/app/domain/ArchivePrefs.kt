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
 * Compression level offered by both zip and 7z creation (ZArchiver-style basics).
 * Mapped to Deflater level / LZMA2 preset by the archive pipeline.
 */
enum class CompressLevel(val label: String) {
    STORE("Store (no compression)"),
    FAST("Fast"),
    NORMAL("Normal (default)"),
    MAXIMUM("Maximum");
}
