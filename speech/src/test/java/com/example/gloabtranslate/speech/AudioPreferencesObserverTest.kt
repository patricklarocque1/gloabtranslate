package com.example.gloabtranslate.speech

import android.content.Context
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioPreferencesObserverTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: UserPreferencesManager
    private lateinit var configurationManager: ConfigurationManager

    @Before
    fun setUp() = runTest {
        context = RuntimeEnvironment.getApplication()
    preferencesManager = UserPreferencesManager.getInstance(context)
    configurationManager = ConfigurationManager(preferencesManager)
        preferencesManager.initialize()
        preferencesManager.resetPreferences()
    }

    @After
    fun tearDown() = runTest {
        preferencesManager.resetPreferences()
    }

    @Test
    fun audioRecorderTracksUpdatedPreferencesWithoutReinitialize() = runTest {
        val recorder = AudioRecorder(context, configurationManager)
        val initial = recorder.getRecordingConfig()
        assertEquals(16000, initial.sampleRate)

        preferencesManager.setPreference("sampleRate", 44100)
        preferencesManager.setPreference("audioBufferSize", 2048)

        // Give more time for the configuration observer to process changes
        withTimeout(5_000) {
            while (true) {
                val config = recorder.getRecordingConfig()
                if (config.sampleRate == 44100 && config.bufferSize == 2048) return@withTimeout
                delay(50) // Increase delay to reduce polling frequency
            }
        }

        val updated = recorder.getRecordingConfig()
        assertEquals(44100, updated.sampleRate)
        assertEquals(2048, updated.bufferSize)

    // recorder has no explicit close method after refactor; relying on GC
    }

    @Test
    fun audioProcessorUpdatesActiveConfigWhenPreferencesChange() = runTest {
        val processor = AudioProcessor(context, configurationManager)
        processor.initialize()

        preferencesManager.setPreference("sampleRate", 22050)
        preferencesManager.setPreference("audioBufferSize", 4096)
        preferencesManager.setPreference("enableNoiseReduction", false)

        // Give more time for the configuration observer to process changes
        withTimeout(5_000) {
            while (true) {
                val config = processor.getProcessingConfig()
                if (config.sampleRate == 22050 && config.bufferSize == 4096) {
                    break
                }
                delay(50) // Increase delay to reduce polling frequency
            }
        }

        val updated = processor.getProcessingConfig()
        assertEquals(22050, updated.sampleRate)
        assertEquals(4096, updated.bufferSize)
        assertFalse(updated.enableNoiseReduction)

        processor.close()
    }
}
