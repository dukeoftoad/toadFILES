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

// ---------- split-into-volumes contract (audit A23) ----------
// The volume-size contract was defined in the compress dialog's file while its
// siblings (ArchiveFormat, CompressLevel) live here — ViewModels ended up importing
// a ui.components type for a pure domain value. Moved next to its siblings; the
// dialog keeps only its own UI copy (labels list, confirm-enabled rule).

/** One megabyte as the volume selector counts it (binary, like the rest of the app). */
const val VOLUME_MB: Long = 1024L * 1024

/**
 * "Split into volumes" options, ZArchiver-style: No, fixed sizes, or Custom…
 * ([CUSTOM] reveals a megabytes field; the byte mapping lives in [customVolumeTextToBytes]).
 */
enum class VolumeChoice(val label: String, val bytes: Long?) {
    NONE("No", null),
    MB5("5 MB", 5 * VOLUME_MB),
    MB10("10 MB", 10 * VOLUME_MB),
    MB50("50 MB", 50 * VOLUME_MB),
    MB100("100 MB", 100 * VOLUME_MB),
    MB700("700 MB", 700 * VOLUME_MB),
    GB1("1 GB", 1024 * VOLUME_MB),
    CUSTOM("Custom…", null);

    override fun toString(): String = label
}

/** Maps the stored (bytes, custom) pair back to the selected option for the dropdown. */
fun volumeChoiceFor(bytes: Long?, custom: Boolean): VolumeChoice =
    if (custom) VolumeChoice.CUSTOM
    else VolumeChoice.entries.firstOrNull { it != VolumeChoice.CUSTOM && it.bytes == bytes }
        ?: VolumeChoice.NONE

/**
 * Custom volume size text (megabytes) → bytes; null when blank, non-numeric or
 * non-positive (an invalid custom size means "no split", never a blocked OK button).
 */
fun customVolumeTextToBytes(text: String): Long? {
    val mb = text.trim().toLongOrNull() ?: return null
    return if (mb <= 0) null else mb * VOLUME_MB
}
