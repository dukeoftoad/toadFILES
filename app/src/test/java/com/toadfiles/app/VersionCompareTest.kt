package com.toadfiles.app

import com.toadfiles.app.domain.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven checks for the semantic-ish version comparison used to decide whether a
 * GitHub release tag is newer than the installed version. Pure JVM — [VersionCompare]
 * has no Android types. The rule under test: only a STRICTLY newer candidate may ever
 * trigger the update panel.
 */
class VersionCompareTest {

    private fun assertNewer(current: String, candidate: String, expected: Boolean) {
        assertEquals(
            "isNewer(current=\"$current\", candidate=\"$candidate\")",
            expected,
            VersionCompare.isNewer(current, candidate),
        )
    }

    @Test
    fun `equal versions are never newer`() {
        assertNewer("1.6.1", "1.6.1", false)
        assertNewer("1.6", "1.6.0", false)
        assertNewer("v1.6.1", "1.6.1", false)
    }

    @Test
    fun `patch minor and major bumps are newer`() {
        assertNewer("1.6.1", "1.6.2", true) // patch
        assertNewer("1.6.1", "1.7.0", true) // minor
        assertNewer("1.6.1", "2.0.0", true) // major
    }

    @Test
    fun `older candidates are not newer`() {
        assertNewer("1.6.1", "1.6.0", false)
        assertNewer("1.6.1", "1.5.9", false)
        assertNewer("2.0.0", "1.9.9", false)
    }

    @Test
    fun `components compare numerically not lexicographically`() {
        assertNewer("1.6.10", "1.6.9", false) // 9 < 10 as numbers
        assertNewer("1.9.0", "1.10.0", true) // 10 > 9 as numbers
    }

    @Test
    fun `v prefix on either side is ignored`() {
        assertNewer("1.6.1", "v1.7.0", true)
        assertNewer("V1.6.1", "v1.7.0", true)
        assertNewer("v1.6.1", "1.7.0", true)
        assertNewer("v1.7.0", "1.7.0", false)
    }

    @Test
    fun `two-component tags pad to three`() {
        assertNewer("1.6.1", "1.6", false) // "1.6" == 1.6.0 -> older
        assertNewer("1.6.1", "1.7", true) // "1.7" == 1.7.0 -> newer
        assertNewer("1.6", "1.7.0", true)
        assertNewer("1.6", "1.7", true)
    }

    @Test
    fun `pre-release suffixes rank below the stable release`() {
        assertNewer("1.7.0", "1.7.0-beta", false) // beta of the same version: not an update
        assertNewer("1.7.0-beta", "1.7.0", true) // stable outranks its own beta — a beta user gets the stable
        assertNewer("1.6.1", "1.7.0-beta", true) // pre-release still beats an older stable
        assertNewer("1.6.1", "1.7.0-rc1", true)
        assertNewer("1.6.1", "1.7.0-alpha", true)
    }

    @Test
    fun `suffixes compare alphabetically when numeric parts tie`() {
        assertNewer("1.7.0-alpha", "1.7.0-beta", true)
        assertNewer("1.7.0-beta", "1.7.0-rc", true)
    }

    @Test
    fun `garbage candidates are never newer`() {
        assertNewer("1.6.1", "", false)
        assertNewer("1.6.1", "v", false)
        assertNewer("1.6.1", "blob", false)
        assertNewer("1.6.1", "release", false)
    }

    @Test
    fun `compare is antisymmetric and consistent`() {
        assertEquals(0, VersionCompare.compare("1.6", "1.6.0"))
        assertEquals(-VersionCompare.compare("1.6.1", "1.7.0"), VersionCompare.compare("1.7.0", "1.6.1"))
    }
}
