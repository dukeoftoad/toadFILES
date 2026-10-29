package com.toadfiles.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import com.toadfiles.app.data.ThemeMode
import com.toadfiles.app.ui.ToadNavHost
import com.toadfiles.app.ui.theme.ToadTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {

    private var hasAllFilesAccess by mutableStateOf(false)
    private var legacyGranted by mutableStateOf(false)

    private val legacyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            legacyGranted = granted
        }

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
            ToadTheme(themeMode = settings.value.themeMode, dynamicColor = settings.value.dynamicColor) {
                if (hasFileAccess()) {
                    ToadNavHost()
                } else {
                    OnboardingScreen(
                        onGrant = { requestFileAccess() },
                        onOpenSettings = { openAppSettings() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private val container get() = (application as ToadApp).container

    private fun refreshPermissionState() {
        hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Some ROMs/test environments can throw from this check; failing closed
            // (showing onboarding) is always safe.
            try {
                Environment.isExternalStorageManager()
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            legacyGranted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hasAllFilesAccess
        else legacyGranted

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
                    "It has no internet permission and never uploads anything.\n\n" +
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
