package com.example.gloabtranslate

import android.app.Application
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import com.example.gloabtranslate.core.debug.DebugTools
import com.example.gloabtranslate.di.ApplicationComponent
import com.example.gloabtranslate.di.DaggerApplicationComponent
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import dagger.android.AndroidInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class for GloabTranslate app.
 * Initializes Dagger dependency injection.
 */
class GloabTranslateApplication : Application(), HasAndroidInjector {

    @Inject
    lateinit var dispatchingAndroidInjector: DispatchingAndroidInjector<Any>

    @Inject
    lateinit var preferencesManager: UserPreferencesManager

    lateinit var applicationComponent: ApplicationComponent
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Initialize Dagger component
        applicationComponent = DaggerApplicationComponent.factory()
            .create(this)

        // Inject members into this application instance (e.g., dispatchingAndroidInjector)
        applicationComponent.inject(this)

        // Initialize any global app components here
        initializeApp()
    }

    override fun androidInjector(): AndroidInjector<Any> {
        return dispatchingAndroidInjector
    }

    private fun initializeApp() {
        applicationScope.launch {
            preferencesManager.initialize()

            preferencesManager.preferenceChangeFlow
                .filterNotNull()
                .collect { event ->
                    if (event.key == "enableDebugMode") {
                        DebugTools.setEnabled(event.newValue as? Boolean == true)
                    }
                }
        }

        applicationScope.launch {
            val debugEnabled = preferencesManager.getPreference("enableDebugMode") as? Boolean ?: false
            DebugTools.setEnabled(debugEnabled)
        }
    }
}
