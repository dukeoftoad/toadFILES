package com.toadfiles.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.data.DownloadRequest
import com.toadfiles.app.data.GitHubRelease
import com.toadfiles.app.data.UpdateCheckRateLimitException
import com.toadfiles.app.data.UpdateChecker
import com.toadfiles.app.data.UpdateDownloader
import com.toadfiles.app.update.StartupUpdateCheck
import com.toadfiles.app.ui.about.AboutViewModel
import com.toadfiles.app.ui.about.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * About page ViewModel — version/build are static, but storage stats and category counts
 * come from real queries that should produce a populated state even on Robolectric's
 * empty media library (where every category has zero items but every category still
 * appears).
 *
 * The update-check half runs against fakes behind [UpdateChecker]/[UpdateDownloader]:
 * the state machine must go Idle → Checking → Available / UpToDate / Error strictly on
 * user action, and the download must end with a downloaded URI in the Available state
 * (the real DownloadManager is never exercised here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: AboutViewModel
    private lateinit var container: AppContainer
    private lateinit var context: Context
    private lateinit var checker: FakeChecker
    private lateinit var downloader: FakeDownloader

    /** Fake GitHub checker: canned release/throwable, optional gate to hold a check open. */
    private class FakeChecker : UpdateChecker {
        var release: GitHubRelease? = null
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var calls: Int = 0
            private set

        override suspend fun fetchLatestRelease(): GitHubRelease {
            calls++
            gate?.await()
            error?.let { throw it }
            return release ?: error("FakeChecker has no canned release")
        }
    }

    /** Fake DownloadManager: records the request, returns a canned download result. */
    private class FakeDownloader : UpdateDownloader {
        var enqueued: Int = 0
            private set
        var lastRequest: DownloadRequest? = null
            private set
        var result: Uri? = Uri.parse("content://downloads/all_downloads/42")

        override fun enqueue(context: Context, request: DownloadRequest): Long {
            enqueued++
            lastRequest = request
            return 42L
        }

        override suspend fun awaitDownload(context: Context, id: Long): Uri? = result
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context)
        checker = FakeChecker()
        downloader = FakeDownloader()
        vm = AboutViewModel(
            container,
            ioDispatcher = dispatcher,
            updateChecker = checker,
            updateDownloader = downloader,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun await(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!block()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within timeout" }
            Thread.sleep(10)
        }
    }

    // Fixtures derive from BuildConfig.VERSION_NAME so a version bump in
    // app/build.gradle.kts cannot silently flip "newer release" cases.
    private val installedTag = "v${BuildConfig.VERSION_NAME}"

    private fun newerTag(): String {
        val parts = BuildConfig.VERSION_NAME.split('.').map { it.toIntOrNull() ?: 0 }
        return "v${parts.getOrElse(0) { 0 }}.${parts.getOrElse(1) { 0 } + 1}.0"
    }

    private fun release(tag: String = newerTag()) = GitHubRelease(
        tagName = tag,
        htmlUrl = "https://github.com/dukeoftoad/toadFILES/releases/tag/$tag",
        notes = "Faster unzip and an update check.",
        apkName = "toadfiles-${tag.removePrefix("v")}.apk",
        apkUrl = "https://github.com/dukeoftoad/toadFILES/releases/download/$tag/toadfiles-${tag.removePrefix("v")}.apk",
        apkSizeBytes = 7340032L,
    )

    private fun awaitAvailable(): UpdateState.Available {
        await { vm.state.value.update is UpdateState.Available }
        return vm.state.value.update as UpdateState.Available
    }

    // ---------- storage / counts (pre-existing coverage) ----------

    @Test
    fun `init loads storage stats and category counts without crashing`() {
        await { !vm.state.value.loading }
        val s = vm.state.value
        // StatFs may report null on unusual Robolectric mounts; if non-null it must be sane.
        s.storage?.let { stats -> assertTrue(stats.totalBytes >= 0) }
        // Every FileCategory must be represented — six categories.
        assertEquals(6, s.categoryCounts.size)
        // Robolectric media library is empty, so all counts are zero.
        assertTrue(s.categoryCounts.all { it.count == 0 })
    }

    @Test
    fun `refresh is idempotent and keeps the state populated`() {
        await { !vm.state.value.loading }
        vm.refresh()
        await { !vm.state.value.loading }
        val s = vm.state.value
        assertNotNull(s.storage)
        assertEquals(6, s.categoryCounts.size)
    }

    // ---------- update check state machine ----------

    @Test
    fun `update panel starts Idle with no network activity`() {
        assertEquals(UpdateState.Idle, vm.state.value.update)
        assertEquals(0, checker.calls)
    }

    @Test
    fun `checkForUpdates moves Idle to Available with the release metadata`() = runTest {
        val fake = release()
        checker.release = fake

        vm.checkForUpdates()
        val available = awaitAvailable()

        assertEquals(fake.tagName, available.version)
        assertEquals(fake.apkUrl, available.apkUrl)
        assertEquals(fake.apkName, available.fileName)
        assertEquals(7340032L, available.sizeBytes)
        assertEquals("Faster unzip and an update check.", available.notes)
        assertEquals(fake.htmlUrl, available.htmlUrl)
        assertFalse(available.downloadInProgress)
        assertEquals(null, available.downloadedUri)
    }

    @Test
    fun `checkForUpdates reports UpToDate when the release is not newer`() = runTest {
        checker.release = release(installedTag) // equals the installed version

        vm.checkForUpdates()

        await { vm.state.value.update is UpdateState.UpToDate }
        val upToDate = vm.state.value.update as UpdateState.UpToDate
        assertEquals(BuildConfig.VERSION_NAME, upToDate.currentVersion)
    }

    @Test
    fun `checkForUpdates surfaces a failed check as Error and can recover`() = runTest {
        checker.error = IOException("no network")

        vm.checkForUpdates()
        await { vm.state.value.update is UpdateState.Error }
        assertTrue((vm.state.value.update as UpdateState.Error).message.contains("GitHub"))
        assertEquals(
            "Couldn't reach GitHub — check your connection and try again.",
            (vm.state.value.update as UpdateState.Error).message,
        )

        checker.error = null
        checker.release = release()
        vm.checkForUpdates()
        awaitAvailable() // retry path works — Error is not a dead end
    }

    @Test
    fun `checkForUpdates ignores a second click while a check is running`() = runTest {
        checker.release = release()
        val gate = CompletableDeferred<Unit>()
        checker.gate = gate

        vm.checkForUpdates()
        assertEquals(UpdateState.Checking, vm.state.value.update)
        vm.checkForUpdates() // second tap while checking — consistent no-op

        gate.complete(Unit)
        awaitAvailable()
        assertEquals(1, checker.calls)
    }

    @Test
    fun `clearUpdateState returns the update panel to Idle`() = runTest {
        checker.release = release()
        vm.checkForUpdates()
        awaitAvailable()

        vm.clearUpdateState()

        assertEquals(UpdateState.Idle, vm.state.value.update)
    }

    // ---------- download + install handoff ----------

    @Test
    fun `checkForUpdates shows the friendly rate-limit message for HTTP 403`() = runTest {
        checker.error = UpdateCheckRateLimitException()

        vm.checkForUpdates()

        await { vm.state.value.update is UpdateState.Error }
        val message = (vm.state.value.update as UpdateState.Error).message
        assertTrue(message.contains("rate limit"))
        assertTrue(message.contains("try again"))
    }

    // ---------- v1.7.1: startup-check seeding + auto-check wiring ----------

    @Test
    fun `a positive startup check seeds the panel Available on construction`() {
        val startup = StartupUpdateCheck()
        checker.release = release()
        startup.checkOnce(
            CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = checker,
        )

        val seededVm = AboutViewModel(container, dispatcher, checker, downloader, startup)

        val available = seededVm.state.value.update as UpdateState.Available
        assertEquals(checker.release!!.tagName, available.version)
        assertEquals(checker.release!!.apkUrl, available.apkUrl)
        assertFalse(available.downloadInProgress) // seeded ≠ downloading
        assertEquals(1, checker.calls) // no second network call from the ViewModel
    }

    @Test
    fun `no startup result leaves the panel Idle as before`() {
        assertEquals(UpdateState.Idle, vm.state.value.update)
    }

    @Test
    fun `startup seeding works even after the banner was dismissed`() {
        val startup = StartupUpdateCheck()
        checker.release = release()
        startup.checkOnce(
            CoroutineScope(dispatcher),
            installedVersion = BuildConfig.VERSION_NAME,
            checker = checker,
        )
        startup.dismissBanner() // banner hidden, About still deep-links into the flow

        val seededVm = AboutViewModel(container, dispatcher, checker, downloader, startup)

        assertTrue(seededVm.state.value.update is UpdateState.Available)
    }

    @Test
    fun `downloadAndInstall enqueues the release apk and records the downloaded uri`() = runTest {
        checker.release = release()
        vm.checkForUpdates()
        val available = awaitAvailable()

        vm.downloadAndInstall(context)

        await { (vm.state.value.update as? UpdateState.Available)?.downloadedUri != null }
        val after = vm.state.value.update as UpdateState.Available
        assertFalse(after.downloadInProgress)
        assertEquals(1, downloader.enqueued)
        assertEquals(available.apkUrl, downloader.lastRequest?.url)
        assertEquals(available.fileName, downloader.lastRequest?.fileName)
        assertEquals("toadFILES ${available.version}", downloader.lastRequest?.title)
    }

    @Test
    fun `downloadAndInstall reports a failed download as Error`() = runTest {
        checker.release = release()
        vm.checkForUpdates()
        awaitAvailable()
        downloader.result = null

        vm.downloadAndInstall(context)

        await { vm.state.value.update is UpdateState.Error }
        assertTrue((vm.state.value.update as UpdateState.Error).message.contains("download"))
    }

    @Test
    fun `downloadAndInstall is a no-op when the panel is not in Available`() = runTest {
        vm.downloadAndInstall(context) // Idle — nothing to download

        assertEquals(0, downloader.enqueued)
        assertEquals(UpdateState.Idle, vm.state.value.update)
    }
}
