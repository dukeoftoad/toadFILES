package com.toadfiles.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Real share/open/install handoff to other apps. Every function returns an honest result —
 * false / a status enum — so callers can tell the user exactly what happened; nothing
 * pretends to succeed.
 *
 * Intent patterns follow the ones used by the major open-source file managers
 * (Material Files, Simple File Manager, Amaze):
 *  - OPEN uses a direct ACTION_VIEW launch. Whether the system resolver appears is
 *    Android's call, not ours: it only shows when more than one app can handle the
 *    type and the user has not pinned a default yet; with a saved default (or a
 *    single handler) the file opens straight into that app. No picker is promised
 *    for plain "Open" — "Open with" forces a one-time chooser instead.
 *  - SHARE uses ACTION_SEND / ACTION_SEND_MULTIPLE behind the system share sheet,
 *    with ClipData (every URI, not just the first) + FLAG_GRANT_READ_URI_PERMISSION
 *    on both the send intent and the chooser, so every target gets read access to
 *    every item.
 *  - INSTALL gates on canRequestPackageInstalls() and walks the user to the exact
 *    Android settings toggle when it is off (several OEM installers otherwise fail
 *    silently or auto-deny). Permission-missing, settings-open-failure and
 *    installer-launch-failure are reported as distinct outcomes.
 */
object Intents {

    const val PROVIDER_AUTHORITY = "com.toadfiles.app.fileprovider"

    /** Outcome of handing an APK to the system installer. */
    enum class InstallResult {
        /** The package installer was launched. */
        STARTED,

        /** "Install unknown apps" was off; the settings toggle was opened — a retry is valid. */
        NEEDS_PERMISSION,

        /** The permission was missing AND the settings screen could not be opened — no retry path. */
        SETTINGS_FAILED,

        /** The handoff itself failed (missing file, URI trouble, or a throwing launch after permission was granted). */
        FAILED,
    }

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        if (ext == "zip") return "application/zip"
        val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mapped ?: "application/octet-stream"
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, PROVIDER_AUTHORITY, file)

    // ---------- open ----------

    /**
     * Opens a real file with an external app via a direct ACTION_VIEW launch. The system
     * resolver shows up on its own when several apps can handle the type and no default
     * is saved; with a pinned default (or a single handler) the file opens directly.
     * That resolver behavior is platform-dependent and is NOT guaranteed by this call.
     * Returns false if nothing can open it.
     */
    fun openFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = try {
            contentUri(context, file)
        } catch (_: Exception) {
            return false
        }
        val intent = viewIntent(uri, mimeOf(file))
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Same as [openFile] but forces a ONE-TIME chooser so the user picks the handler each
     * time. A forced chooser never writes a default — that is exactly its point.
     */
    fun openWith(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = try {
            contentUri(context, file)
        } catch (_: Exception) {
            return false
        }
        val intent = viewIntent(uri, mimeOf(file))
        return try {
            context.startActivity(chooserAround(intent, listOf(uri), "Open with"))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens a MediaStore-backed content URI (Categories / Recent tabs). Direct launch, no
     * chooser — the system resolver appears on its own terms (multiple handlers, no saved
     * default), exactly as in [openFile]. MediaStore URIs are already world-readable
     * through grants, so no FileProvider round-trip is needed.
     */
    fun openContent(context: Context, uri: Uri, mime: String): Boolean {
        val intent = viewIntent(uri, mime)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Same as [openContent] but always shows the one-time chooser. */
    fun openContentWith(context: Context, uri: Uri, mime: String): Boolean {
        val intent = viewIntent(uri, mime)
        return try {
            context.startActivity(chooserAround(intent, listOf(uri), "Open with"))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun viewIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndTypeAndNormalize(uri, mime)
        // ClipData + grant flag: the belt-and-braces combo that keeps the read grant
        // attached to the resolved activity on every Android version and OEM.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** A read-grant clip carrying EVERY uri — not just the first. */
    private fun grantClip(uris: List<Uri>): ClipData {
        val clip = ClipData.newRawUri(null, uris.first())
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        return clip
    }

    /**
     * Wraps [intent] in a chooser that carries the same URIs and read grant. Some OEM
     * share/resolve sheets derive their grants from the chooser intent itself, so the
     * grant must survive on both wrappers, not only the inner intent.
     */
    private fun chooserAround(intent: Intent, uris: List<Uri>, title: String): Intent =
        Intent.createChooser(intent, title).apply {
            clipData = grantClip(uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ---------- share ----------

    /**
     * Standard Android share for on-disk files: ACTION_SEND (one file) or
     * ACTION_SEND_MULTIPLE (several) via FileProvider content URIs, behind the system
     * share sheet. The receiving app is chosen by the user; toadFILES itself never
     * uploads anything (no INTERNET permission).
     */
    fun shareFiles(context: Context, files: List<File>): Boolean {
        if (files.isEmpty()) return false
        val uris = mutableListOf<Uri>()
        for (f in files) {
            if (!f.isFile) continue
            try {
                uris += contentUri(context, f)
            } catch (_: Exception) {
                // Outside every provider root — skip it, share the rest.
            }
        }
        if (uris.isEmpty()) return false
        return shareUris(context, uris, shareMimeFor(files))
    }

    /**
     * Standard Android share for content URIs (MediaStore rows from Categories / Recent).
     * All URIs must share one mime type family; pass the most common one.
     *
     * ClipData carries EVERY uri: on several Android versions the grant a receiver ends up
     * with is derived from ClipData, so a first-item-only clip silently makes items 2..n
     * unreadable for the receiving app even though they ride along in EXTRA_STREAM.
     */
    fun shareUris(context: Context, uris: List<Uri>, mime: String): Boolean {
        if (uris.isEmpty()) return false
        return try {
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            // Forward read access to whichever app the user picks from the share sheet.
            intent.clipData = grantClip(uris)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooserAround(intent, uris, "Share via"))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Best common mime for a batch of files: the single file's type, else a generic one. */
    private fun shareMimeFor(files: List<File>): String {
        val real = files.filter { it.isFile }
        if (real.size == 1) return mimeOf(real.first())
        val mimes = real.map { mimeOf(it) }.toSet()
        return when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            mimes.all { it.startsWith("audio/") } -> "audio/*"
            mimes.all { it.startsWith("text/") } -> "text/*"
            else -> "*/*"
        }
    }

    // ---------- misc ----------

    /** Copies a real path to the system clipboard. Returns false if the clipboard service is unavailable. */
    fun copyPathToClipboard(context: Context, file: File): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("toadFILES path", file.absolutePath))
        true
    } catch (_: Exception) {
        false
    }

    // ---------- install ----------

    /**
     * Hands a real APK to the Android package installer.
     *
     * On Android 8+ the app must hold the "Install unknown apps" grant for this app
     * (REQUEST_INSTALL_PACKAGES + a user toggle). Many OEM installers fail silently or
     * auto-deny without it, so we check first and walk the user straight to the toggle
     * instead of firing an intent into the void. Returns [InstallResult.STARTED] when the
     * installer was launched, [InstallResult.NEEDS_PERMISSION] when the user was taken to
     * the settings toggle (the pending APK should be kept — a retry is valid),
     * [InstallResult.SETTINGS_FAILED] when even the settings screen could not be opened
     * (no retry path — the pending APK should be dropped), [InstallResult.FAILED] when the
     * handoff itself failed.
     */
    fun installApk(context: Context, file: File): InstallResult {
        if (!file.exists()) return InstallResult.FAILED
        val uri = try {
            contentUri(context, file)
        } catch (_: Exception) {
            return InstallResult.FAILED
        }
        if (!canRequestPackageInstalls(context)) {
            // Distinct outcome: "go flip the toggle in Settings" is only honest if we
            // actually took the user there. If both settings intents fail, say so.
            return if (openInstallPermissionSettings(context)) {
                InstallResult.NEEDS_PERMISSION
            } else {
                InstallResult.SETTINGS_FAILED
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent) // no chooser: this must reach the installer
            InstallResult.STARTED
        } catch (_: Exception) {
            // Permission is already granted here; this is a real handoff failure.
            InstallResult.FAILED
        }
    }

    private fun canRequestPackageInstalls(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    /** Opens the "Install unknown apps" screen for this app; false when both attempts fail. */
    private fun openInstallPermissionSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** True when the app currently holds the "Install unknown apps" grant. Exposed for the UI. */
    fun hasInstallPermission(context: Context): Boolean =
        try {
            context.packageManager.canRequestPackageInstalls()
        } catch (_: Exception) {
            false
        }
}
