package com.toadfiles.app.domain

import java.io.File

/** Archive container formats toadFILES can CREATE (v1.7.3); extraction is wider (zip/7z). */
enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP("zip", "ZIP"),
    SEVEN_Z("7z", "7z"),
}

/**
 * Archive detection + dispatch shared by Browse, Recent and Categories (v1.7.1).
 *
 * One source of truth for "is this an archive toadFILES can act on", so every surface
 * (row tap, action sheets, selection menus) offers the same Extract actions:
 *  - .zip: extract (hardened [ZipSupport.extract]) AND compress-to-zip.
 *  - .7z:  extract (hardened [SevenZipSupport.extract]) AND, since v1.7.3,
 *    compress-to-7z through the equally hardened [SevenZipSupport.create7z].
 * Other archive-ish extensions (rar, tar, gz, …) keep their old behaviour: generic file.
 *
 * v1.7.3: [compress] routes to the format's pipeline with the shared [CompressLevel]
 * chooser (Deflater levels for zip, LZMA2 presets / COPY for 7z).
 *
 * v1.8.0: [compress]/[extract] accept a password. Only the 7z pipeline uses it
 * (AES-256 data-only encryption, [SevenZipSupport.create7z]); the zip pipeline takes
 * the parameter for signature symmetry but keeps declining encrypted zips — and
 * [extract] reports [OpResult.passwordRequired] when an encrypted 7z is opened without
 * a usable password, so the UI can prompt and retry.
 */
object ArchiveOps {

    /** Extensions toadFILES can extract. */
    private val EXTRACTABLE = setOf("zip", "7z")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** True when tapping/long-pressing this name should offer archive (Extract) actions. */
    fun canExtract(name: String): Boolean = extension(name) in EXTRACTABLE

    /** True when this name is itself an archive of a format we can create. */
    fun isArchive(name: String): Boolean = extension(name) == "zip" || extension(name) == "7z"

    /** Total entry count when it is known upfront (zip central directory, 7z header),
     *  for a determinate progress bar before the op starts; null when unknown. */
    fun entryCount(archive: File): Int? = when (extension(archive.name)) {
        "zip" -> runCatching { java.util.zip.ZipFile(archive).use { it.size() } }.getOrNull()
        "7z" -> SevenZipSupport.entryCount(archive)
        else -> null
    }

    /** Extracts [archive] into [targetDir] with the format's hardened pipeline.
     *  [password] is used by the 7z pipeline for AES-256 encrypted archives. */
    suspend fun extract(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        password: CharArray? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = when (extension(archive.name)) {
        "zip" -> ZipSupport.extract(archive, targetDir, replace, limits, password, onProgress)
        "7z" -> SevenZipSupport.extract(archive, targetDir, replace, limits, password, onProgress)
        else -> OpResult(0, 0, 1, false, listOf("${archive.name}: unsupported archive type"))
    }

    /** Compresses [sources] into [outFile] with the chosen [format] and [level] (v1.7.3).
     *  v1.8.0: [password] encrypts 7z archives (AES-256, data-only); the zip pipeline
     *  ignores it — encrypted zip creation isn't supported. */
    suspend fun compress(
        sources: List<File>,
        outFile: File,
        format: ArchiveFormat,
        level: CompressLevel = CompressLevel.NORMAL,
        password: CharArray? = null,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = when (format) {
        ArchiveFormat.ZIP -> ZipSupport.zip(sources, outFile, level, onProgress)
        ArchiveFormat.SEVEN_Z -> SevenZipSupport.create7z(
            sources, outFile, level, password = password, onProgress = onProgress,
        )
    }

    /** Zip creation kept for the v1.7.1–1.7.2 call sites; new code uses [compress]. */
    suspend fun zip(
        sources: List<File>,
        outFile: File,
        level: CompressLevel = CompressLevel.NORMAL,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = ZipSupport.zip(sources, outFile, level, onProgress)
}
