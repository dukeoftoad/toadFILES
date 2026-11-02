package com.toadfiles.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.data.FileRepository
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.SettingsRepository
import com.toadfiles.app.domain.AppIconSwitcher
import com.toadfiles.app.domain.PackageManagerAppIconSwitcher
import com.toadfiles.app.domain.Trash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Tiny hand-rolled DI container. No framework, no reflection. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val fileRepo = FileRepository(context)
    val mediaRepo = MediaRepository(context.contentResolver)
    val settingsRepo = SettingsRepository(context)
    /** Applies the saved launcher-icon choice to the manifest activity-aliases. */
    val iconSwitcher: AppIconSwitcher = PackageManagerAppIconSwitcher(context)
    val clipboard = Clipboard()
}

class ToadApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Trash retention: quietly drop items older than 30 days in the background.
        container.appScope.launch(Dispatchers.IO) {
            val roots = container.fileRepo.roots().map { it.dir }
            Trash.purge(roots)
        }
        // Re-apply the saved launcher icon: enable the saved (design, color) alias and
        // disable the other 35, so a fresh install or restored backup shows the right
        // icon even before the user opens Appearance.
        container.appScope.launch(Dispatchers.IO) {
            val s = container.settingsRepo.settings.first()
            container.iconSwitcher.apply(s.appIconDesign, s.appIconColor)
        }
    }
}

/** Creates ViewModels with the app container; ViewModels stay constructor-injected and testable. */
object ToadViewModelFactory : ViewModelProvider.Factory {
    private val container: AppContainer
        get() = (appContext as ToadApp).container

    private var appContext: Application? = null

    fun init(app: Application) {
        appContext = app
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val c = container
        return when {
            modelClass.isAssignableFrom(com.toadfiles.app.ui.home.HomeViewModel::class.java) ->
                com.toadfiles.app.ui.home.HomeViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.browse.BrowseViewModel::class.java) ->
                com.toadfiles.app.ui.browse.BrowseViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.categories.CategoriesViewModel::class.java) ->
                com.toadfiles.app.ui.categories.CategoriesViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.recent.RecentViewModel::class.java) ->
                com.toadfiles.app.ui.recent.RecentViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.storage.StorageViewModel::class.java) ->
                com.toadfiles.app.ui.storage.StorageViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.analyzer.StorageAnalyzerViewModel::class.java) ->
                com.toadfiles.app.ui.analyzer.StorageAnalyzerViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.settings.SettingsViewModel::class.java) ->
                com.toadfiles.app.ui.settings.SettingsViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.trash.TrashViewModel::class.java) ->
                com.toadfiles.app.ui.trash.TrashViewModel(c) as T
            modelClass.isAssignableFrom(com.toadfiles.app.ui.about.AboutViewModel::class.java) ->
                com.toadfiles.app.ui.about.AboutViewModel(c) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
