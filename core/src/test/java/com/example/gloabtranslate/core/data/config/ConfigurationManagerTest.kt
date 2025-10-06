package com.example.gloabtranslate.core.data.config

import android.content.Context
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigurationManagerTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: UserPreferencesManager
    private lateinit var configurationManager: ConfigurationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferencesManager = UserPreferencesManager.getInstance(context)
        runBlocking {
            preferencesManager.initialize()
            preferencesManager.resetPreferences()
        }
    configurationManager = ConfigurationManager(preferencesManager)
    }

    @After
    fun tearDown() {
        runBlocking {
            preferencesManager.resetPreferences()
        }
    }

    @Test
    fun audioConfigReflectsPreferenceChanges() = runBlocking {
        val initial = configurationManager.audioConfig.first()
        assertEquals(16000, initial.sampleRate)

        assertTrue(preferencesManager.setPreference("sampleRate", 44100))
        assertTrue(preferencesManager.setPreference("audioBufferSize", 2048))

        val updated = configurationManager.audioConfig.first { config ->
            config.sampleRate == 44100 && config.audioBufferSize == 2048
        }

        assertEquals(44100, updated.sampleRate)
        assertEquals(2048, updated.audioBufferSize)
    }

    @Test
    fun translationConfigUpdatesHistoryFlag() = runBlocking {
        val initial = configurationManager.translationConfig.first()
        assertTrue(initial.enableHistory)

        assertTrue(preferencesManager.setPreference("enableHistory", false))

        val updated = configurationManager.translationConfig.first { !it.enableHistory }
        assertFalse(updated.enableHistory)
    }
}
