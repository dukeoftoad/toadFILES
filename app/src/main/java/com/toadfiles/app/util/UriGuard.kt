package com.toadfiles.app.util

import java.io.File

/**
 * Creation-time guard for FileProvider hand-offs: res/xml/file_paths.xml keeps its
 * root-path catch-all (removable volumes need it), so this ALLOWLIST is the second
 * line of defence, checked before a share URI is ever built (audit P1.1).
 *
 * The caller-supplied path is CANONICALIZED first — a lexical prefix check could be
 * bypassed by a symlink under shared storage pointing at /data, or by dot-segment
 * aliases. Only canonical paths inside real shared-storage volumes
 * (/storage/emulated/N, /storage/XXXX-XXXX) may be shared; app-private trees
 * (Android/data, Android/obb) on those volumes are refused as well.
 *
 * Robolectric runs the app's "external storage" inside the JVM temp dir, so under
 * Robolectric (and only there) that temp tree is additionally allowed; on a real
 * device nothing outside /storage ever passes.
 */
object UriGuard {

    /** Canonical roots of user-visible shared storage on a real device. */
    private const val SHARED_STORAGE_ROOT = "/storage"

    /** App-private trees under shared storage that must never reach other apps. */
    private val PRIVATE_TREES = listOf("Android/data", "Android/obb")

    /** True when the JVM is Robolectric — test-only widening of the allowlist. */
    private val isRobolectric: Boolean = try {
        android.os.Build.FINGERPRINT?.contains("robolectric", ignoreCase = true) == true
    } catch (_: Throwable) {
        // Plain JVM unit tests: android.os.Build is not mocked there.
        false
    }

    /**
     * True when [path] may be handed to other apps via FileProvider. The path is
     * canonicalized (symlinks and dot segments resolved) before policy evaluation,
     * so a link under shared storage that points into a private/system tree is
     * refused by its REAL target. Anything that cannot be canonicalized is refused.
     */
    fun isShareablePath(path: String): Boolean {
        if (path.isBlank()) return false
        val canonical = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            return false
        }
        return isShareableCanonicalPath(canonical)
    }

    /**
     * Pure policy check on an already-canonical path: shared-storage volumes only,
     * minus the app-private Android/data and Android/obb trees.
     */
    fun isShareableCanonicalPath(canonicalPath: String): Boolean {
        val p = canonicalPath.trimEnd('/')
        if (p.isEmpty()) return false
        val onDeviceStorage = p == SHARED_STORAGE_ROOT || p.startsWith("$SHARED_STORAGE_ROOT/")
        val onTestStorage = isRobolectric && run {
            val tmp = System.getProperty("java.io.tmpdir")?.trimEnd('/') ?: return@run false
            p == tmp || p.startsWith("$tmp/")
        }
        if (!onDeviceStorage && !onTestStorage) return false
        for (tree in PRIVATE_TREES) {
            if (p == "/$tree" || p.endsWith("/$tree") ||
                p.contains("/$tree/") || p.startsWith("/$tree/")
            ) return false
        }
        return true
    }
}
