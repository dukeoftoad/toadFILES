package com.toadfiles.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.ArchiveOps
import com.toadfiles.app.util.Intents
import java.io.File

/**
 * Where a tap on a media-library entry (Recent, Home's recent strip, the Categories
 * drill-in, the per-entry action sheet's "Open") should route.
 *
 * The two flaw fixes this exists for (owner report):
 *  - **APK taps went to a notes app.** A plain ACTION_VIEW on an .apk can be claimed
 *    by whatever generic viewer the OEM ships (the reporting device's Notes app);
 *    the honest target for an APK is the SYSTEM PACKAGE INSTALLER, gated exactly
 *    like Browse's .zip-row install flow ([Intents.installApkFromUri]).
 *  - **Archive taps bounced out of the app.** toadFILES now registers as an archive
 *    VIEW handler (the "Open with" feature), so a plain ACTION_VIEW on a .zip can
 *    resolve right back into toadFILES — or into another archiver — with the user
 *    dumped somewhere unexpected. An extractable archive with a real path belongs
 *    in toadFILES' own extract flow (Browse's extract-or-open dialog).
 */
sealed interface MediaTapRoute {

    /** An .apk: hand it to the system package installer behind the shared confirm dialog. */
    data class InstallApk(val uri: Uri, val name: String) : MediaTapRoute

    /** An extractable archive with a real on-disk path: route to Browse's extract dialog. */
    data class OpenArchive(val archive: File) : MediaTapRoute

    /** Everything else: an external viewer via ACTION_VIEW, as before. */
    data class OpenExternal(val uri: Uri, val mime: String) : MediaTapRoute
}

/**
 * The one routing rule every media surface uses for row taps and the sheet's
 * "Open" — one source of truth, so no surface can regress independently.
 *
 * Note: only a plain `.apk` extension routes to the installer — `.xapk`/`.apkm`
 * bundles are not installable packages and stay with external viewers.
 */
fun routeMediaTap(entry: MediaRepository.MediaEntry): MediaTapRoute = when {
    ArchiveOps.extension(entry.name) == "apk" -> MediaTapRoute.InstallApk(entry.uri, entry.name)
    ArchiveOps.canExtract(entry.name) && entry.data != null ->
        MediaTapRoute.OpenArchive(File(entry.data))
    else -> MediaTapRoute.OpenExternal(entry.uri, entry.mime)
}

/**
 * The user-facing message for an installer handoff that did NOT simply start;
 * null means the installer took over and nothing needs saying.
 */
fun apkInstallMessage(result: Intents.InstallResult): String? = when (result) {
    Intents.InstallResult.STARTED -> null
    Intents.InstallResult.NEEDS_PERMISSION ->
        "Allow \"Install unknown apps\" for toadFILES, return here, then tap Install again"
    Intents.InstallResult.SETTINGS_FAILED ->
        "Could not open Android's install-permission settings"
    Intents.InstallResult.FAILED -> "Could not start the installer"
}

/**
 * The ONE install-confirm dialog, shared by Browse (file rows), Recent, Categories
 * and Home. Explains the "Install unknown apps" detour before it can happen, and
 * keeps itself open on [Intents.InstallResult.NEEDS_PERMISSION] — the user walks
 * to Settings, comes back and taps Install again on the same dialog.
 *
 * @param launch performs the gated handoff (File- or uri-backed) and reports the
 *        outcome; the dialog never touches the APK itself.
 */
@Composable
fun ApkInstallDialog(
    apkName: String,
    launch: () -> Intents.InstallResult,
    onDismiss: () -> Unit,
    toast: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        modifier = androidx.compose.ui.Modifier
            .testTag("dialog_apk_install"),
        onDismissRequest = onDismiss,
        title = { Text("Install \"$apkName\"?") },
        text = {
            Text(
                "toadFILES hands this APK to the Android package installer. " +
                    "Android may ask you to allow installs from toadFILES first."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                when (val result = launch()) {
                    Intents.InstallResult.STARTED -> onDismiss()
                    // The settings toggle is now on screen: keep the dialog so the
                    // user can retry the exact install when they come back.
                    Intents.InstallResult.NEEDS_PERMISSION ->
                        toast(apkInstallMessage(result)!!)
                    Intents.InstallResult.SETTINGS_FAILED -> {
                        toast(apkInstallMessage(result)!!)
                        onDismiss()
                    }
                    Intents.InstallResult.FAILED -> {
                        toast(apkInstallMessage(result)!!)
                        onDismiss()
                    }
                }
            }) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Convenience: the dialog above with a File-backed handoff (Browse rows, Home favorites). */
@Composable
fun ApkInstallFileDialog(
    apk: File?,
    context: Context,
    onDismiss: () -> Unit,
    toast: (String) -> Unit,
) {
    apk?.let { file ->
        ApkInstallDialog(
            apkName = file.name,
            launch = { Intents.installApk(context, file) },
            onDismiss = onDismiss,
            toast = toast,
        )
    }
}
