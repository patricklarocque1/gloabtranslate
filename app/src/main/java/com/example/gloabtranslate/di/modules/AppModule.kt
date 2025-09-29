package com.example.gloabtranslate.di.modules

import android.content.Context
import com.example.gloabtranslate.service.LifecycleManager
import dagger.Module
import dagger.Provides
import android.app.Application
import javax.inject.Singleton

/**
 * Main application module providing core application dependencies.
 */
@Module
object AppModule {

    @Provides
    @Singleton
    fun provideContext(application: Application): Context {
        return application.applicationContext
    }

    @Provides
    @Singleton
    fun provideLifecycleManager(context: Context): LifecycleManager {
        return LifecycleManager.getInstance(context)
    }
}
