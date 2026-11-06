package com.toadfiles.app.domain

import java.io.File
import java.nio.channels.SeekableByteChannel

/**
 * Full-edition 7z pipeline (docs/LITE_EDITION_PLAN.md §3 phase 2): ArchiveOps talks to
 * [SevenZipImpl] only, and this flavor wires it to the real hardened
 * [SevenZipSupport] engine (commons-compress + xz live on the full classpath only).
 * The lite flavor ships an equally-named object whose every entry point reports the
 * honest "not included in toadFILES Lite" failure — see src/lite.
 */
internal object SevenZipImpl {

    /** Entry count from the 7z header, or null when the archive can't be opened. */
    fun entryCount(archive: File): Int? = SevenZipSupport.entryCount(archive)

    /** Entry count of a volume START, read through a seekable channel; null on failure. */
    fun volumeEntryCount(archive: File): Int? = try {
        VolumeSupport.seekableChannel(archive).use { ch ->
            org.apache.commons.compress.archivers.sevenz.SevenZFile(ch).use { it.entries.toList().size }
        }
    } catch (_: Exception) {
        null
    }

    suspend fun extract(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = SevenZipSupport.extract(archive, targetDir, replace, limits, password, onProgress)

    suspend fun extract(
        channel: SeekableByteChannel,
        channelSizeHint: Long,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = SevenZipSupport.extract(channel, channelSizeHint, targetDir, replace, limits, password, onProgress)

    suspend fun create7z(
        sources: List<File>,
        target: File,
        level: CompressLevel,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = SevenZipSupport.create7z(sources, target, level, password = password, onProgress = onProgress)
}
