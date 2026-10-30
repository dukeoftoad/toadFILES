package com.toadfiles.app

import com.toadfiles.app.util.UriGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The allowlist behind FileProvider hand-offs (audit P1.1): file_paths.xml keeps a
 * root-path catch-all for removable volumes, so only canonical paths under real
 * shared-storage volumes may ever become share URIs. A symlink under an allowed
 * root that points elsewhere must be evaluated by its TARGET, not its link path.
 */
class UriGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `system and internal locations are rejected`() {
        assertFalse(UriGuard.isShareablePath("/data/data/x"))
        assertFalse(UriGuard.isShareablePath("/proc/1/cmdline"))
        assertFalse(UriGuard.isShareablePath("/sys/fs"))
        assertFalse(UriGuard.isShareablePath("/dev/null"))
        assertFalse(UriGuard.isShareablePath("/system/lib"))
        assertFalse(UriGuard.isShareablePath("/vendor/etc"))
        assertFalse(UriGuard.isShareablePath("/apex/com.android.runtime"))
        assertFalse(UriGuard.isShareablePath("/product/app"))
    }

    @Test
    fun `user-visible storage volumes stay shareable`() {
        assertTrue(UriGuard.isShareablePath("/storage/emulated/0/Download/f.txt"))
        assertTrue(UriGuard.isShareablePath("/storage/emulated/10/DCIM/photo.jpg"))
        // SD / OTG volumes: /storage/XXXX-XXXX. Non-existent paths canonicalize
        // to themselves, so the policy can be tested without the volume present.
        assertTrue(UriGuard.isShareablePath("/storage/0000-0001/DCIM/photo.jpg"))
    }

    @Test
    fun `app-private trees under shared storage are rejected`() {
        assertFalse(UriGuard.isShareablePath("/storage/emulated/0/Android/data"))
        assertFalse(UriGuard.isShareablePath("/storage/emulated/0/Android/data/com.other/files/x"))
        assertFalse(UriGuard.isShareablePath("/storage/emulated/0/Android/obb/com.game/main.1.com.game.obb"))
    }

    @Test
    fun `dot-segment aliases cannot smuggle denied targets`() {
        // ".." must climb far enough to leave /storage entirely — the guard sees
        // the CANONICAL target, so these resolve to /data/… and /proc/… and are refused.
        assertFalse(UriGuard.isShareablePath("/storage/emulated/0/../../../data/data/x"))
        assertFalse(UriGuard.isShareablePath("/storage/../proc/1/cmdline"))
    }

    @Test
    fun `symlink under shared storage pointing at a denied target is refused by its real path`() {
        // Build a miniature "/storage" tree in temp: the LINK lives under an allowed
        // root, its TARGET outside — canonicalization must evaluate the target.
        val storage = tmp.newFolder("storage", "emulated", "0", "Download")
        val secret = tmp.newFolder("secret")
        val link = File(storage, "steal-me.txt")
        Files.createSymbolicLink(link.toPath(), File(secret, "private.txt").toPath())
        // The guard sees the link's canonical target (under the JVM temp dir, which
        // is NOT device shared storage), so it must refuse.
        assertFalse(UriGuard.isShareablePath(link.absolutePath))

        // Same check via the pure policy function on the resolved path.
        val resolved = link.canonicalPath
        assertFalse(UriGuard.isShareableCanonicalPath(resolved))
    }

    @Test
    fun `paths outside device storage are rejected on a plain JVM`() {
        // Under plain JUnit (no Robolectric) the temp-dir widening must NOT apply.
        val outside = tmp.newFile("plain.txt")
        assertFalse(UriGuard.isShareablePath(outside.absolutePath))
    }

    @Test
    fun `blank and uncanonicalizable paths are rejected`() {
        assertFalse(UriGuard.isShareablePath(""))
        assertFalse(UriGuard.isShareablePath("   "))
    }
}
