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
        val uri = contentUri(context, file)
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
        val uri = contentUri(context, file)
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

    fun shareFiles(context: Context, files: List<File>): Boolean {
        if (files.isEmpty()) return false
        val uris = ArrayList(files.map { contentUri(context, it) })
        val single = files.size == 1
        val intent = if (single) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.apply {
            if (single) putExtra(Intent.EXTRA_STREAM, uris[0]) else putExtra(Intent.EXTRA_STREAM, uris)
            type = files.map { mimeOf(it) }.distinct().singleOrNull() ?: "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            // Some receivers only honour permissions via ClipData
            clipData = android.content.ClipData.newRawUri("toadFILES", uris[0])
            if (!single) {
                for (u in uris.drop(1)) clipData!!.addItem(android.content.ClipData.Item(u))
            }
        }
        return try {
            context.startActivity(Intent.createChooser(intent, null))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareContent(context: Context, uri: Uri, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, null))
            true
        } catch (_: Exception) {
            false
        }
    }
}
