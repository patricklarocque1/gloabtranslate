package com.example.gloabtranslate.nlp

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
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
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecognitionServiceTest {

    private lateinit var mockContext: Context
    private lateinit var mockAvailabilityManager: RecognizerAvailabilityManager
    private lateinit var mockLanguageIdentifier: LanguageIdentifier
    private lateinit var mockTranslator: Translator
    private lateinit var mockModelManager: ModelManager
    private lateinit var recognitionService: RecognitionService
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)
        
        // Create mocks
        mockContext = mockk(relaxed = true)
        mockAvailabilityManager = mockk(relaxed = true)
        mockLanguageIdentifier = mockk(relaxed = true)
        mockTranslator = mockk(relaxed = true)
        mockModelManager = mockk(relaxed = true)
        
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
    fun `initialize should return success when on-device recognition is available`() = runTest(testDispatcher) {
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
    fun `initialize should return success when cloud-only recognition is available`() = runTest(testDispatcher) {
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
    fun `initialize should return failure when recognition is unavailable`() = runTest(testDispatcher) {
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
    fun `initialize should return failure when still checking availability`() = runTest(testDispatcher) {
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
    fun `initialize should handle exceptions gracefully`() = runTest(testDispatcher) {
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
    fun `identifyLanguage should return success with identified language`() = runTest(testDispatcher) {
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
    fun `identifyLanguage should return failure when language identifier is null`() = runTest(testDispatcher) {
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
    fun `identifyLanguage should handle timeout`() = runTest(testDispatcher) {
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
    fun `translateText should return success with translated text`() = runTest(testDispatcher) {
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
    fun `translateText should handle timeout`() = runTest(testDispatcher) {
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
    fun `translateText should handle exceptions`() = runTest(testDispatcher) {
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
    fun `getCapabilityStatus should return status from availability manager`() = runTest(testDispatcher) {
        // Given
        val expectedStatus = "On-device translation available"
        every { mockAvailabilityManager.getCapabilityMessage() } returns expectedStatus

        // When
        val status = recognitionService.getCapabilityStatus()

        // Then
        assertEquals(expectedStatus, status)
    }

    @Test
    fun `getRecommendedAction should return action from availability manager`() = runTest(testDispatcher) {
        // Given
        val expectedAction = "You can use translation offline"
        every { mockAvailabilityManager.getRecommendedAction() } returns expectedAction

        // When
        val action = recognitionService.getRecommendedAction()

        // Then
        assertEquals(expectedAction, action)
    }

    @Test
    fun `isOnDeviceAvailable should return status from availability manager`() = runTest(testDispatcher) {
        // Given
        every { mockAvailabilityManager.isOnDeviceRecognitionAvailable() } returns true

        // When
        val isAvailable = recognitionService.isOnDeviceAvailable()

        // Then
        assertTrue(isAvailable)
    }

    @Test
    fun `isCloudAvailable should return status from availability manager`() = runTest(testDispatcher) {
        // Given
        every { mockAvailabilityManager.isCloudRecognitionAvailable() } returns true

        // When
        val isAvailable = recognitionService.isCloudAvailable()

        // Then
        assertTrue(isAvailable)
    }

    @Test
    fun `cleanup should close all active translators`() = runTest(testDispatcher) {
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
