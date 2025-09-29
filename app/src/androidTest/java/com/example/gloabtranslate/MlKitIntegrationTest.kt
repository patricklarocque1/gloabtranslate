package com.example.gloabtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gloabtranslate.nlp.RecognitionService
import com.example.gloabtranslate.nlp.RecognizerAvailabilityManager
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for ML Kit functionality.
 * These tests require a real Android device or emulator with Google Play Services.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MlKitIntegrationTest {

    private lateinit var context: Context
    private lateinit var recognitionService: RecognitionService
    private lateinit var availabilityManager: RecognizerAvailabilityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        recognitionService = RecognitionService(context)
        availabilityManager = RecognizerAvailabilityManager(context)
    }

    @After
    fun tearDown() {
        recognitionService.cleanup()
    }

    @Test
    fun `test Google Play Services availability`() = runTest {
        // When
        val isAvailable = availabilityManager.checkGooglePlayServicesAvailability()

        // Then
        // This test will pass if Google Play Services is available
        // and fail if not available (e.g., on emulator without Google Play)
        assertTrue(isAvailable, "Google Play Services should be available for ML Kit integration")
    }

    @Test
    fun `test recognition service initialization`() = runTest {
        // When
        val result = recognitionService.initialize()

        // Then
        assertTrue(result.success, "Recognition service should initialize successfully")
        assertNotNull(result.text, "Initialization result should have a message")
    }

    @Test
    fun `test language identification with English text`() = runTest {
        // Given
        recognitionService.initialize()
        val testText = "Hello world"

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertTrue(result.success, "Language identification should succeed")
        assertEquals(testText, result.text, "Original text should be preserved")
        assertNotNull(result.sourceLanguage, "Source language should be identified")
        assertEquals("en", result.sourceLanguage, "English text should be identified as 'en'")
    }

    @Test
    fun `test language identification with Spanish text`() = runTest {
        // Given
        recognitionService.initialize()
        val testText = "Hola mundo"

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertTrue(result.success, "Language identification should succeed")
        assertEquals(testText, result.text, "Original text should be preserved")
        assertNotNull(result.sourceLanguage, "Source language should be identified")
        assertEquals("es", result.sourceLanguage, "Spanish text should be identified as 'es'")
    }

    @Test
    fun `test language identification with French text`() = runTest {
        // Given
        recognitionService.initialize()
        val testText = "Bonjour le monde"

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertTrue(result.success, "Language identification should succeed")
        assertEquals(testText, result.text, "Original text should be preserved")
        assertNotNull(result.sourceLanguage, "Source language should be identified")
        assertEquals("fr", result.sourceLanguage, "French text should be identified as 'fr'")
    }

    @Test
    fun `test translation from English to Spanish`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = "Hello world"
        val sourceLanguage = "en"
        val targetLanguage = "es"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success, "Translation should succeed")
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertEquals(sourceLanguage, result.sourceLanguage, "Source language should be preserved")
        assertEquals(targetLanguage, result.targetLanguage, "Target language should be preserved")
        assertNotNull(result.translatedText, "Translated text should not be null")
        assertTrue(result.translatedText!!.isNotEmpty(), "Translated text should not be empty")
    }

    @Test
    fun `test translation from Spanish to English`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = "Hola mundo"
        val sourceLanguage = "es"
        val targetLanguage = "en"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success, "Translation should succeed")
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertEquals(sourceLanguage, result.sourceLanguage, "Source language should be preserved")
        assertEquals(targetLanguage, result.targetLanguage, "Target language should be preserved")
        assertNotNull(result.translatedText, "Translated text should not be null")
        assertTrue(result.translatedText!!.isNotEmpty(), "Translated text should not be empty")
    }

    @Test
    fun `test translation from English to French`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = "Good morning"
        val sourceLanguage = "en"
        val targetLanguage = "fr"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success, "Translation should succeed")
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertEquals(sourceLanguage, result.sourceLanguage, "Source language should be preserved")
        assertEquals(targetLanguage, result.targetLanguage, "Target language should be preserved")
        assertNotNull(result.translatedText, "Translated text should not be null")
        assertTrue(result.translatedText!!.isNotEmpty(), "Translated text should not be empty")
    }

    @Test
    fun `test translation with empty text`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = ""
        val sourceLanguage = "en"
        val targetLanguage = "es"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        // Empty text should still be processed (though result may vary)
        assertNotNull(result, "Result should not be null")
    }

    @Test
    fun `test translation with very long text`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = "This is a very long text that contains multiple sentences. " +
                "It is designed to test the translation capabilities with a substantial amount of content. " +
                "The text should be translated successfully without any issues. " +
                "This helps ensure that the ML Kit translation service can handle longer inputs properly."
        val sourceLanguage = "en"
        val targetLanguage = "es"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success, "Translation should succeed with long text")
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertNotNull(result.translatedText, "Translated text should not be null")
        assertTrue(result.translatedText!!.isNotEmpty(), "Translated text should not be empty")
    }

    @Test
    fun `test recognition capability determination`() = runTest {
        // When
        val capability = availabilityManager.determineRecognitionCapability()

        // Then
        assertNotNull(capability, "Recognition capability should be determined")
        assertTrue(
            capability == RecognizerAvailabilityManager.RecognitionCapability.ON_DEVICE_AVAILABLE ||
            capability == RecognizerAvailabilityManager.RecognitionCapability.CLOUD_ONLY ||
            capability == RecognizerAvailabilityManager.RecognitionCapability.UNAVAILABLE,
            "Capability should be one of the expected values"
        )
    }

    @Test
    fun `test capability status message`() = runTest {
        // Given
        availabilityManager.determineRecognitionCapability()

        // When
        val statusMessage = availabilityManager.getCapabilityMessage()

        // Then
        assertNotNull(statusMessage, "Status message should not be null")
        assertTrue(statusMessage.isNotEmpty(), "Status message should not be empty")
    }

    @Test
    fun `test recommended action`() = runTest {
        // Given
        availabilityManager.determineRecognitionCapability()

        // When
        val recommendedAction = availabilityManager.getRecommendedAction()

        // Then
        assertNotNull(recommendedAction, "Recommended action should not be null")
        assertTrue(recommendedAction.isNotEmpty(), "Recommended action should not be empty")
    }

    @Test
    fun `test on-device recognition availability`() = runTest {
        // Given
        availabilityManager.determineRecognitionCapability()

        // When
        val isOnDeviceAvailable = availabilityManager.isOnDeviceRecognitionAvailable()

        // Then
        // This test will pass regardless of the actual availability
        // as it tests the method doesn't throw exceptions
        assertNotNull(isOnDeviceAvailable, "On-device availability should be determined")
    }

    @Test
    fun `test cloud recognition availability`() = runTest {
        // Given
        availabilityManager.determineRecognitionCapability()

        // When
        val isCloudAvailable = availabilityManager.isCloudRecognitionAvailable()

        // Then
        // This test will pass regardless of the actual availability
        // as it tests the method doesn't throw exceptions
        assertNotNull(isCloudAvailable, "Cloud availability should be determined")
    }

    @Test
    fun `test recognition service status methods`() = runTest {
        // Given
        recognitionService.initialize()

        // When
        val capabilityStatus = recognitionService.getCapabilityStatus()
        val recommendedAction = recognitionService.getRecommendedAction()
        val isOnDeviceAvailable = recognitionService.isOnDeviceAvailable()
        val isCloudAvailable = recognitionService.isCloudAvailable()

        // Then
        assertNotNull(capabilityStatus, "Capability status should not be null")
        assertNotNull(recommendedAction, "Recommended action should not be null")
        assertNotNull(isOnDeviceAvailable, "On-device availability should be determined")
        assertNotNull(isCloudAvailable, "Cloud availability should be determined")
    }

    @Test
    fun `test multiple translations with same language pair`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceLanguage = "en"
        val targetLanguage = "es"
        val texts = listOf("Hello", "Good morning", "How are you?", "Thank you")

        // When & Then
        texts.forEach { text ->
            val result = recognitionService.translateText(text, sourceLanguage, targetLanguage)
            assertTrue(result.success, "Translation should succeed for: $text")
            assertEquals(text, result.text, "Original text should be preserved for: $text")
            assertNotNull(result.translatedText, "Translated text should not be null for: $text")
        }
    }

    @Test
    fun `test translation with special characters`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = "Hello! How are you? I'm fine, thank you. 😊"
        val sourceLanguage = "en"
        val targetLanguage = "es"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success, "Translation should succeed with special characters")
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertNotNull(result.translatedText, "Translated text should not be null")
    }

    @Test
    fun `test translation with numbers`() = runTest {
        // Given
        recognitionService.initialize()
        val sourceText = "I have 5 apples and 3 oranges"
        val sourceLanguage = "en"
        val targetLanguage = "es"

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success, "Translation should succeed with numbers")
        assertEquals(sourceText, result.text, "Original text should be preserved")
        assertNotNull(result.translatedText, "Translated text should not be null")
    }

    @Test
    fun `test concurrent language identifications`() = runTest {
        // Given
        recognitionService.initialize()
        val texts = listOf("Hello", "Hola", "Bonjour", "Guten Tag")

        // When
        val results = texts.map { text ->
            recognitionService.identifyLanguage(text)
        }

        // Then
        results.forEach { result ->
            assertTrue(result.success, "Language identification should succeed")
            assertNotNull(result.sourceLanguage, "Source language should be identified")
        }
    }

    @Test
    fun `test concurrent translations`() = runTest {
        // Given
        recognitionService.initialize()
        val translations = listOf(
            Triple("Hello", "en", "es"),
            Triple("Good morning", "en", "fr"),
            Triple("How are you?", "en", "de"),
            Triple("Thank you", "en", "it")
        )

        // When
        val results = translations.map { (text, source, target) ->
            recognitionService.translateText(text, source, target)
        }

        // Then
        results.forEach { result ->
            assertTrue(result.success, "Translation should succeed")
            assertNotNull(result.translatedText, "Translated text should not be null")
        }
    }

    @Test
    fun `test service cleanup`() = runTest {
        // Given
        recognitionService.initialize()

        // When
        recognitionService.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue(true, "Cleanup should complete successfully")
    }
}
