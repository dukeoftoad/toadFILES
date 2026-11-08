package com.toadfiles.app

import com.toadfiles.app.data.GithubReleaseChecker
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parsing of the real GitHub releases/latest payload shape with org.json (provided by
 * Robolectric on the JVM — the same implementation Android ships). Runs against a
 * realistic raw string, no network involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckerParsingTest {

    private val payload = """
        {
          "url": "https://api.github.com/repos/dukeoftoad/toadFILES/releases/17322461",
          "html_url": "https://github.com/dukeoftoad/toadFILES/releases/tag/v1.7.0",
          "tag_name": "v1.7.0",
          "target_commitish": "main",
          "name": "toadFILES 1.7.0",
          "body": "### Fixes\r\n- Faster unzip for large archives\r\n- New in-app update check (strictly user-initiated)\r\n",
          "draft": false,
          "prerelease": false,
          "created_at": "2026-02-01T10:00:00Z",
          "published_at": "2026-02-01T10:12:00Z",
          "assets": [
            {
              "name": "toadfiles-1.7.0-sources.zip",
              "content_type": "application/zip",
              "browser_download_url": "https://github.com/dukeoftoad/toadFILES/releases/download/v1.7.0/toadfiles-1.7.0-sources.zip",
              "size": 204800
            },
            {
              "name": "toadfiles-1.7.0.apk",
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/dukeoftoad/toadFILES/releases/download/v1.7.0/toadfiles-1.7.0.apk",
              "size": 7340032
            },
            {
              "name": "toadfiles-1.7.0-sha256.txt",
              "content_type": "text/plain",
              "browser_download_url": "https://github.com/dukeoftoad/toadFILES/releases/download/v1.7.0/toadfiles-1.7.0-sha256.txt",
              "size": 142
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parseRelease picks the first apk asset and the release fields`() {
        val release = GithubReleaseChecker.parseRelease(payload)

        assertEquals("v1.7.0", release.tagName)
        assertEquals("https://github.com/dukeoftoad/toadFILES/releases/tag/v1.7.0", release.htmlUrl)
        // The zip asset must be skipped in favor of the .apk one.
        assertEquals("toadfiles-1.7.0.apk", release.apkName)
        assertEquals(
            "https://github.com/dukeoftoad/toadFILES/releases/download/v1.7.0/toadfiles-1.7.0.apk",
            release.apkUrl,
        )
        assertEquals(7340032L, release.apkSizeBytes)
        // Notes keep the body text (CR-LF normalized to LF), markdown rendered as plain text.
        assertTrue(release.notes!!.startsWith("### Fixes"))
        assertTrue(release.notes!!.contains("strictly user-initiated"))
        assertTrue(!release.notes!!.contains("\r"))
    }

    @Test
    fun `parseRelease tolerates a release with no apk asset`() {
        val noApk = """
            {
              "tag_name": "v1.8.0",
              "html_url": "https://github.com/dukeoftoad/toadFILES/releases/tag/v1.8.0",
              "body": "Sources only.",
              "assets": [
                { "name": "sources.zip", "browser_download_url": "https://x/s.zip", "size": 1 }
              ]
            }
        """.trimIndent()

        val release = GithubReleaseChecker.parseRelease(noApk)

        assertEquals("v1.8.0", release.tagName)
        assertNull(release.apkName)
        assertNull(release.apkUrl)
        assertEquals(0L, release.apkSizeBytes)
    }

    // ---------- v2.5.0: edition-aware APK pick (two APKs per tag since v2.3.0) ----------

    /** A modern payload: both editions ship, lite listed FIRST (the order that bit). */
    private val twoApkPayload = """
        {
          "tag_name": "v2.5.0",
          "html_url": "https://github.com/dukeoftoad/toadFILES/releases/tag/v2.5.0",
          "body": "notes",
          "assets": [
            { "name": "toadfiles-lite-v2.5.0.apk", "browser_download_url": "https://x/lite.apk", "size": 3600000 },
            { "name": "toadfiles-v2.5.0.apk", "browser_download_url": "https://x/full.apk", "size": 4800000 },
            { "name": "toadfiles-v2.5.0-sha256.txt", "browser_download_url": "https://x/s.txt", "size": 142 }
          ]
        }
    """.trimIndent()

    @Test
    fun `full app skips the lite APK even when it is listed first`() {
        val release = GithubReleaseChecker.parseRelease(twoApkPayload, isLite = false)

        assertEquals("toadfiles-v2.5.0.apk", release.apkName)
        assertEquals("https://x/full.apk", release.apkUrl)
        assertEquals(4800000L, release.apkSizeBytes)
    }

    @Test
    fun `lite app picks the lite APK regardless of listing order`() {
        val release = GithubReleaseChecker.parseRelease(twoApkPayload, isLite = true)

        assertEquals("toadfiles-lite-v2.5.0.apk", release.apkName)
        assertEquals("https://x/lite.apk", release.apkUrl)
        assertEquals(3600000L, release.apkSizeBytes)
    }

    @Test
    fun `full app still parses a pre-split release with a single apk asset`() {
        // The default isLite=false must keep the old single-APK payloads working:
        // "toadfiles-1.7.0.apk" carries no lite marker, so it matches the full edition.
        val release = GithubReleaseChecker.parseRelease(payload)

        assertEquals("toadfiles-1.7.0.apk", release.apkName)
    }

    @Test
    fun `full app gets no apk when a release ships lite assets only`() {
        val liteOnly = """
            {
              "tag_name": "v2.5.1",
              "assets": [
                { "name": "toadfiles-lite-v2.5.1.apk", "browser_download_url": "https://x/lite.apk", "size": 1 }
              ]
            }
        """.trimIndent()

        val release = GithubReleaseChecker.parseRelease(liteOnly, isLite = false)

        // Honest null beats silently offering the wrong edition's APK.
        assertNull(release.apkName)
        assertNull(release.apkUrl)
    }

    @Test
    fun `parseRelease treats a missing assets array and empty body honestly`() {
        val minimal = """{"tag_name": "v1.9.0"}"""

        val release = GithubReleaseChecker.parseRelease(minimal)

        assertEquals("v1.9.0", release.tagName)
        assertNull(release.apkUrl)
        assertNull(release.notes)
        // html_url falls back to the repo releases page so "View on GitHub" always works.
        assertEquals("https://github.com/dukeoftoad/toadFILES/releases", release.htmlUrl)
    }

    @Test
    fun `parseRelease refuses a payload without tag_name`() {
        try {
            GithubReleaseChecker.parseRelease("""{"html_url": "https://github.com/x"}""")
            fail("expected JSONException for a payload without tag_name")
        } catch (_: JSONException) {
            // expected — the ViewModel surfaces this as an honest Error state
        }
    }
}
