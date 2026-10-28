package io.github.dukeoftoad.toadfiles.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** App-level copy/move clipboard, shared across screens for the session only (not persisted). */
class Clipboard {

    enum class Mode { COPY, MOVE }

    data class Clip(val mode: Mode, val files: List<File>)

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip

    fun set(mode: Mode, files: List<File>) {
        _clip.value = if (files.isEmpty()) null else Clip(mode, files.toList())
    }

    fun clear() {
        _clip.value = null
    }
}
