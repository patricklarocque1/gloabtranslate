package com.example.gloabtranslate.speech

import android.content.Context
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AudioPreferencesObserverTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: UserPreferencesManager

    @Before
    fun setUp() = runTest {
        context = RuntimeEnvironment.getApplication()
        preferencesManager = UserPreferencesManager.getInstance(context)
        preferencesManager.initialize()
        preferencesManager.resetPreferences()
    }

    @After
    fun tearDown() = runTest {
        preferencesManager.resetPreferences()
    }

    @Test
    fun audioRecorderTracksUpdatedPreferencesWithoutReinitialize() = runTest {
        val recorder = AudioRecorder(context)
        val initial = recorder.getRecordingConfig()
        assertEquals(16000, initial.sampleRate)

        preferencesManager.setPreference("sampleRate", 44100)
        preferencesManager.setPreference("audioBufferSize", 2048)

        withTimeout(1_000) {
            while (true) {
                val config = recorder.getRecordingConfig()
                if (config.sampleRate == 44100 && config.bufferSize == 2048) return@withTimeout
                delay(10)
            }
        }

        val updated = recorder.getRecordingConfig()
        assertEquals(44100, updated.sampleRate)
        assertEquals(2048, updated.bufferSize)

        recorder.cleanup()
    }

    @Test
    fun audioProcessorUpdatesActiveConfigWhenPreferencesChange() = runTest {
        val processor = AudioProcessor(context)
        processor.initialize()

        preferencesManager.setPreference("sampleRate", 22050)
        preferencesManager.setPreference("audioBufferSize", 4096)
        preferencesManager.setPreference("enableNoiseReduction", false)

        withTimeout(1_000) {
            while (true) {
                val config = processor.getProcessingConfig()
                if (config.sampleRate == 22050 && config.bufferSize == 4096) {
                    break
                }
                delay(10)
            }
        }

        val updated = processor.getProcessingConfig()
        assertEquals(22050, updated.sampleRate)
        assertEquals(4096, updated.bufferSize)
        assertFalse(updated.enableNoiseReduction)

        processor.cleanup()
    }
}
