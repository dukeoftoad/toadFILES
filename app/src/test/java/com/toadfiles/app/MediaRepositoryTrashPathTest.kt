package com.toadfiles.app

import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.Trash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trash-path detection powers the "hide trashed files everywhere" rule: renaming a file
 * into the per-volume trash folder does not instantly update the MediaStore index, so
 * Recent and Categories filter rows by their DATA path instead.
 */
class MediaRepositoryTrashPathTest {

    @Test
    fun `paths inside the trash folder on any volume are detected`() {
        assertTrue(MediaRepository.isTrashPath("/storage/emulated/0/${Trash.DIR_NAME}/1700000000_photo.jpg"))
        assertTrue(MediaRepository.isTrashPath("/storage/0000-0001/${Trash.DIR_NAME}/1700000000_song.mp3"))
    }

    @Test
    fun `the trash folder itself counts as inside`() {
        assertTrue(MediaRepository.isTrashPath("/storage/emulated/0/${Trash.DIR_NAME}"))
    }

    @Test
    fun `ordinary paths are not flagged`() {
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/DCIM/photo.jpg"))
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/Download/report.pdf"))
        assertFalse(MediaRepository.isTrashPath(""))
    }

    @Test
    fun `lookalike folder names are not flagged`() {
        // A file literally named ".toadfiles-trash" (not a folder segment) stays visible.
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/notes/.toadfiles-trash.txt"))
        // A sibling with the trash name as a substring is not the trash folder.
        assertFalse(MediaRepository.isTrashPath("/storage/emulated/0/not.toadfiles-trash/file.txt"))
    }
}
