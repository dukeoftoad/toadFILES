package com.toadfiles.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.domain.FileCategory
import com.toadfiles.app.ui.components.MediaTapRoute
import com.toadfiles.app.ui.components.routeMediaTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The ONE tap-routing rule every media surface (Recent rows/grid, Home's recent strip
 * and favorites, the Categories drill-in, the per-entry sheet's "Open") uses.
 *
 * The two flaws this rule fixes (owner report):
 *  - an .apk tap fired a plain ACTION_VIEW that a notes app could hijack — APKs must
 *    route to the SYSTEM PACKAGE INSTALLER;
 *  - an archive tap fired a VIEW intent that, since toadFILES registers as an archive
 *    handler, bounced back into the app or into another archiver — extractable
 *    archives with a real path must route into toadFILES' own extract flow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaTapRoutingTest {

    private fun entry(
        name: String,
        mime: String,
        data: String? = null,
        category: FileCategory = FileCategory.DOCS,
    ) = MediaRepository.MediaEntry(
        id = 1L,
        uri = Uri.parse("content://media/external/file/1"),
        name = name,
        size = 1024L,
        dateModified = 1L,
        mime = mime,
        category = category,
        data = data,
    )

    @Test
    fun `apk routes to the package installer, never a viewer`() {
        val route = routeMediaTap(
            entry(
                "app-release.apk",
                mime = "application/vnd.android.package-archive",
                data = "/storage/emulated/0/Download/app-release.apk",
                category = FileCategory.APKS,
            ),
        )
        assertTrue(route is MediaTapRoute.InstallApk)
        assertEquals("app-release.apk", (route as MediaTapRoute.InstallApk).name)
    }

    @Test
    fun `apk routes to the installer even with a blank or generic mime`() {
        // MediaStore can hand sideloaded APKs a generic mime; the EXTENSION is the
        // honest signal (the classifier in MediaRepository already repairs the mime —
        // the router must not depend on that having happened).
        val route = routeMediaTap(
            entry("game.apk", mime = "application/octet-stream", category = FileCategory.APKS),
        )
        assertTrue(route is MediaTapRoute.InstallApk)
    }

    @Test
    fun `xapk and apkm bundles with a path route into the extract flow`() {
        // v2.1.0 (owner report): app bundles ARE zip containers holding the real .apk
        // files — the honest tap is toadFILES' own extract flow so the APKs inside can
        // come out; the old external-viewer routing offered no way to get at them.
        // They still never route to the installer (rule order: extension "apk" check
        // above can't match ".xapk"/".apkm").
        val xapk = routeMediaTap(
            entry("app.xapk", mime = "application/octet-stream", data = "/tmp/toadfiles-test/app.xapk"),
        )
        assertTrue(xapk is MediaTapRoute.OpenArchive)
        assertEquals(File("/tmp/toadfiles-test/app.xapk"), (xapk as MediaTapRoute.OpenArchive).archive)
        val apkm = routeMediaTap(
            entry("app.apkm", mime = "application/octet-stream", data = "/tmp/toadfiles-test/app.apkm"),
        )
        assertTrue(apkm is MediaTapRoute.OpenArchive)
    }

    @Test
    fun `xapk and apkm without an on-disk path stay external`() {
        assertTrue(
            routeMediaTap(entry("app.xapk", mime = "application/octet-stream")) is MediaTapRoute.OpenExternal,
        )
    }

    @Test
    fun `zip with an on-disk path routes into the extract flow`() {
        val route = routeMediaTap(
            entry(
                "bundle.zip",
                mime = "application/zip",
                data = "/storage/emulated/0/Download/bundle.zip",
                category = FileCategory.ARCHIVES,
            ),
        )
        assertTrue(route is MediaTapRoute.OpenArchive)
        assertEquals(
            File("/storage/emulated/0/Download/bundle.zip"),
            (route as MediaTapRoute.OpenArchive).archive,
        )
    }

    @Test
    fun `seven-z volume starts route into the extract flow too`() {
        val route = routeMediaTap(
            entry("photos.7z.001", mime = "application/octet-stream", data = "/tmp/toadfiles-test/photos.7z.001"),
        )
        assertTrue(route is MediaTapRoute.OpenArchive)
    }

    @Test
    fun `archive without an on-disk path stays external - nothing to extract from`() {
        // No real path → toadFILES' extractor has no File to work on; the stream
        // copy path only exists for the system "Open with" handoff, not row taps.
        assertTrue(
            routeMediaTap(entry("bundle.zip", mime = "application/zip")) is MediaTapRoute.OpenExternal,
        )
    }

    @Test
    fun `rar and tar stay external - toadFILES cannot extract them`() {
        assertTrue(
            routeMediaTap(entry("old.rar", mime = "application/x-rar-compressed", data = "/tmp/old.rar")) is
                MediaTapRoute.OpenExternal,
        )
        assertTrue(
            routeMediaTap(entry("tape.tar", mime = "application/x-tar", data = "/tmp/tape.tar")) is
                MediaTapRoute.OpenExternal,
        )
    }

    @Test
    fun `regular files keep the external viewer path`() {
        val route = routeMediaTap(
            entry(
                "notes.txt",
                mime = "text/plain",
                data = "/storage/emulated/0/Documents/notes.txt",
                category = FileCategory.DOCS,
            ),
        )
        assertTrue(route is MediaTapRoute.OpenExternal)
        assertEquals("text/plain", (route as MediaTapRoute.OpenExternal).mime)
    }
}
