package com.toadfiles.app

import com.toadfiles.app.util.UriGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The denylist behind FileProvider hand-offs: file_paths.xml keeps a root-path
 * catch-all for removable volumes, so system/internal locations must be refused
 * before a share URI is ever built. Prefix matching respects path-component
 * boundaries — "/data/data/x" is denied, "/database_app/f" is not.
 */
class UriGuardTest {

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
    fun `user-visible storage stays shareable`() {
        assertTrue(UriGuard.isShareablePath("/storage/emulated/0/Download/f.txt"))
        assertTrue(UriGuard.isShareablePath("/sdcard/f.txt"))
        // Robolectric's filesystem root lives under /tmp; tests share files from there.
        assertTrue(UriGuard.isShareablePath("/tmp/robolectric/x"))
    }

    @Test
    fun `matching respects component boundaries`() {
        // The denied root itself (with or without trailing slash) is denied...
        assertFalse(UriGuard.isShareablePath("/data"))
        assertFalse(UriGuard.isShareablePath("/data/"))
        // ...but longer names that merely start with the same characters are not.
        assertTrue(UriGuard.isShareablePath("/database_app/f"))
        assertTrue(UriGuard.isShareablePath("/systemd/run"))
        assertTrue(UriGuard.isShareablePath("/procedures/f.txt"))
        assertTrue(UriGuard.isShareablePath("/devices/list.txt"))
    }

    @Test
    fun `blank paths are rejected`() {
        assertFalse(UriGuard.isShareablePath(""))
        assertFalse(UriGuard.isShareablePath("   "))
    }
}
