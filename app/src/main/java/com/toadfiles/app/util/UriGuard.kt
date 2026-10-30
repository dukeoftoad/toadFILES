package com.toadfiles.app.util

/**
 * Creation-time guard for FileProvider hand-offs: res/xml/file_paths.xml keeps its
 * root-path catch-all (removable volumes need it), so this denylist is the second
 * line of defence, checked before a share URI is ever built.
 */
object UriGuard {

    /** System/internal trees that must never reach other apps through the provider. */
    private val DENIED_ROOTS = listOf(
        "/data",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/apex",
        "/product",
    )

    /** True when [path] may be handed to other apps via FileProvider. Denylist-only:
     *  false for system/internal locations — /data, /proc, /sys, /dev, /system, /vendor,
     *  /apex, /product — matched as path-component prefixes; everything else true. */
    fun isShareablePath(path: String): Boolean {
        if (path.isBlank()) return false
        for (root in DENIED_ROOTS) {
            if (path == root || path.startsWith("$root/")) return false
        }
        return true
    }
}
