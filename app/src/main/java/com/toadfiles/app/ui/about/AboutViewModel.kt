package com.toadfiles.app.ui.about

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toadfiles.app.AppContainer
import com.toadfiles.app.BuildConfig
import com.toadfiles.app.data.DownloadRequest
import com.toadfiles.app.data.GithubReleaseChecker
import com.toadfiles.app.data.SystemUpdateDownloader
import com.toadfiles.app.data.UpdateCheckException
import com.toadfiles.app.data.UpdateChecker
import com.toadfiles.app.data.UpdateDownloader
import com.toadfiles.app.domain.CategorySummary
import com.toadfiles.app.domain.StorageStats
import com.toadfiles.app.domain.VersionCompare
import com.toadfiles.app.update.StartupUpdateCheck
import com.toadfiles.app.util.Intents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * State for the About page. Everything is loaded on demand when the user opens About
 * (mirroring the rest of toadFILES — no startup scans) so opening About on a phone with
 * a lot of files is bounded by repository queries, never by the ViewModel itself.
 *
 * [update] carries the self-update panel state ([UpdateState]); it is Idle until the
 * user asks for a check, EXCEPT that a positive result of the v1.7.1 silent startup
 * check (app open, once per process, see [StartupUpdateCheck]) seeds Available
 * directly — still no network from this ViewModel.
 */
data class AboutUiState(
    val loading: Boolean = false,
    val storage: StorageStats? = null,
    val categoryCounts: List<CategorySummary> = emptyList(),
    val update: UpdateState = UpdateState.Idle,
)

/**
 * State machine for the OTA check (About → "Check for updates").
 *
 * PRIVACY STANCE: this panel contacts GitHub only on user action; the v1.7.1 silent
 * startup check lives in [StartupUpdateCheck] and only ever SEEDS this state with
 * an already-fetched result. GitHub-only endpoints, nothing cached beyond this
 * in-memory state.
 */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** The newest GitHub release is not newer than the installed version. */
    data class UpToDate(val currentVersion: String) : UpdateState

    /**
     * A newer release exists. [downloadedUri] (a DownloadManager content URI) is set
     * once the APK finished downloading, which is when the installer handoff happens;
     * [needsInstallPermission] marks the "Install unknown apps" round-trip — the APK
     * is kept so the user can tap "Download & install" again after flipping the toggle.
     */
    data class Available(
        val version: String,
        val apkUrl: String,
        val fileName: String,
        val sizeBytes: Long,
        val notes: String?,
        val htmlUrl: String,
        val downloadInProgress: Boolean = false,
        val downloadedUri: Uri? = null,
        val needsInstallPermission: Boolean = false,
    ) : UpdateState

    data class Error(val message: String) : UpdateState
}

/** Aggregates all the "honest about" data for the About screen in one place. */
class AboutViewModel(
    private val container: AppContainer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateChecker: UpdateChecker = GithubReleaseChecker(),
    private val updateDownloader: UpdateDownloader = SystemUpdateDownloader(),
    private val startupCheck: StartupUpdateCheck = StartupUpdateCheck.instance,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState(loading = true))
    val state: StateFlow<AboutUiState> = _state

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        // v1.7.1: if the silent startup check (app open) already found a newer release,
        // arrive with the update panel populated — the banner's "Update" tap deep-links
        // here straight into the existing "Download & install" flow.
        seedFromStartupCheck()
        refresh()
    }

    /** Populate the panel from the startup check's result, if any (Idle only, never downloads). */
    private fun seedFromStartupCheck() {
        val release = startupCheck.banner.value ?: return
        if (!VersionCompare.isNewer(BuildConfig.VERSION_NAME, release.tagName)) return
        if (release.apkUrl == null) return
        setUpdate(
            UpdateState.Available(
                version = release.tagName,
                apkUrl = release.apkUrl,
                fileName = release.apkName ?: DEFAULT_APK_NAME,
                sizeBytes = release.apkSizeBytes,
                notes = release.notes,
                htmlUrl = release.htmlUrl,
            )
        )
    }

    /** Re-fetch every figure shown on the About page. Safe to call repeatedly. */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch(ioDispatcher) {
            val root = container.fileRepo.primaryRoot()
            val storage = container.fileRepo.statsOf(root)
            val summaries = container.mediaRepo.summaries()
            _state.update {
                it.copy(
                    loading = false,
                    storage = storage,
                    categoryCounts = summaries,
                )
            }
        }
    }

    /**
     * Checks GitHub Releases for a version newer than [BuildConfig.VERSION_NAME].
     * Strictly user-initiated and cancellable via [viewModelScope]; a second click
     * while a check is running is a no-op (the consistent choice — the spinner already
     * communicates that work is in flight). Dismissing the panel mid-check makes the
     * late result a no-op too.
     */
    fun checkForUpdates() {
        if (_state.value.update is UpdateState.Checking) return
        // Flip synchronously (not inside the coroutine) so a rapid second tap — which
        // also lands on the main thread — always sees Checking and is a no-op.
        setUpdate(UpdateState.Checking)
        checkJob?.cancel()
        checkJob = viewModelScope.launch(ioDispatcher) {
            val result: UpdateState = try {
                val release = updateChecker.fetchLatestRelease()
                when {
                    !VersionCompare.isNewer(BuildConfig.VERSION_NAME, release.tagName) ->
                        UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                    release.apkUrl == null ->
                        UpdateState.Error(
                            "Release ${release.tagName} has no APK attached yet — " +
                                "you can grab it from GitHub."
                        )
                    else -> UpdateState.Available(
                        version = release.tagName,
                        apkUrl = release.apkUrl,
                        fileName = release.apkName ?: DEFAULT_APK_NAME,
                        sizeBytes = release.apkSizeBytes,
                        notes = release.notes,
                        htmlUrl = release.htmlUrl,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Error(checkErrorMessage(e))
            }
            // Only land the result if the user has not dismissed the panel meanwhile.
            _state.update { cur ->
                if (cur.update is UpdateState.Checking) cur.copy(update = result) else cur
            }
        }
    }

    /**
     * "Download & install" tap. First press: enqueues the APK in the system
     * [android.app.DownloadManager] (progress visible in its notification) and, when
     * it completes, hands the downloaded content URI to the system installer through
     * the SAME machinery BrowseScreen uses for tapped APKs
     * ([Intents.installApkFromUri] — the URI twin of [Intents.installApk], including
     * the "Install unknown apps" gate and outcome handling: on
     * [Intents.InstallResult.NEEDS_PERMISSION] the downloaded APK is kept so a second
     * tap installs it without re-downloading; dead-end outcomes surface as [UpdateState.Error]).
     *
     * A second tap while a download is running is a no-op; a tap after a completed
     * download goes straight to the installer handoff. If the user leaves About
     * mid-download the handoff is cancelled with the ViewModel — the system download
     * itself continues, and re-tapping re-runs the flow.
     */
    fun downloadAndInstall(context: Context) {
        val current = _state.value.update as? UpdateState.Available ?: return
        if (current.downloadInProgress) return
        val appContext = context.applicationContext
        if (current.downloadedUri != null) {
            handOffToInstaller(appContext)
            return
        }
        downloadJob?.cancel()
        // Flip synchronously (not inside the coroutine) so a rapid second tap — which
        // also lands on the main thread — always sees downloadInProgress and is a no-op.
        mutateAvailable { it.copy(downloadInProgress = true, needsInstallPermission = false) }
        downloadJob = viewModelScope.launch(ioDispatcher) {
            try {
                val id = updateDownloader.enqueue(
                    appContext,
                    DownloadRequest(
                        url = current.apkUrl,
                        fileName = current.fileName,
                        title = "toadFILES ${current.version}",
                        description = "toadFILES update — installed after download",
                    ),
                )
                val uri = updateDownloader.awaitDownload(appContext, id)
                if (uri == null) {
                    failAvailable(DOWNLOAD_FAILED)
                    return@launch
                }
                mutateAvailable { it.copy(downloadInProgress = false, downloadedUri = uri) }
                handOffToInstaller(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAvailable(downloadErrorMessage(e))
            }
        }
    }

    /** Dismisses the update panel and cancels any in-flight check/download job. */
    fun clearUpdateState() {
        checkJob?.cancel()
        checkJob = null
        downloadJob?.cancel()
        downloadJob = null
        setUpdate(UpdateState.Idle)
    }

    private fun handOffToInstaller(context: Context) {
        val available = _state.value.update as? UpdateState.Available ?: return
        val uri = available.downloadedUri ?: return
        when (Intents.installApkFromUri(context, uri)) {
            Intents.InstallResult.STARTED -> Unit // the installer took over
            Intents.InstallResult.NEEDS_PERMISSION ->
                mutateAvailable { it.copy(needsInstallPermission = true) }
            Intents.InstallResult.SETTINGS_FAILED ->
                failAvailable("Could not open Android's install-permission settings.")
            Intents.InstallResult.FAILED ->
                failAvailable("Could not start the installer for the downloaded APK.")
        }
    }

    private fun setUpdate(update: UpdateState) {
        _state.update { it.copy(update = update) }
    }

    /** Updates the Available state in place; a no-op if the panel moved on meanwhile. */
    private fun mutateAvailable(transform: (UpdateState.Available) -> UpdateState.Available) {
        _state.update { cur ->
            val available = cur.update as? UpdateState.Available ?: return@update cur
            cur.copy(update = transform(available))
        }
    }

    /** Replaces Available with [message] as an Error, unless the panel moved on. */
    private fun failAvailable(message: String) {
        _state.update { cur ->
            if (cur.update is UpdateState.Available) cur.copy(update = UpdateState.Error(message)) else cur
        }
    }

    private fun checkErrorMessage(e: Exception): String = when {
        // UpdateCheckException (rate limit, 5xx, 4xx) already carries a friendly message.
        e is UpdateCheckException -> e.message ?: GENERIC_CHECK_FAILED
        e is IOException -> "Couldn't reach GitHub — check your connection and try again."
        else -> "Update check failed: ${e.message?.take(120) ?: e.javaClass.simpleName}"
    }

    private fun downloadErrorMessage(e: Exception): String = when (e) {
        is IOException -> e.message ?: DOWNLOAD_FAILED
        else -> "The download failed: ${e.message?.take(120) ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val DEFAULT_APK_NAME = "toadfiles-update.apk"
        const val DOWNLOAD_FAILED = "The APK download failed — you can try again."
        const val GENERIC_CHECK_FAILED = "The update check failed — try again in a moment."
    }
}
