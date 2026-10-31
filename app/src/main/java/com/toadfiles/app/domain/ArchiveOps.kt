package com.toadfiles.app.domain

import java.io.File

/**
 * Archive detection + dispatch shared by Browse, Recent and Categories (v1.7.1).
 *
 * One source of truth for "is this an archive toadFILES can act on", so every surface
 * (row tap, action sheets, selection menus) offers the same Extract actions:
 *  - .zip: extract (hardened [ZipSupport.extract]) AND compress-to-zip.
 *  - .7z:  extract-only (hardened [SevenZipSupport.extract]) — there is deliberately no
 *    "compress to 7z" option in v1.7.1.
 * Other archive-ish extensions (rar, tar, gz, …) keep their old behaviour: generic file.
 */
object ArchiveOps {

    /** Extensions toadFILES can extract. */
    private val EXTRACTABLE = setOf("zip", "7z")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** True when tapping/long-pressing this name should offer archive (Extract) actions. */
    fun canExtract(name: String): Boolean = extension(name) in EXTRACTABLE

    /** Total entry count when it is known upfront (zip central directory, 7z header),
     *  for a determinate progress bar before the op starts; null when unknown. */
    fun entryCount(archive: File): Int? = when (extension(archive.name)) {
        "zip" -> runCatching { java.util.zip.ZipFile(archive).use { it.size() } }.getOrNull()
        "7z" -> SevenZipSupport.entryCount(archive)
        else -> null
    }

    /** Extracts [archive] into [targetDir] with the format's hardened pipeline. */
    suspend fun extract(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits = ZipSupport.Limits(),
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = when (extension(archive.name)) {
        "zip" -> ZipSupport.extract(archive, targetDir, replace, limits, onProgress)
        "7z" -> SevenZipSupport.extract(archive, targetDir, replace, limits, onProgress)
        else -> OpResult(0, 0, 1, false, listOf("${archive.name}: unsupported archive type"))
    }

    /** Compression stays on the zip pipeline (7z is extract-only in v1.7.1). */
    suspend fun zip(
        sources: List<File>,
        outFile: File,
        onProgress: (OpProgress) -> Unit = {},
    ): OpResult = ZipSupport.zip(sources, outFile, onProgress)
}
