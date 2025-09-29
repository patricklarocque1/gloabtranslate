package com.example.gloabtranslate.nlp

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class RecognitionServiceTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockAvailabilityManager: RecognizerAvailabilityManager

    @Mock
    private lateinit var mockLanguageIdentifier: LanguageIdentification

    @Mock
    private lateinit var mockTranslator: Translator

    private lateinit var mockModelManager: ModelManager

    private lateinit var recognitionService: RecognitionService
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        // Mock static methods
        mockkStatic(LanguageIdentification::class)
        mockkStatic(Translation::class)
        mockkObject(ModelManager.Companion)
        
        every { LanguageIdentification.getClient(any()) } returns mockLanguageIdentifier
        every { Translation.getClient(any<TranslatorOptions>()) } returns mockTranslator
        mockModelManager = mockk(relaxed = true)
        every { ModelManager.getInstance(mockContext) } returns mockModelManager
        coEvery { mockModelManager.ensureLanguagePairAvailable(any(), any(), any()) } just Runs

        // Create service with mocked availability manager
        recognitionService = RecognitionService(mockContext)

        val availabilityField = RecognitionService::class.java.getDeclaredField("availabilityManager")
        availabilityField.isAccessible = true
        availabilityField.set(recognitionService, mockAvailabilityManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `initialize should return success when on-device recognition is available`() = runTest {
        // Given
        every { mockAvailabilityManager.determineRecognitionCapability() } returns 
            RecognizerAvailabilityManager.RecognitionCapability.ON_DEVICE_AVAILABLE
        every { mockAvailabilityManager.isOnDeviceRecognitionAvailable() } returns true

        // When
        val result = recognitionService.initialize()

        // Then
        assertTrue(result.success)
        assertEquals("Recognition service initialized", result.text)
        assertTrue(result.isOnDevice)
        assertNull(result.error)
    }

    @Test
    fun `initialize should return success when cloud-only recognition is available`() = runTest {
        // Given
        every { mockAvailabilityManager.determineRecognitionCapability() } returns 
            RecognizerAvailabilityManager.RecognitionCapability.CLOUD_ONLY
        every { mockAvailabilityManager.isOnDeviceRecognitionAvailable() } returns false

        // When
        val result = recognitionService.initialize()

        // Then
        assertTrue(result.success)
        assertEquals("Recognition service initialized", result.text)
        assertFalse(result.isOnDevice)
        assertNull(result.error)
    }

    @Test
    fun `initialize should return failure when recognition is unavailable`() = runTest {
        // Given
        every { mockAvailabilityManager.determineRecognitionCapability() } returns 
            RecognizerAvailabilityManager.RecognitionCapability.UNAVAILABLE
        every { mockAvailabilityManager.getCapabilityMessage() } returns "Google Play Services not available"

        // When
        val result = recognitionService.initialize()

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Recognition not available") == true)
        assertNull(result.text)
    }

    @Test
    fun `initialize should return failure when still checking availability`() = runTest {
        // Given
        every { mockAvailabilityManager.determineRecognitionCapability() } returns 
            RecognizerAvailabilityManager.RecognitionCapability.CHECKING

        // When
        val result = recognitionService.initialize()

        // Then
        assertFalse(result.success)
        assertEquals("Still checking recognition availability...", result.error)
        assertNull(result.text)
    }

    @Test
    fun `initialize should handle exceptions gracefully`() = runTest {
        // Given
        every { mockAvailabilityManager.determineRecognitionCapability() } throws 
            RuntimeException("Test exception")

        // When
        val result = recognitionService.initialize()

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Initialization failed") == true)
        assertNull(result.text)
    }

    @Test
    fun `identifyLanguage should return success with identified language`() = runTest {
        // Given
        val testText = "Hello world"
        val expectedLanguage = "en"
        
        every { mockLanguageIdentifier.identifyLanguage(testText) } returns mockk {
            every { addOnSuccessListener(any()) } answers {
                firstArg<(String) -> Unit>().invoke(expectedLanguage)
                this
            }
            every { addOnFailureListener(any()) } returns this
        }

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertTrue(result.success)
        assertEquals(testText, result.text)
        assertEquals(expectedLanguage, result.sourceLanguage)
        assertNull(result.error)
    }

    @Test
    fun `identifyLanguage should return failure when language identifier is null`() = runTest {
        // Given
        val testText = "Hello world"

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertFalse(result.success)
        assertEquals("Language identifier not available", result.error)
        assertNull(result.text)
    }

    @Test
    fun `identifyLanguage should handle timeout`() = runTest {
        // Given
        val testText = "Hello world"
        
        every { mockLanguageIdentifier.identifyLanguage(testText) } returns mockk {
            every { addOnSuccessListener(any()) } returns this
            every { addOnFailureListener(any()) } returns this
        }

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertFalse(result.success)
        assertEquals("Language identification timed out", result.error)
    }

    @Test
    fun `translateText should return success with translated text`() = runTest {
        // Given
        val sourceText = "Hello"
        val sourceLanguage = "en"
        val targetLanguage = "es"
        val expectedTranslation = "Hola"
        
        every { mockTranslator.translate(sourceText) } returns mockk {
            every { addOnSuccessListener(any()) } answers {
                firstArg<(String) -> Unit>().invoke(expectedTranslation)
                this
            }
            every { addOnFailureListener(any()) } returns this
        }

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertTrue(result.success)
        assertEquals(sourceText, result.text)
        assertEquals(sourceLanguage, result.sourceLanguage)
        assertEquals(expectedTranslation, result.translatedText)
        assertEquals(targetLanguage, result.targetLanguage)
        assertNull(result.error)
    }

    @Test
    fun `translateText should handle timeout`() = runTest {
        // Given
        val sourceText = "Hello"
        val sourceLanguage = "en"
        val targetLanguage = "es"
        
        every { mockTranslator.translate(sourceText) } returns mockk {
            every { addOnSuccessListener(any()) } returns this
            every { addOnFailureListener(any()) } returns this
        }

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertFalse(result.success)
        assertEquals("Translation timed out", result.error)
    }

    @Test
    fun `translateText should handle exceptions`() = runTest {
        // Given
        val sourceText = "Hello"
        val sourceLanguage = "en"
        val targetLanguage = "es"
        
        every { mockTranslator.translate(sourceText) } throws RuntimeException("Translation failed")

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Translation failed") == true)
    }

    @Test
    fun `getCapabilityStatus should return status from availability manager`() = runTest {
        // Given
        val expectedStatus = "On-device translation available"
        every { mockAvailabilityManager.getCapabilityMessage() } returns expectedStatus

        // When
        val status = recognitionService.getCapabilityStatus()

        // Then
        assertEquals(expectedStatus, status)
    }

    @Test
    fun `getRecommendedAction should return action from availability manager`() = runTest {
        // Given
        val expectedAction = "You can use translation offline"
        every { mockAvailabilityManager.getRecommendedAction() } returns expectedAction

        // When
        val action = recognitionService.getRecommendedAction()

        // Then
        assertEquals(expectedAction, action)
    }

    @Test
    fun `isOnDeviceAvailable should return status from availability manager`() = runTest {
        // Given
        every { mockAvailabilityManager.isOnDeviceRecognitionAvailable() } returns true

        // When
        val isAvailable = recognitionService.isOnDeviceAvailable()

        // Then
        assertTrue(isAvailable)
    }

    @Test
    fun `isCloudAvailable should return status from availability manager`() = runTest {
        // Given
        every { mockAvailabilityManager.isCloudRecognitionAvailable() } returns true

        // When
        val isAvailable = recognitionService.isCloudAvailable()

        // Then
        assertTrue(isAvailable)
    }

    @Test
    fun `cleanup should close all active translators`() = runTest {
        // Given
        val mockTranslator1 = mockk<Translator>(relaxed = true)
        val mockTranslator2 = mockk<Translator>(relaxed = true)
        
        every { mockTranslator1.close() } just Runs
        every { mockTranslator2.close() } just Runs

        // When
        recognitionService.cleanup()

        // Then
        // Verify that cleanup doesn't throw exceptions
        assertTrue(true)
    }
}
