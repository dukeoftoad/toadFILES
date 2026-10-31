package com.toadfiles.app.domain

/**
 * Semantic-ish version comparison for release tags against the installed version
 * ([com.toadfiles.app.BuildConfig.VERSION_NAME]). Handles the shapes GitHub tags
 * actually use:
 *  - an optional leading "v" ("v1.7.0" == "1.7.0"),
 *  - dot-separated numeric components ("1.6.1"), with 2-component tags treated as
 *    padded ("1.6" == "1.6.0"),
 *  - "-" pre-release segments split like any other component, and a MISSING suffix
 *    means the stable release, so "1.7.0-beta" < "1.7.0".
 *
 * Components compare numerically (1.10.0 > 1.9.0), never lexicographically.
 * Pure Kotlin — no Android types — so the whole thing is unit-testable on the JVM.
 */
object VersionCompare {

    /** True only when [candidate] is strictly newer than [current]. */
    fun isNewer(current: String, candidate: String): Boolean = compare(current, candidate) < 0

    /**
     * Component-wise comparison: negative when [a] < [b], positive when [a] > [b],
     * 0 when equivalent ("1.6" vs "1.6.0"). A string with no numeric components at
     * all ("", "v", "blob") parses as component 0 — which deliberately makes it never
     * "newer" than any real release, so a malformed tag can never push an update.
     */
    fun compare(a: String, b: String): Int {
        val left = parse(a) ?: return 0
        val right = parse(b) ?: return 0
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { MISSING }
            val r = right.getOrElse(i) { MISSING }
            if (l.number != r.number) return l.number.compareTo(r.number)
            val ls = l.suffix
            val rs = r.suffix
            if (ls != rs) {
                if (ls == null) return 1 // stable (no suffix) outranks its own pre-releases
                if (rs == null) return -1
                return ls.compareTo(rs)
            }
        }
        return 0
    }

    private data class Component(val number: Long, val suffix: String?)

    private val MISSING = Component(0, null)

    private fun parse(version: String): List<Component>? {
        val cleaned = version.trim().trimStart('v', 'V')
        if (cleaned.isEmpty()) return null
        return cleaned.split('.', '-').map { part ->
            Component(
                number = part.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L,
                suffix = part.dropWhile { it.isDigit() }.ifEmpty { null },
            )
        }
    }
}
