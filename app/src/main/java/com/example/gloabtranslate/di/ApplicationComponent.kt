package com.example.gloabtranslate.di

import android.app.Application
import com.example.gloabtranslate.GloabTranslateApplication
import com.example.gloabtranslate.di.modules.ActivityModule
import com.example.gloabtranslate.di.modules.AppModule
import com.example.gloabtranslate.di.modules.CoreModule
import com.example.gloabtranslate.di.modules.FragmentModule
import com.example.gloabtranslate.di.modules.NlpModule
import com.example.gloabtranslate.di.modules.SpeechModule
import com.example.gloabtranslate.di.modules.TtsModule
import dagger.BindsInstance
import dagger.Component
import dagger.android.AndroidInjectionModule
import dagger.android.support.AndroidSupportInjectionModule
import dagger.android.AndroidInjector
import javax.inject.Singleton

/**
 * Main Dagger component for the application.
 * Provides dependencies throughout the app and handles Android injection.
 */
@Singleton
@Component(
    modules = [
        AndroidInjectionModule::class,
        AndroidSupportInjectionModule::class,
        ActivityModule::class,
        AppModule::class,
        CoreModule::class,
        FragmentModule::class,
        NlpModule::class,
        SpeechModule::class,
        TtsModule::class
    ]
)
interface ApplicationComponent : AndroidInjector<GloabTranslateApplication> {

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance application: Application): ApplicationComponent
    }
}
