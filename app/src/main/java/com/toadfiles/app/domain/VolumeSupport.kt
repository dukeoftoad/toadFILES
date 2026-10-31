package com.toadfiles.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.Enumeration

/**
 * Split-into-volumes support (v1.8.0) — the ZArchiver-style "Split into volumes" domain.
 *
 * A volume set is a byte-exact cut of ONE archive into sibling files named
 * "<archive>.001", "<archive>.002", … Only the ".001" file is a volume *start*:
 * every consumer (action sheets, [ArchiveOps.extract]) enters the set through it,
 * and the set is always walked from there, never from ".002".
 *
 * Contracts kept deliberately simple and honest:
 *  - Detection never throws: [volumeSet] on a missing ".001" (or any gap) just
 *    returns the shorter contiguous run, possibly empty.
 *  - [split] is atomic-ish like the rest of the app: every volume is written as a
 *    hidden ".part-" temp first and renamed onto its final name only when complete;
 *    the original archive is deleted ONLY after every volume is live. Any failure
 *    or cancellation deletes the partial volumes and keeps the original.
 */
object VolumeSupport {

    private const val BUFFER_SIZE = 64 * 1024

    // ---------- naming / detection ----------

    /**
     * True for the first volume of a set: names ending in ".001" with at least one
     * character before the dot ("DCIM.7z.001" → true; "DCIM.7z.002", "a.zip",
     * a bare dot-file ".001" → false).
     */
    fun isVolumeStart(name: String): Boolean =
        name.length > 4 && name.endsWith(".001")

    /** The [name] with the volume suffix removed ("DCIM.7z.001" → "DCIM.7z"). */
    fun baseName(name: String): String = name.removeSuffix(".001")

    /** [baseName] of [first]'s name ("DCIM.7z.001" → "DCIM.7z"). */
    fun archiveName(first: File): String = baseName(first.name)

    /**
     * The contiguous run starting at [first]: ".001", ".002", ".003"… stopping at
     * the first missing number. Never throws for missing files; a set of just
     * [".001"] is a valid (single-volume) set, and a non-start or unusable ".001"
     * yields an empty list.
     */
    fun volumeSet(first: File): List<File> {
        if (!isVolumeStart(first.name) || !first.isFile) return emptyList()
        val parent = first.parentFile ?: return listOf(first)
        val base = baseName(first.name)
        val volumes = mutableListOf(first)
        var index = 2
        while (true) {
            val next = File(parent, "$base.${"%03d".format(index)}")
            if (!next.isFile) break
            volumes += next
            index++
        }
        return volumes
    }

    /**
     * Gap detector for extraction: the number of the first volume the contiguous
     * run from [first] is missing WHILE higher-numbered volumes exist on disk
     * (".001,.002,.004" → 3; ".001,.003" → 2; ".001" absent → 1). Null when the
     * set is complete (the run reaches the highest volume that exists).
     */
    fun firstMissingVolume(first: File): Int? {
        if (!isVolumeStart(first.name)) return null
        if (!first.isFile) return 1
        val run = volumeSet(first)
        val highestContiguous = run.size
        val parent = first.parentFile ?: return null
        val base = baseName(first.name)
        val pattern = Regex("^${Regex.escape(base)}\\.(\\d{3,})$")
        val hasHigher = parent.listFiles()?.any { sibling ->
            sibling.isFile && sibling !== first &&
                pattern.matchEntire(sibling.name)?.let { match ->
                    match.groupValues[1].toLong() > highestContiguous
                } == true
        } ?: false
        return if (hasHigher) highestContiguous + 1 else null
    }

    // ---------- splitting ----------

    /**
     * Stream-copies [archive] into siblings "name.001", "name.002", … — every
     * volume exactly [bytesPerVolume] bytes except the last, which is smaller.
     * An empty archive becomes a single empty ".001".
     *
     * Runs on Dispatchers.IO and is cancellation-safe (a checkpoint per buffer and
     * per volume). Publication follows the app's atomic-ish convention: each volume
     * is first written as a hidden ".part-" temp, then renamed onto its final name;
     * the original archive is deleted ONLY after every volume is live. Any failure
     * or cancellation deletes the temps and the already-published volumes and keeps
     * the original — rethrows the original exception ([IllegalArgumentException] for
     * a non-positive [bytesPerVolume], [IOException] otherwise, including
     * CancellationException propagation).
     */
    suspend fun split(archive: File, bytesPerVolume: Long): List<File> = withContext(Dispatchers.IO) {
        if (bytesPerVolume <= 0) throw IllegalArgumentException("bytesPerVolume must be positive")
        if (!archive.isFile) throw IOException("${archive.name}: not a readable file")
        val parent = archive.parentFile ?: File(".")
        val total = archive.length()
        val count = if (total == 0L) 1L else (total + bytesPerVolume - 1) / bytesPerVolume
        val volumes = mutableListOf<File>()
        val pendingTemps = mutableListOf<File>()
        val published = mutableListOf<File>()
        try {
            FileInputStream(archive).use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                for (index in 1L..count) {
                    currentCoroutineContext().ensureActive()
                    val finalFile = File(parent, volumeName(archive.name, index))
                    val tmp = File(parent, ".${finalFile.name}.part-${System.nanoTime()}")
                    pendingTemps += tmp
                    var remaining = bytesPerVolume
                    FileOutputStream(tmp).use { out ->
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val want = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                            val n = input.read(buf, 0, want)
                            if (n == -1) throw IOException("${archive.name}: shrank while splitting")
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (finalFile.exists() && !finalFile.delete()) {
                        throw IOException("could not replace ${finalFile.name}")
                    }
                    if (!tmp.renameTo(finalFile)) throw IOException("could not create ${finalFile.name}")
                    pendingTemps -= tmp
                    published += finalFile
                    volumes += finalFile
                }
            }
            if (!archive.delete()) throw IOException("could not remove ${archive.name} after splitting")
            volumes
        } catch (t: Throwable) {
            // Never lose data: the original is intact, so junk temps and partial
            // volumes (published or not) all go away.
            (pendingTemps + published).forEach { partial -> runCatching { partial.delete() } }
            throw t
        }
    }

    private fun volumeName(archiveName: String, index: Long): String =
        "$archiveName.${"%03d".format(index)}"

    // ---------- reading a set as one stream ----------

    /** Sum of the live volumes' sizes (0 for an unusable/missing start). */
    fun totalSize(first: File): Long = volumeSet(first).sumOf { it.length() }

    /**
     * Sequential [InputStream] over the whole set, concatenating the volumes in
     * order. Each volume's [FileInputStream] is opened lazily (only when the
     * previous volume is exhausted), so a huge set doesn't hold one FD per volume.
     * A volume that vanishes mid-read surfaces as an [IOException] from the read.
     */
    fun inputStream(first: File): InputStream =
        SequenceInputStream(LazyVolumeEnumeration(volumeSet(first)))

    private class LazyVolumeEnumeration(private val files: List<File>) : Enumeration<InputStream> {
        private var next = 0
        override fun hasMoreElements(): Boolean = next < files.size
        override fun nextElement(): InputStream = FileInputStream(files[next++])
    }

    /**
     * Read-only [SeekableByteChannel] spanning the whole contiguous set: positions
     * 0..totalSize-1 map onto the volumes, [size] is the sum, and reads cross
     * volume boundaries transparently. write()/truncate() throw
     * [NonWritableChannelException]. This is what lets commons-compress's SevenZFile
     * open a multi-volume 7z set (SevenZFile accepts a SeekableByteChannel).
     */
    fun seekableChannel(first: File): SeekableByteChannel = VolumeSeekableChannel(volumeSet(first))

    private class VolumeSeekableChannel(files: List<File>) : SeekableByteChannel {
        private val channels = ArrayList<FileChannel>(files.size)
        private val offsets = LongArray(files.size)
        private var totalSize = 0L
        private var pos = 0L
        private var open = true

        init {
            try {
                for (f in files) channels += FileChannel.open(f.toPath(), StandardOpenOption.READ)
                var acc = 0L
                for (i in channels.indices) {
                    offsets[i] = acc
                    acc += channels[i].size()
                }
                totalSize = acc
            } catch (t: Throwable) {
                channels.forEach { runCatching { it.close() } }
                throw t
            }
        }

        private fun indexFor(p: Long): Int {
            var lo = 0
            var hi = channels.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (p < offsets[mid + 1]) hi = mid else lo = mid + 1
            }
            return lo
        }

        override fun read(dst: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            if (!dst.hasRemaining()) return 0
            if (pos >= totalSize) return -1
            var total = 0
            while (dst.hasRemaining() && pos < totalSize) {
                val idx = indexFor(pos)
                val ch = channels[idx]
                ch.position(pos - offsets[idx])
                val n = ch.read(dst)
                if (n < 0) break // a volume truncated underneath us: surface EOF
                total += n
                pos += n
            }
            return if (total == 0) -1 else total
        }

        override fun position(): Long = pos

        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0) { "negative position" }
            pos = newPosition
            return this
        }

        override fun size(): Long = totalSize

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

        override fun isOpen(): Boolean = open

        override fun close() {
            if (!open) return
            open = false
            channels.forEach { runCatching { it.close() } }
        }
    }
}
