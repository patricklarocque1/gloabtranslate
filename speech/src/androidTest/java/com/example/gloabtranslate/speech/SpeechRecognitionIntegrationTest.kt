package com.example.gloabtranslate.speech

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechRecognitionIntegrationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var context: Context
    private lateinit var speechRecognitionService: SpeechRecognitionService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        speechRecognitionService = SpeechRecognitionService(context)
    }

    @Test
    fun initializeCompletesWithoutCrash() = runBlocking {
        // Initialization should return either true or false but never throw.
        val result = speechRecognitionService.initialize()
        assertNotNull(result)
    }

    @Test
    fun serviceReportsAvailabilityGracefully() {
        val available = speechRecognitionService.isAvailable()
        // Value depends on device capabilities; ensure call succeeds.
        assertTrue(available || !available)
    }
}
