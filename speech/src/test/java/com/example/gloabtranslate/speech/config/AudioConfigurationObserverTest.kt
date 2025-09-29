package com.example.gloabtranslate.speech.config

import android.content.Context
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
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
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
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
        observer = AudioConfigurationObserver(context)
    }

    @After
    fun tearDown() = runTest {
        observer.cleanup()
        preferencesManager.resetPreferences()
    }

    @Test
    fun emitsCriticalAndNonCriticalUpdates() = runTest {
        val criticalEvents = Channel<Int>(Channel.BUFFERED)
        val nonCriticalEvents = Channel<Int>(Channel.BUFFERED)

        observer.setOnCriticalConfigChanged { config ->
            criticalEvents.trySend(config.sampleRate)
        }
        observer.setOnConfigChanged { config ->
            nonCriticalEvents.trySend(config.audioBufferSize)
        }
        observer.start()

        // Initial critical emission
        withTimeout(1_000) { criticalEvents.receive() }

        // Critical change
        preferencesManager.setPreference("sampleRate", 44100)
        val criticalSampleRate = withTimeout(1_000) { criticalEvents.receive() }
        assertEquals(44100, criticalSampleRate)

        // Non-critical change
        preferencesManager.setPreference("audioBufferSize", 2048)
        val bufferSize = withTimeout(1_000) { nonCriticalEvents.receive() }
        assertEquals(2048, bufferSize)

        criticalEvents.close()
        nonCriticalEvents.close()
    }
}
