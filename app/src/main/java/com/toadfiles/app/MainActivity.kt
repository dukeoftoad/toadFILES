package com.toadfiles.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.update.StartupUpdateCheck
import com.toadfiles.app.update.UpdateBanner
import com.toadfiles.app.ui.Routes
import com.toadfiles.app.ui.ToadNavHost
import com.toadfiles.app.ui.theme.ToadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasAllFilesAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToadViewModelFactory.init(application)
        enableEdgeToEdge()
        refreshPermissionState()
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
            val startupUpdate = remember { StartupUpdateCheck.instance }
            LaunchedEffect(Unit) { startupUpdate.checkOnce(appScope) }
            val updateBanner by startupUpdate.banner.collectAsStateWithLifecycle()
            val bannerDismissed by startupUpdate.bannerDismissed.collectAsStateWithLifecycle()

            // Hoisted so the banner's "Update" can deep-link into the existing About flow.
            val navController = rememberNavController()

            ToadTheme(
                themeMode = settings.value.themeMode,
                dynamicColor = settings.value.dynamicColor,
                accentColor = settings.value.accentColor,
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (hasFileAccess()) {
                        // File browsing depends only on storage permission. The update
                        // banner is optional and must never gate the main app UI.
                        if (updateBanner != null && !bannerDismissed) {
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
            }
        }
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
            IconButton(onClick = {}, enabled = false, modifier = Modifier.size(96.dp)) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(88.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Welcome to toadFILES", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "toadFILES manages the files on this device locally. " +
                    "It never uploads anything, and nothing runs in the background.\n\n" +
                    "Its only network use is the update check, which runs once when you " +
                    "open the app and when you tap \"Check for updates\" on the About " +
                    "page, and talks to GitHub Releases.\n\n" +
                    "To browse and organise your files, grant \"All files access\" " +
                    "in the next screen. Android requires this special permission for " +
                    "file managers to work on modern versions.",
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
