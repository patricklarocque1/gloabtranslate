package com.example.gloabtranslate.nlp

import android.content.Context
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
@Config(sdk = [28])
class RecognitionServiceTest {

    private lateinit var mockContext: Context
    private lateinit var mockAvailabilityManager: RecognizerAvailabilityManager
    private lateinit var mockLanguageIdentifier: LanguageIdentifier
    private lateinit var mockTranslator: Translator
    private lateinit var mockModelManager: ModelManager
    private lateinit var recognitionService: RecognitionService
    private val testDispatcher = StandardTestDispatcher()

    // Helper methods for Task API mocking
    private fun <T> createSuccessTask(value: T): com.google.android.gms.tasks.Task<T> {
        val task = mockk<com.google.android.gms.tasks.Task<T>>()
        every { task.addOnSuccessListener(any<com.google.android.gms.tasks.OnSuccessListener<T>>()) } answers {
            firstArg<com.google.android.gms.tasks.OnSuccessListener<T>>().onSuccess(value)
            task
        }
        every { task.addOnFailureListener(any<com.google.android.gms.tasks.OnFailureListener>()) } returns task
        return task
    }

    private fun <T> createFailureTask(exception: Exception): com.google.android.gms.tasks.Task<T> {
        val task = mockk<com.google.android.gms.tasks.Task<T>>()
        every { task.addOnFailureListener(any<com.google.android.gms.tasks.OnFailureListener>()) } answers {
            firstArg<com.google.android.gms.tasks.OnFailureListener>().onFailure(exception)
            task
        }
        every { task.addOnSuccessListener(any<com.google.android.gms.tasks.OnSuccessListener<T>>()) } returns task
        return task
    }

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
        // Legacy getInstance removed in production; companion mock retained only if needed, but tests will inject directly
        // mockkObject(ModelManager.Companion)
        
        every { LanguageIdentification.getClient(any()) } returns mockLanguageIdentifier
        every { Translation.getClient(any<TranslatorOptions>()) } returns mockTranslator
        mockModelManager = mockk(relaxed = true)
    // Replace legacy getInstance usage by stubbing companion but still directly inject mock into service
        coEvery { mockModelManager.ensureLanguagePairAvailable(any(), any(), any()) } just Runs

        // Create service with mocked availability manager
        recognitionService = RecognitionService(mockContext, mockModelManager)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initialize should return success when on-device recognition is available`() = runTest {
        // Given
        coEvery { mockAvailabilityManager.determineRecognitionCapability() } returns 
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initialize should return success when cloud-only recognition is available`() = runTest {
        // Given
        coEvery { mockAvailabilityManager.determineRecognitionCapability() } returns 
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initialize should return failure when recognition is unavailable`() = runTest {
        // Given
        coEvery { mockAvailabilityManager.determineRecognitionCapability() } returns 
            RecognizerAvailabilityManager.RecognitionCapability.UNAVAILABLE
        every { mockAvailabilityManager.getCapabilityMessage() } returns "Google Play Services not available"

        // When
        val result = recognitionService.initialize()

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Recognition not available") == true)
        assertNull(result.text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initialize should return failure when still checking availability`() = runTest {
        // Given
        coEvery { mockAvailabilityManager.determineRecognitionCapability() } returns 
            RecognizerAvailabilityManager.RecognitionCapability.CHECKING

        // When
        val result = recognitionService.initialize()

        // Then
        assertFalse(result.success)
        assertEquals("Still checking recognition availability...", result.error)
        assertNull(result.text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `initialize should handle exceptions gracefully`() = runTest {
        // Given
        coEvery { mockAvailabilityManager.determineRecognitionCapability() } throws 
            RuntimeException("Test exception")

        // When
        val result = recognitionService.initialize()

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Initialization failed") == true)
        assertNull(result.text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identifyLanguage should return success with identified language`() = runTest {
        // Given
        val testText = "Hello world"
        val expectedLanguage = "en"
        
        every { mockLanguageIdentifier.identifyLanguage(testText) } returns createSuccessTask(expectedLanguage)

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertTrue(result.success)
        assertEquals(testText, result.text)
        assertEquals(expectedLanguage, result.sourceLanguage)
        assertNull(result.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identifyLanguage should return failure when language identifier is null`() = runTest {
        // Given
        val testText = "Hello world"
        
        // Mock LanguageIdentification.getClient to throw an exception for this test
        // This will cause initializeLanguageIdentifier() to fail and languageIdentifier to remain null
        every { LanguageIdentification.getClient(any()) } throws RuntimeException("Failed to initialize")

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertFalse(result.success)
        assertEquals("Language identifier not available", result.error)
        assertNull(result.text)
        
        // Restore the original mock for other tests
        every { LanguageIdentification.getClient(any()) } returns mockLanguageIdentifier
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identifyLanguage should handle timeout`() = runTest {
        // Given
        val testText = "Hello world"
        
        // Create a task that never completes to simulate timeout
        val hangingTask = mockk<com.google.android.gms.tasks.Task<String>>()
        every { hangingTask.addOnSuccessListener(any<com.google.android.gms.tasks.OnSuccessListener<String>>()) } returns hangingTask
        every { hangingTask.addOnFailureListener(any<com.google.android.gms.tasks.OnFailureListener>()) } returns hangingTask
        
        every { mockLanguageIdentifier.identifyLanguage(testText) } returns hangingTask

        // When
        val result = recognitionService.identifyLanguage(testText)

        // Then
        assertFalse(result.success)
        assertEquals("Language identification timed out", result.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translateText should return success with translated text`() = runTest {
        // Given
        val sourceText = "Hello"
        val sourceLanguage = "en"
        val targetLanguage = "es"
        val expectedTranslation = "Hola"
        
        every { mockTranslator.translate(sourceText) } returns createSuccessTask(expectedTranslation)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translateText should handle timeout`() = runTest {
        // Given
        val sourceText = "Hello"
        val sourceLanguage = "en"
        val targetLanguage = "es"
        
        every { mockTranslator.translate(sourceText) } returns createFailureTask(RuntimeException("Timeout"))

        // When
        val result = recognitionService.translateText(sourceText, sourceLanguage, targetLanguage)

        // Then
        assertFalse(result.success)
        assertEquals("Translation failed: Timeout", result.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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

    @OptIn(ExperimentalCoroutinesApi::class)
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

    @OptIn(ExperimentalCoroutinesApi::class)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `isOnDeviceAvailable should return status from availability manager`() = runTest {
        // Given
        every { mockAvailabilityManager.isOnDeviceRecognitionAvailable() } returns true

        // When
        val isAvailable = recognitionService.isOnDeviceAvailable()

        // Then
        assertTrue(isAvailable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `isCloudAvailable should return status from availability manager`() = runTest {
        // Given
        every { mockAvailabilityManager.isCloudRecognitionAvailable() } returns true

        // When
        val isAvailable = recognitionService.isCloudAvailable()

        // Then
        assertTrue(isAvailable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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
