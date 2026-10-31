package com.toadfiles.app.update

import com.toadfiles.app.BuildConfig
import com.toadfiles.app.data.GithubReleaseChecker
import com.toadfiles.app.data.GitHubRelease
import com.toadfiles.app.data.UpdateChecker
import com.toadfiles.app.domain.VersionCompare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The silent once-per-process update check on app open (v1.7.1).
 *
 * PRIVACY STANCE, updated for v1.7.1: toadFILES still contacts exactly one endpoint
 * (the repo's releases/latest) and sends nothing but a plain GET. The single change
 * the user asked for: the check now ALSO runs once, silently, when the app is opened
 * — never periodically, never WorkManager, no auto-download. It only fires when the
 * app process comes up, and at most once per process ([checkOnce] is a no-op after
 * the first call).
 *
 * Failure is intentionally invisible: airplane mode, no network, GitHub being down —
 * the auto-check swallows everything and stays quiet. Only the manual check on the
 * About page shows errors. When a newer release WITH an APK asset is found, the
 * banner state ([banner]) is populated so MainActivity can show the subtle
 * "toadFILES vX available" card; tapping it deep-links to the existing About update
 * flow (AboutViewModel seeds its panel from [banner] — see AboutViewModel).
 */
class StartupUpdateCheck(
    private val defaultChecker: UpdateChecker = GithubReleaseChecker(),
) {

    private val _banner = MutableStateFlow<GitHubRelease?>(null)

    /** The newer release to advertise in the banner; null when there is nothing to show. */
    val banner: StateFlow<GitHubRelease?> = _banner

    /** True once [checkOnce] has actually started a check in this instance (= this process). */
    var started: Boolean = false
        private set

    /**
     * Runs the silent check at most once per instance. Guarded by [started] (set
     * synchronously before launching) so a re-composed LaunchedEffect in MainActivity
     * can never double-fire. Offline/other failures resolve silently.
     */
    fun checkOnce(
        scope: CoroutineScope,
        installedVersion: String = BuildConfig.VERSION_NAME,
        checker: UpdateChecker = defaultChecker,
    ) {
        if (started) return
        started = true
        scope.launch {
            // Silent by design: any failure (airplane mode, DNS, rate limit) is swallowed.
            val release = runCatching { checker.fetchLatestRelease() }.getOrNull() ?: return@launch
            if (release.apkUrl != null &&
                VersionCompare.isNewer(installedVersion, release.tagName)
            ) {
                _banner.value = release
            }
        }
    }

    /** Hides the banner for the rest of the process (the release itself is not forgotten by About seeding). */
    fun dismissBanner() {
        _banner.value = null
    }

    companion object {
        /** Process-wide instance MainActivity wires up; tests build their own. */
        val instance = StartupUpdateCheck()
    }
}
