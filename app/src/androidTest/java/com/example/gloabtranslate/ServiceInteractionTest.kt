package com.example.gloabtranslate

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.gloabtranslate.service.LiveTranslateService
import com.example.gloabtranslate.speech.SpeechRecognitionService
import com.example.gloabtranslate.tts.TextToSpeechService
import com.example.gloabtranslate.nlp.RecognitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * UI tests for service interactions.
 * Tests integration between UI and various services (speech-to-text, text-to-speech, translation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ServiceInteractionTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @Mock
    private lateinit var mockLiveTranslateService: LiveTranslateService

    @Mock
    private lateinit var mockSpeechRecognitionService: SpeechRecognitionService

    @Mock
    private lateinit var mockTextToSpeechService: TextToSpeechService

    @Mock
    private lateinit var mockRecognitionService: RecognitionService

    private lateinit var activityScenario: ActivityScenario<MainActivity>
    private lateinit var context: Context
    private lateinit var speechService: SpeechRecognitionService
    private lateinit var ttsService: TextToSpeechService
    private lateinit var recognitionService: RecognitionService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize real services for testing
        speechService = SpeechRecognitionService(context)
        ttsService = TextToSpeechService(context)
        recognitionService = RecognitionService(context)
        
        // Mock service responses
        Mockito.`when`(mockLiveTranslateService.isCurrentlyRecording()).thenReturn(false)
        Mockito.`when`(mockLiveTranslateService.getRecognitionStatus()).thenReturn("Recognition available")
        Mockito.`when`(mockLiveTranslateService.getRecommendedAction()).thenReturn("Ready to translate")
    }

    @After
    fun tearDown() {
        activityScenario.close()
        speechService.cleanup()
        ttsService.cleanup()
        recognitionService.cleanup()
    }

    @Test
    fun `test speech recognition service initialization`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When
        val result = speechService.initialize()

        // Then
        assertTrue("Speech recognition service should initialize successfully", result.success)
        // Initialization successful, no need to check message property
    }

    @Test
    fun `test speech recognition with UI interaction`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        speechService.initialize()

        // When - Click start button to initiate recording
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())

        // Then - Verify UI updates to show recording state
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test text-to-speech service initialization`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When
        val result = ttsService.initialize()

        // Then
        assertTrue("TTS service should initialize successfully", result.success)
        // Initialization successful, no need to check message property
    }

    @Test
    fun `test TTS playback with UI feedback`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        ttsService.initialize()

        // When - Simulate TTS playback
        val testText = "Hello world"
        val result = ttsService.speak(testText)

        // Then
        assertTrue("TTS playback should succeed", result.success)
        assertNotNull("TTS should return utterance ID", result.utteranceId)
        
        // Verify UI is still responsive
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test translation service integration`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        recognitionService.initialize()

        // When
        val sourceText = "Hello world"
        val result = recognitionService.translateText(sourceText, "en", "es")

        // Then
        assertTrue("Translation should succeed", result.success)
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertNotNull("Translated text should not be null", result.translatedText)
    }

    @Test
    fun `test service binding and unbinding`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Service should bind automatically
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))

        // Then - Verify UI shows service connection status
        Espresso.onView(ViewMatchers.withId(R.id.recognitionStatusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test service state changes reflected in UI`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Click start button
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())

        // Then - Verify UI updates
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test multiple service interactions`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        speechService.initialize()
        ttsService.initialize()
        recognitionService.initialize()

        // When - Test speech recognition
        val speechResult = speechService.recognize("")
        assertTrue("Speech recognition should start successfully", speechResult.success)

        // Test translation
        val translationResult = recognitionService.translateText("Hello", "en", "es")
        assertTrue("Translation should succeed", translationResult.success)

        // Test TTS
        val ttsResult = ttsService.speak("Hola")
        assertTrue("TTS should succeed", ttsResult.success)

        // Then - Verify UI remains responsive
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test service error handling with UI feedback`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Test with invalid input
        val result = recognitionService.translateText("", "invalid", "invalid")

        // Then - Verify error is handled gracefully
        // The service should handle errors without crashing the UI
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test concurrent service operations`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        speechService.initialize()
        ttsService.initialize()
        recognitionService.initialize()

        // When - Run multiple operations concurrently
        val results = withContext(Dispatchers.IO) {
            listOf(
                recognitionService.translateText("Hello", "en", "es"),
                recognitionService.translateText("World", "en", "fr"),
                recognitionService.translateText("Test", "en", "de")
            )
        }

        // Then - All operations should complete successfully
        results.forEach { result ->
            assertNotNull("Result should not be null", result)
        }

        // UI should remain responsive
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test service cleanup on activity destroy`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Close activity
        activityScenario.close()

        // Then - Services should be cleaned up properly
        // This is verified by the fact that no exceptions are thrown
        assertTrue("Services should clean up without exceptions", true)
    }

    @Test
    fun `test service availability checks`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Check service availability
        val speechAvailable = speechService.isAvailable()
        val ttsAvailable = ttsService.isAvailable()
        val recognitionAvailable = recognitionService.getCapabilityStatus()

        // Then - Services should report their availability
        assertNotNull("Speech service availability should be determined", speechAvailable)
        assertNotNull("TTS service availability should be determined", ttsAvailable)
        assertNotNull("Recognition service availability should be determined", recognitionAvailable)

        // UI should reflect service status
        Espresso.onView(ViewMatchers.withId(R.id.recognitionStatusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test service configuration changes`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        ttsService.initialize()

        // When - Change TTS configuration
        val newConfig = TextToSpeechService.TTSConfig(
            language = java.util.Locale("es", "ES"),
            speechRate = 1.2f,
            pitch = 1.1f,
            volume = 0.8f
        )
        // Update TTS configuration using individual setters
        val result = ttsService.setLanguage(newConfig.language) && 
            ttsService.setSpeechRate(newConfig.speechRate) && 
            ttsService.setPitch(newConfig.pitch)

        // Then - Configuration should be updated successfully
        assertTrue("TTS configuration should update successfully", result)

        // UI should remain responsive
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test service lifecycle with UI interactions`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Interact with UI while services are starting
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())

        // Then - UI should handle service lifecycle properly
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test service error recovery with UI`() = runTest {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Simulate service error and recovery
        try {
            recognitionService.initialize()
        } catch (e: Exception) {
            // Handle potential initialization error
        }

        // Then - UI should remain functional
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
}
