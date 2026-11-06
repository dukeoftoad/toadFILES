package com.toadfiles.app.domain

import java.io.File
import java.nio.channels.SeekableByteChannel

/**
 * Lite-edition 7z seam (docs/LITE_EDITION_PLAN.md §3 phase 2): lite declares ZIP-only,
 * so commons-compress + xz are not on the lite classpath at all (~1 MB of dex saved).
 * Every entry point answers with an honest failed [OpResult] — the same shape a real
 * 7z failure takes — so a mislabeled .7z that slips past the [ArchiveOps.canExtract]
 * gate still reports "unsupported" instead of crashing. Nothing here can throw.
 */
internal object SevenZipImpl {

    private const val REASON = "7z support is not included in toadFILES Lite"

    fun entryCount(archive: File): Int? = null

    fun volumeEntryCount(archive: File): Int? = null

    suspend fun extract(
        archive: File,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = OpResult(0, 0, 1, false, listOf("${archive.name}: $REASON"))

    suspend fun extract(
        channel: SeekableByteChannel,
        channelSizeHint: Long,
        targetDir: File,
        replace: Boolean,
        limits: ZipSupport.Limits,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = OpResult(0, 0, 1, false, listOf(REASON))

    suspend fun create7z(
        sources: List<File>,
        target: File,
        level: CompressLevel,
        password: CharArray?,
        onProgress: (OpProgress) -> Unit,
    ): OpResult = OpResult(0, 0, 1, false, listOf("$REASON — pick the ZIP format"))
}
