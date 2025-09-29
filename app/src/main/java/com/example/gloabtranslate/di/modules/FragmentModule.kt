package com.example.gloabtranslate.di.modules

import com.example.gloabtranslate.MainFragment
import com.example.gloabtranslate.ui.history.TranslationHistoryFragment
import com.example.gloabtranslate.ui.language.LanguageSelectionFragment
import com.example.gloabtranslate.ui.transcription.TranscriptionFragment
import com.example.gloabtranslate.ui.translation.TranslationResultFragment
import com.example.gloabtranslate.di.FragmentScope
import dagger.Module
import dagger.android.ContributesAndroidInjector

/**
 * Fragment module for Android injection.
 */
@Module
abstract class FragmentModule {

    @FragmentScope
    @ContributesAndroidInjector
    abstract fun contributeMainFragment(): MainFragment

    @FragmentScope
    @ContributesAndroidInjector
    abstract fun contributeTranslationHistoryFragment(): TranslationHistoryFragment

    @FragmentScope
    @ContributesAndroidInjector
    abstract fun contributeLanguageSelectionFragment(): LanguageSelectionFragment

    @FragmentScope
    @ContributesAndroidInjector
    abstract fun contributeTranscriptionFragment(): TranscriptionFragment

    @FragmentScope
    @ContributesAndroidInjector
    abstract fun contributeTranslationResultFragment(): TranslationResultFragment
}
