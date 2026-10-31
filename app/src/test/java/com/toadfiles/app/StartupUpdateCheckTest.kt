package com.toadfiles.app

import com.toadfiles.app.data.GitHubRelease
import com.toadfiles.app.data.UpdateCheckRateLimitException
import com.toadfiles.app.data.UpdateChecker
import com.toadfiles.app.update.StartupUpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The v1.7.1 silent startup check (app open): once per instance/process, no banner
 * unless a NEWER release WITH an APK exists, all failures invisible (airplane mode,
 * rate limit, GitHub down), and dismissal is final for the process.
 *
 * Fixtures derive from BuildConfig.VERSION_NAME (never hardcoded) so a version bump
 * cannot silently flip the "newer" cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupUpdateCheckTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Fake checker: canned release/throwable, counts calls. */
    private class FakeChecker(var release: GitHubRelease?, var error: Exception?) : UpdateChecker {
        var calls = 0
            private set

        override suspend fun fetchLatestRelease(): GitHubRelease {
            calls++
            error?.let { throw it }
            return release ?: error("FakeChecker has no canned release")
        }
    }

    private val installedTag = "v${BuildConfig.VERSION_NAME}"

    private fun newerTag(): String {
        val parts = BuildConfig.VERSION_NAME.split('.').map { it.toIntOrNull() ?: 0 }
        return "v${parts.getOrElse(0) { 0 }}.${parts.getOrElse(1) { 0 } + 1}.0"
    }

    private fun release(tag: String, apkUrl: String? = "https://github.com/dukeoftoad/toadFILES/releases/download/$tag/toadfiles.apk") =
        GitHubRelease(
            tagName = tag,
            htmlUrl = "https://github.com/dukeoftoad/toadFILES/releases/tag/$tag",
            notes = "Startup check test notes.",
            apkName = "toadfiles-${tag.removePrefix("v")}.apk",
            apkUrl = apkUrl,
            apkSizeBytes = 7340032L,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newCheck(): StartupUpdateCheck = StartupUpdateCheck()

    @Test
    fun `banner starts empty and nothing was checked`() {
        val check = newCheck()
        assertFalse(check.started)
        assertNull(check.banner.value)
    }

    @Test
    fun `newer release with apk populates the banner`() {
        val checker = FakeChecker(release(newerTag()), null)
        val check = newCheck()

        check.checkOnce(
            kotlinx.coroutines.CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = checker,
        )

        assertTrue(check.started)
        assertEquals(newerTag(), check.banner.value?.tagName)
        assertEquals(1, checker.calls)
    }

    @Test
    fun `same-version release never shows the banner`() {
        val check = newCheck()
        check.checkOnce(
            kotlinx.coroutines.CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = FakeChecker(release(installedTag), null),
        )
        assertNull(check.banner.value)
    }

    @Test
    fun `newer release without an APK asset is ignored silently`() {
        val check = newCheck()
        check.checkOnce(
            kotlinx.coroutines.CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = FakeChecker(release(newerTag(), apkUrl = null), null),
        )
        assertNull(check.banner.value) // nothing installable → nothing to announce
    }

    @Test
    fun `network failure fails silently with no banner`() {
        val check = newCheck()
        check.checkOnce(
            kotlinx.coroutines.CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = FakeChecker(null, IOException("airplane mode")),
        )
        assertNull(check.banner.value) // auto-check never surfaces errors
    }

    @Test
    fun `rate limit also stays silent`() {
        val check = newCheck()
        check.checkOnce(
            kotlinx.coroutines.CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = FakeChecker(null, UpdateCheckRateLimitException()),
        )
        assertNull(check.banner.value)
    }

    @Test
    fun `checkOnce runs at most once per instance - second call is a no-op`() {
        val first = FakeChecker(release(newerTag()), null)
        val second = FakeChecker(release(newerTag()), null)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val check = newCheck()

        check.checkOnce(scope, installedVersion = BuildConfig.VERSION_NAME, checker = first)
        assertEquals(newerTag(), check.banner.value?.tagName)

        val bannerBefore = check.banner.value
        check.checkOnce(scope, installedVersion = BuildConfig.VERSION_NAME, checker = second)
        assertEquals(0, second.calls) // throttled: at most one auto-check per process
        assertEquals(bannerBefore, check.banner.value)
    }

    @Test
    fun `dismissBanner hides the banner but keeps the release for About seeding`() {
        val check = newCheck()
        check.checkOnce(
            kotlinx.coroutines.CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = FakeChecker(release(newerTag()), null),
        )
        assertTrue(check.banner.value != null)
        assertFalse(check.bannerDismissed.value)

        check.dismissBanner()
        // Dismissal only hides the card; the release itself is not forgotten.
        assertTrue(check.banner.value != null)
        assertTrue(check.bannerDismissed.value)
    }
}
