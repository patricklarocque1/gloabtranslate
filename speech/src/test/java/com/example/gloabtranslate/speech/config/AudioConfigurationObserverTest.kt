package com.example.gloabtranslate.speech.config

import android.content.Context
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioConfigurationObserverTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: UserPreferencesManager
    private lateinit var observer: AudioConfigurationObserver

    @Before
    fun setUp() = runTest {
        context = RuntimeEnvironment.getApplication()
    preferencesManager = UserPreferencesManager.getInstance(context)
        preferencesManager.initialize()
        preferencesManager.resetPreferences()
    // Provide a configuration manager instance if observer evolves to expect DI in future
    observer = AudioConfigurationObserver(context)
    }

    @After
    fun tearDown() = runTest {
        observer.cleanup()
        preferencesManager.resetPreferences()
    }

    @Test
    fun emitsCriticalAndNonCriticalUpdates() = runTest {
        val criticalConfigChannel = Channel<Int>(Channel.CONFLATED)
        val nonCriticalConfigChannel = Channel<Int>(Channel.CONFLATED)

        observer.setOnCriticalConfigChanged { config ->
            criticalConfigChannel.trySend(config.sampleRate)
        }
        observer.setOnConfigChanged { config ->
            nonCriticalConfigChannel.trySend(config.audioBufferSize)
        }
        observer.start()

        // Make preference changes
        preferencesManager.setPreference("sampleRate", 44100)
        preferencesManager.setPreference("audioBufferSize", 2048)

        // Assert that the callbacks were invoked with the correct values
        withTimeout(1000) {
            assertEquals(44100, criticalConfigChannel.receive())
            assertEquals(2048, nonCriticalConfigChannel.receive())
        }
    }
}
