package com.toadfiles.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.toadfiles.app.util.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * An application whose startActivity ALWAYS throws — the dark-path device where every
 * outbound intent is refused: both "Install unknown apps" settings handoffs and any
 * package-installer launch.
 */
class ExplodingStartActivityApp : Application() {
    var attempts = 0
        private set

    override fun startActivity(intent: Intent?) {
        attempts++
        throw ActivityNotFoundException("test: no activity handles $intent")
    }
}

/**
 * Install-handoff failure classification (audit P2.14 remainder):
 *  - permission missing AND the settings screen cannot open -> the distinct
 *    SETTINGS_FAILED outcome, not NEEDS_PERMISSION (which would tell the user to go
 *    flip a toggle we never managed to open);
 *  - installer launch throwing after the permission was granted stays FAILED, never
 *    masquerading as a permission problem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ExplodingStartActivityApp::class)
class IntentsInstallFailureTest {

    private val app: ExplodingStartActivityApp = ApplicationProvider.getApplicationContext()

    @Test
    fun `installApk reports the distinct outcome when every launch is refused`() {
        val root = Environment.getExternalStorageDirectory()
        root.mkdirs()
        val apk = File(root, "intents_install_failure.apk").apply { writeText("apk bytes") }
        try {
            val result = Intents.installApk(app, apk)
            if (Intents.hasInstallPermission(app)) {
                // Permission granted: the installer launch itself threw -> plain FAILED.
                assertEquals(Intents.InstallResult.FAILED, result)
                assertEquals(1, app.attempts)
            } else {
                // Permission missing: both settings attempts fired and both were refused.
                assertEquals(Intents.InstallResult.SETTINGS_FAILED, result)
                assertEquals(2, app.attempts)
                // Nothing actually started on the device.
                assertNull(Shadows.shadowOf(app).nextStartedActivity)
            }
        } finally {
            apk.delete()
        }
    }
}
