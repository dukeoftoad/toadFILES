package com.toadfiles.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.toadfiles.app.data.Clipboard
import com.toadfiles.app.data.FileRepository
import com.toadfiles.app.data.MediaRepository
import com.toadfiles.app.data.SettingsRepository
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
            modelClass.isAssignableFrom(com.toadfiles.app.ui.settings.SettingsViewModel::class.java) ->
                com.toadfiles.app.ui.settings.SettingsViewModel(c) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
