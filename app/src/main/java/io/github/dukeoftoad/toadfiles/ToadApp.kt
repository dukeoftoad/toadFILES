package io.github.dukeoftoad.toadfiles

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.dukeoftoad.toadfiles.data.Clipboard
import io.github.dukeoftoad.toadfiles.data.FileRepository
import io.github.dukeoftoad.toadfiles.data.MediaRepository
import io.github.dukeoftoad.toadfiles.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny hand-rolled DI container — no framework, no reflection, nothing to misconfigure. */
class AppContainer(app: ToadApp) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val fileRepo = FileRepository()
    val mediaRepo = MediaRepository(app.contentResolver)
    val settingsRepo = SettingsRepository(app)
    val clipboard = Clipboard()
}

class ToadApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
            modelClass.isAssignableFrom(io.github.dukeoftoad.toadfiles.ui.home.HomeViewModel::class.java) ->
                io.github.dukeoftoad.toadfiles.ui.home.HomeViewModel(c) as T
            modelClass.isAssignableFrom(io.github.dukeoftoad.toadfiles.ui.browse.BrowseViewModel::class.java) ->
                io.github.dukeoftoad.toadfiles.ui.browse.BrowseViewModel(c) as T
            modelClass.isAssignableFrom(io.github.dukeoftoad.toadfiles.ui.categories.CategoriesViewModel::class.java) ->
                io.github.dukeoftoad.toadfiles.ui.categories.CategoriesViewModel(c) as T
            modelClass.isAssignableFrom(io.github.dukeoftoad.toadfiles.ui.recent.RecentViewModel::class.java) ->
                io.github.dukeoftoad.toadfiles.ui.recent.RecentViewModel(c) as T
            modelClass.isAssignableFrom(io.github.dukeoftoad.toadfiles.ui.storage.StorageViewModel::class.java) ->
                io.github.dukeoftoad.toadfiles.ui.storage.StorageViewModel(c) as T
            modelClass.isAssignableFrom(io.github.dukeoftoad.toadfiles.ui.settings.SettingsViewModel::class.java) ->
                io.github.dukeoftoad.toadfiles.ui.settings.SettingsViewModel(c) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
