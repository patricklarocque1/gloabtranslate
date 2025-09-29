package com.example.gloabtranslate.di.modules

import com.example.gloabtranslate.MainActivity
import com.example.gloabtranslate.di.ActivityScope
import dagger.Module
import dagger.android.ContributesAndroidInjector

/**
 * Activity module for Android injection.
 */
@Module
abstract class ActivityModule {

    @ActivityScope
    @ContributesAndroidInjector(modules = [FragmentModule::class])
    abstract fun contributeMainActivity(): MainActivity
}
