package com.toadfiles.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Real share/open handoff to other apps. Returns false when no handler exists — never fakes success. */
object Intents {

    const val PROVIDER_AUTHORITY = "com.toadfiles.app.fileprovider"

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        if (ext == "zip") return "application/zip"
        val mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mapped ?: "application/octet-stream"
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, PROVIDER_AUTHORITY, file)

    /** Opens a real file with an external app. Returns false if nothing can handle it. */
    fun openFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = try {
            contentUri(context, file)
        } catch (_: Exception) {
            // File is outside every provider root (unmounted volume, odd path):
            // refuse honestly rather than crash the tap.
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, file.name))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Same as [openFile] but forces a chooser so the user picks the handler each time. */
    fun openWith(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = try {
            contentUri(context, file)
        } catch (_: Exception) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            // Always show the chooser so the user picks which app handles the file.
            context.startActivity(Intent.createChooser(intent, "Open with"))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Copies a real path to the system clipboard. Returns false if the clipboard service is unavailable. */
    fun copyPathToClipboard(context: Context, file: File): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("toadFILES path", file.absolutePath))
        true
    } catch (_: Exception) {
        false
    }

    /** Opens a MediaStore-backed content URI owned by the OS. */
    fun openContent(context: Context, uri: Uri, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, null))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Hands a real APK to the Android package installer via FileProvider.
     * Returns false when the installer cannot be started (e.g. "Install unknown
     * apps" not granted for toadFILES and the system declines to route).
     */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = try {
            contentUri(context, file)
        } catch (_: Exception) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent) // no chooser: this must reach the installer
            true
        } catch (_: Exception) {
            false
        }
    }

}
