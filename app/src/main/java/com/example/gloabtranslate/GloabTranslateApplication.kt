package com.example.gloabtranslate

import android.app.Application
import android.content.ComponentCallbacks2
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
import kotlinx.coroutines.cancel
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

    @Inject
    lateinit var serviceCoordinator: com.example.gloabtranslate.service.ServiceCoordinator

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
        
        // Initialize services through coordinator
        applicationScope.launch {
            serviceCoordinator.initializeServices()
        }
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
                    when (event.key) {
                        "enableDebugMode" -> DebugTools.setEnabled(event.newValue as? Boolean == true)
                        "enableAnalytics" -> DebugTools.setAnalyticsEnabled(event.newValue as? Boolean == true)
                    }
                }
        }

        applicationScope.launch {
            val debugEnabled = preferencesManager.getPreference("enableDebugMode") as? Boolean ?: false
            DebugTools.setEnabled(debugEnabled)

            val analyticsEnabled = preferencesManager.getPreference("enableAnalytics") as? Boolean ?: false
            DebugTools.setAnalyticsEnabled(analyticsEnabled)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // Note: onTerminate is not guaranteed to be called in production
        cleanupResources()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Cleanup resources only at the most aggressive trim level where the process is likely to be killed.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            cleanupResources()
        }
    }
    
    private fun cleanupResources() {
        try {
            // Shutdown services through coordinator
            if (::serviceCoordinator.isInitialized) {
                serviceCoordinator.shutdown()
            }
            
            // Cancel application scope coroutines
            applicationScope.cancel()
        } catch (e: Exception) {
            android.util.Log.e("GloabTranslateApplication", "Error during cleanup", e)
        }
    }
}
