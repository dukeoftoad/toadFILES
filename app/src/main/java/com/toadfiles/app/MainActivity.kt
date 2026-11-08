package com.toadfiles.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.toadfiles.app.ui.components.ToadSplash
import com.toadfiles.app.ui.theme.ToadFace
import com.toadfiles.app.ui.theme.ToadTheme
import com.toadfiles.app.util.IncomingIntents
import com.toadfiles.app.util.Intents
import com.toadfiles.app.update.StartupUpdateCheck
import com.toadfiles.app.update.UpdateBanner
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.domain.IS_LITE
import com.toadfiles.app.domain.MotionStyle
import com.toadfiles.app.ui.Routes
import com.toadfiles.app.ui.ToadNavHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var hasAllFilesAccess by mutableStateOf(false)

    /**
     * Archive handed over by the system "Open with" sheet (v1.11 manifest VIEW
     * filter). Held as state so the routing effect below fires as soon as storage
     * access is granted — the handoff must survive the permission round-trip, not
     * only the happy path.
     */
    private var pendingViewUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToadViewModelFactory.init(application)
        enableEdgeToEdge()
        refreshPermissionState()
        pendingViewUri = IncomingIntents.extractViewUri(intent)
        setContent {
            val appScope = remember { (application as ToadApp).container.appScope }
            val settings = remember {
                container.settingsRepo.settings.stateIn(
                    appScope,
                    SharingStarted.Eagerly,
                    initialValue = com.toadfiles.app.data.ToadSettings(),
                )
            }.collectAsStateWithLifecycle()

            // v1.7.1 OTA: one silent update check per app process, fired on open —
            // never periodic, never a download. Failures (airplane mode etc.) stay silent.
            // Lite (docs/LITE_EDITION_PLAN.md §3 phase 2) never checks at all: the
            // edition ships without the update checker and without the INTERNET
            // permission, so the constant-folded branch below removes the check (and
            // R8 strips StartupUpdateCheck's callers) from the lite build entirely.
            val startupUpdate = remember { StartupUpdateCheck.instance }
            LaunchedEffect(Unit) { if (!IS_LITE) startupUpdate.checkOnce(appScope) }
            val updateBanner by startupUpdate.banner.collectAsStateWithLifecycle()
            val bannerDismissed by startupUpdate.bannerDismissed.collectAsStateWithLifecycle()

            // Hoisted so the banner's "Update" can deep-link into the existing About flow.
            val navController = rememberNavController()

            // v1.11: an archive opened via "Open with → toadFILES" is routed into
            // Browse's extract-or-open flow — the same dialog tapping the archive in
            // Browse shows, so the handoff behaves like the in-app action. Waits for
            // storage access: a handoff that arrives during onboarding must survive
            // until the grant lands (onResume refreshes hasAllFilesAccess).
            //
            // Flaw fix (owner report): the handoff used to AUTO-EXTRACT stream-copied
            // archives straight into Download — the user was dumped in a folder with
            // no say at all. Now it always ASKS: Browse opens on a folder the user can
            // actually see (the archive's own folder; Download for cache copies) and
            // shows the extract-or-open dialog, with "Extract to…" alongside — the
            // archive_dest only names the DEFAULT destination "here" means for a
            // cache copy, it never triggers extraction by itself.
            LaunchedEffect(pendingViewUri, hasAllFilesAccess) {
                val uri = pendingViewUri ?: return@LaunchedEffect
                if (!hasAllFilesAccess) return@LaunchedEffect
                pendingViewUri = null
                // Rotation must not replay the handoff from the retained intent —
                // consume it at the source too.
                setIntent(Intent(applicationContext, MainActivity::class.java))
                val resolved = withContext(Dispatchers.IO) {
                    IncomingIntents.resolveArchive(applicationContext, uri)
                }
                if (resolved == null) {
                    Toast.makeText(applicationContext, "Could not open that file", Toast.LENGTH_SHORT).show()
                    return@LaunchedEffect
                }
                if (!ArchiveOps.canExtract(resolved.file.name)) {
                    // We only advertise archive mimes, but a provider can mislabel —
                    // hand the file to whatever really handles it instead of failing.
                    if (!Intents.openFile(applicationContext, resolved.file)) {
                        Toast.makeText(applicationContext, "No app can open this file", Toast.LENGTH_SHORT).show()
                    }
                    return@LaunchedEffect
                }
                val streamDest = if (resolved.fromStream) IncomingIntents.defaultExtractDest() else null
                val dir = streamDest?.absolutePath
                    ?: resolved.file.parentFile?.absolutePath
                    ?: Environment.getExternalStorageDirectory().absolutePath
                navController.navigate(
                    Routes.browse(
                        dir,
                        openArchive = resolved.file.absolutePath,
                        archiveDest = streamDest?.absolutePath,
                    ),
                ) { launchSingleTop = true }
            }

            // v1.11 splash: the toad hops in and blinks on every cold start; a
            // configuration change finishes the same animation instead of replaying it.
            var splashShowing by rememberSaveable { mutableStateOf(true) }

            ToadTheme(
                appTheme = settings.value.appTheme,
                themeMode = settings.value.themeMode,
                accentColor = settings.value.accentColor,
                // Lite is "Performance mode, baked into the build" (plan §2): NONE is
                // the only motion the edition ever sees — the ToadMotion provided
                // here drives the splash early-return and every transition — and
                // performance mode reads as ON for the whole UI, so VideoThumb skips
                // the MediaMetadataRetriever pipeline app-wide. Both sides are
                // compile-time constants for the lite flavor; R8 folds them away.
                motionStyle = if (IS_LITE) MotionStyle.NONE else settings.value.motionStyle,
                appFont = settings.value.appFont,
                textScale = settings.value.textScale,
                performanceMode = settings.value.performanceMode || IS_LITE,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (hasFileAccess()) {
                            // File browsing depends only on storage permission. The update
                            // banner is optional and must never gate the main app UI.
                            // (Also constant-folded out of lite: the banner state stays
                            // null there because checkOnce never runs.)
                            if (!IS_LITE && updateBanner != null && !bannerDismissed) {
                                UpdateBanner(
                                    release = updateBanner,
                                    onOpenUpdateFlow = {
                                        navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                                    },
                                    onDismiss = startupUpdate::dismissBanner,
                                )
                            }
                            Box(Modifier.weight(1f)) { ToadNavHost(navController) }
                        } else {
                            OnboardingScreen(
                                onGrant = { requestFileAccess() },
                                onOpenSettings = { openAppSettings() },
                            )
                        }
                    }
                    if (splashShowing) {
                        ToadSplash(onFinished = { splashShowing = false })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask: "Open with toadFILES" while the app is running lands here
        // instead of stacking a second MainActivity on the existing task.
        pendingViewUri = IncomingIntents.extractViewUri(intent)
    }

    override fun onStop() {
        super.onStop()
        // Flush the in-memory "most used" folder counts to DataStore while we're
        // backgrounded — visits are never persisted one by one.
        (application as? ToadApp)?.container?.appScope?.launch(Dispatchers.IO) {
            runCatching { container.settingsRepo.persistFolderUses() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private val container get() = (application as ToadApp).container

    /**
     * Re-reads the storage permission gate. Since v1.8.1 the app requires Android 14
     * (API 34+), so the only regime is "All files access"
     * ([Environment.isExternalStorageManager] backed by MANAGE_EXTERNAL_STORAGE),
     * granted from the system settings screen. Called in onCreate and onResume, so
     * granting from Settings unlocks the app without a restart.
     */
    private fun refreshPermissionState() {
        // Some ROMs/test environments can throw from this check; failing closed
        // (showing onboarding) is always safe.
        hasAllFilesAccess = try {
            Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
    }

    /** Whole-UI gate: "All files access". */
    private fun hasFileAccess(): Boolean = hasAllFilesAccess

    private fun requestFileAccess() {
        // Deep-link to this app's own "All files access" toggle. The per-package intent
        // is not handled by every ROM, so fall back to the generic all-files screen,
        // then to the app details page as a last resort.
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            )
        )
    }
}

@Composable
private fun OnboardingScreen(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The mascot greets new users — same toad as the splash and launcher.
            ToadFace(size = 112.dp, modifier = Modifier.padding(bottom = 20.dp))
            Spacer(Modifier.height(16.dp))
            Text("Welcome to toadFILES", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                // Lite's privacy story is even stronger than full's: no update checker,
                // no INTERNET permission — the app physically cannot phone out.
                if (IS_LITE) {
                    "toadFILES Lite manages the files on this device locally. " +
                        "It never uploads anything, and nothing runs in the background.\n\n" +
                        "It has no network access at all — the Lite edition ships without " +
                        "the update checker, so updates arrive the same way the app did: " +
                        "by sideloading a newer release.\n\n" +
                        "To browse and organise your files, grant \"All files access\" " +
                        "in the next screen. Android requires this special permission for " +
                        "file managers to work on modern versions."
                } else {
                    "toadFILES manages the files on this device locally. " +
                        "It never uploads anything, and nothing runs in the background.\n\n" +
                        "Its only network use is the update check, which runs once when you " +
                        "open the app and when you tap \"Check for updates\" on the About " +
                        "page, and talks to GitHub Releases.\n\n" +
                        "To browse and organise your files, grant \"All files access\" " +
                        "in the next screen. Android requires this special permission for " +
                        "file managers to work on modern versions."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onGrant,
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("Grant all files access")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(24.dp)) {
                Text("Open app settings")
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "Tip: return here after granting — the app unlocks automatically.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
