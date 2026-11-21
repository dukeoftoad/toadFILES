package com.toadfiles.app.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    /** Proper plural for user-facing counts: "1 item", "2 items" — never "item(s)".
     *  Returns just the word so qualified forms compose too ("3 source items"). */
    fun itemWord(n: Int): String = if (n == 1) "item" else "items"

    /**
     * The multi-select app-bar title: "N selected", plus the honest total size
     * ("N selected · 24.5 MB") when every selected item is a file whose size is
     * known. Callers pass a null [bytes] when the selection mixes in a folder —
     * a folder's true size needs a full tree walk, which the title bar must
     * never trigger, so it stays quiet instead of guessing.
     */
    fun selectionLabel(count: Int, bytes: Long?): String =
        if (bytes == null || bytes < 0) "$count selected" else "$count selected · ${size(bytes)}"

    /** 1024-style units like most desktop file managers. */
    fun size(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble()
        val units = listOf("KB", "MB", "GB", "TB")
        var u = -1
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024
            u++
        }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[u])
        else String.format(Locale.US, "%.1f %s", v, units[u])
    }

    fun date(epochMillis: Long): String {
        if (epochMillis <= 0) return "—"
        val fmt = SimpleDateFormat.getDateInstance(SimpleDateFormat.DEFAULT, Locale.getDefault())
        return fmt.format(Date(epochMillis))
    }

    fun dateTime(epochMillis: Long): String {
        if (epochMillis <= 0) return "—"
        val fmt = SimpleDateFormat.getDateTimeInstance(
            SimpleDateFormat.DEFAULT, SimpleDateFormat.SHORT, Locale.getDefault()
        )
        return fmt.format(Date(epochMillis))
    }
}
