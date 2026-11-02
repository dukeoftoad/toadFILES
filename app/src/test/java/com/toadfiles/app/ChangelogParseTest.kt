package com.toadfiles.app

import com.toadfiles.app.ui.whatsnew.Changelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Keep-a-Changelog parser behind the What's New page, exercised against fixtures on
 * the plain JVM (no Android types involved). The bundled-asset test reads the generated
 * app/src/main/assets/changelog.md straight from disk — the same file the APK ships —
 * like AppIconSwitcherTest reads the manifest source, and additionally pins the newest
 * changelog entry to the version the build is producing.
 */
class ChangelogParseTest {

    /** The repo-root changelog as the bundling task ships it (never hand-edited). */
    private val bundledAsset: File by lazy {
        listOf(File("src/main/assets/changelog.md"), File("app/src/main/assets/changelog.md"))
            .firstOrNull { it.exists() }
            ?: error(
                "changelog.md is missing — the Gradle syncChangelog task generates it " +
                    "before preBuild; run tests through Gradle."
            )
    }

    @Test
    fun `parse extracts releases, sections and items in order`() {
        val releases = Changelog.parse(
            """
            # Changelog

            ## [2.0.0] — 2027-01-05

            ### Added
            - First added item.
            - Second added item.

            ### Fixed
            - The only fix.

            ## [1.0.0] - 2026-01-01

            ### Changed
            - Older change.
            """.trimIndent()
        )

        assertEquals(listOf("2.0.0", "1.0.0"), releases.map { it.version })
        assertEquals("2027-01-05", releases[0].date)
        assertEquals(listOf("Added", "Fixed"), releases[0].sections.map { it.title })
        assertEquals(
            listOf("First added item.", "Second added item."),
            releases[0].sections[0].items,
        )
        assertEquals(listOf("The only fix."), releases[0].sections[1].items)
        assertEquals(listOf("Changed"), releases[1].sections.map { it.title })
    }

    @Test
    fun `unreleased section, document title and link footers are skipped`() {
        val releases = Changelog.parse(
            """
            # Changelog

            All notable changes live here.

            ## [Unreleased]

            ### Added
            - Work in progress, not a release.

            ## [1.2.3] — 2026-03-04

            ### Fixed
            - A real fix.

            [1.2.3]: https://github.com/dukeoftoad/toadFILES/compare/v1.2.2...v1.2.3
            [1.2.2]: https://github.com/dukeoftoad/toadFILES/compare/v1.2.1...v1.2.2
            """.trimIndent()
        )

        assertEquals(listOf("1.2.3"), releases.map { it.version })
        assertEquals(listOf("A real fix."), releases.single().allItems)
    }

    @Test
    fun `missing dates are tolerated and empty releases are skipped`() {
        val releases = Changelog.parse(
            """
            ## [2.0.0]

            ### Added
            - No date after the brackets.

            ## [1.9.9] —

            ### Fixed
            - Dangling dash, still no date.

            ## [1.9.8] — 2026-02-02

            Release prose without any bullet sections.
            """.trimIndent()
        )

        assertEquals(listOf("2.0.0", "1.9.9"), releases.map { it.version })
        assertTrue(releases.all { it.date == null })
        // 1.9.8 has no items, so it carries no signal for the What's New page.
        assertTrue(releases.none { it.version == "1.9.8" })
    }

    @Test
    fun `wrapped bullets fold into one item and markdown flattens to plain text`() {
        val releases = Changelog.parse(
            """
            ## [1.0.0] — 2026-01-01

            ### Added
            - **App icon picker**: 5 new designs (toad face, document,
              crowned toad) with `syncChangelog` and a [link](https://example.com)
              to the docs.
            """.trimIndent()
        )

        val item = releases.single().allItems.single()
        assertEquals(
            "App icon picker: 5 new designs (toad face, document, crowned toad) " +
                "with syncChangelog and a link to the docs.",
            item,
        )
    }

    @Test
    fun `bullets before any section land in a Changes section`() {
        val releases = Changelog.parse(
            """
            ## [0.9.0] — 2025-12-31

            - Straight bullet, no heading above it.
            """.trimIndent()
        )

        val release = releases.single()
        assertEquals(listOf("Changes"), release.sections.map { it.title })
        assertEquals(listOf("Straight bullet, no heading above it."), release.allItems)
    }

    @Test
    fun `unparsable markdown yields an empty list`() {
        assertTrue(Changelog.parse("").isEmpty())
        assertTrue(Changelog.parse("just some prose, no headings").isEmpty())
        assertTrue(Changelog.parse("## Not a changelog heading\n\nrandom text").isEmpty())
    }

    @Test
    fun `bundled asset parses and its newest release matches the installed version`() {
        val releases = Changelog.parse(bundledAsset.readText())
        assertTrue(
            "the bundled changelog must contain at least one release",
            releases.isNotEmpty(),
        )
        // Guards the release process: bumping versionName without tagging a matching
        // CHANGELOG.md entry (or vice versa) turns this test red.
        assertEquals(
            BuildConfig.VERSION_NAME,
            releases.first().version.removePrefix("v"),
        )
        assertNotNull(releases.first().date)
    }
}
