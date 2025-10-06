package com.example.gloabtranslate.nlp

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.OnFailureListener
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecognizerAvailabilityManagerTest {

    private lateinit var mockContext: Context

    private lateinit var mockGoogleApiAvailability: GoogleApiAvailability
    private lateinit var mockLanguageIdentifier: LanguageIdentifier
    private lateinit var mockTranslator: Translator
    private lateinit var mockModelManager: ModelManager

    private lateinit var availabilityManager: RecognizerAvailabilityManager
    private val testDispatcher = UnconfinedTestDispatcher()

    // Helper method to create mock Task with success callback
    private fun <T> createSuccessTask(result: T): Task<T> {
        return mockk<Task<T>>(relaxed = true) {
            every { addOnSuccessListener(any<OnSuccessListener<T>>()) } answers {
                firstArg<OnSuccessListener<T>>().onSuccess(result)
                this@mockk
            }
            every { addOnFailureListener(any<OnFailureListener>()) } returns this@mockk
        }
    }

    // Helper method to create mock Task with failure callback
    private fun <T> createFailureTask(exception: Exception): Task<T> {
        return mockk<Task<T>>(relaxed = true) {
            every { addOnSuccessListener(any<OnSuccessListener<T>>()) } returns this@mockk
            every { addOnFailureListener(any<OnFailureListener>()) } answers {
                firstArg<OnFailureListener>().onFailure(exception)
                this@mockk
            }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        // Create mocks
        mockContext = mockk(relaxed = true)
        mockGoogleApiAvailability = mockk(relaxed = true)
        mockLanguageIdentifier = mockk(relaxed = true)
        mockTranslator = mockk(relaxed = true)
        mockModelManager = mockk(relaxed = true)
        
        // Mock static methods
        mockkStatic(GoogleApiAvailability::class)
        mockkStatic(LanguageIdentification::class)
        mockkStatic(Translation::class)
    // Legacy companion getInstance not used; direct injection by reflection
        
        every { GoogleApiAvailability.getInstance() } returns mockGoogleApiAvailability
        every { LanguageIdentification.getClient() } returns mockLanguageIdentifier
        every { Translation.getClient(any<TranslatorOptions>()) } returns mockTranslator
        coEvery { mockModelManager.ensureLanguagePairAvailable(any(), any(), any()) } just Runs
        every { mockTranslator.downloadModelIfNeeded(any()) } returns createSuccessTask(null)
        
        availabilityManager = RecognizerAvailabilityManager(mockContext, mockModelManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `checkGooglePlayServicesAvailability should return true when services are available`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS

        // When
        val result = availabilityManager.checkGooglePlayServicesAvailability()

        // Then
        assertTrue(result)
    }

    @Test
    fun `checkGooglePlayServicesAvailability should return false when services are missing`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SERVICE_MISSING

        // When
        val result = availabilityManager.checkGooglePlayServicesAvailability()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkGooglePlayServicesAvailability should return false when services are disabled`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SERVICE_DISABLED

        // When
        val result = availabilityManager.checkGooglePlayServicesAvailability()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkGooglePlayServicesAvailability should handle exceptions`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } throws 
            RuntimeException("Test exception")

        // When
        val result = availabilityManager.checkGooglePlayServicesAvailability()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkOnDeviceLanguageIdAvailability should return true when language ID works`() = runTest {
        // Given
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createSuccessTask("en")

        // When
        val result = availabilityManager.checkOnDeviceLanguageIdAvailability()

        // Then
        assertTrue(result)
    }

    @Test
    fun `checkOnDeviceLanguageIdAvailability should return false when language ID fails`() = runTest {
        // Given
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        val result = availabilityManager.checkOnDeviceLanguageIdAvailability()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkOnDeviceLanguageIdAvailability should handle exceptions`() = runTest {
        // Given
        every { LanguageIdentification.getClient() } throws RuntimeException("Test exception")

        // When
        val result = availabilityManager.checkOnDeviceLanguageIdAvailability()

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkOnDeviceTranslationAvailability should return true when translation works`() = runTest {
        // Given
        every { mockTranslator.translate("Hello") } returns createSuccessTask("Hola")

        // When
        val result = availabilityManager.checkOnDeviceTranslationAvailability("en", "es")

        // Then
        assertTrue(result)
    }

    @Test
    fun `checkOnDeviceTranslationAvailability should return false when translation fails`() = runTest {
        // Given
        every { mockTranslator.translate("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        val result = availabilityManager.checkOnDeviceTranslationAvailability("en", "es")

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkOnDeviceTranslationAvailability should return false when model download fails`() = runTest {
        // Given
        coEvery { mockModelManager.ensureLanguagePairAvailable(any(), any(), any()) } throws 
            MlKitException("Model download failed", 1)

        // When
        val result = availabilityManager.checkOnDeviceTranslationAvailability("en", "es")

        // Then
        assertFalse(result)
    }

    @Test
    fun `checkOnDeviceTranslationAvailability should handle exceptions`() = runTest {
        // Given
        every { Translation.getClient(any<TranslatorOptions>()) } throws 
            RuntimeException("Test exception")

        // When
        val result = availabilityManager.checkOnDeviceTranslationAvailability("en", "es")

        // Then
        assertFalse(result)
    }

    @Test
    fun `determineRecognitionCapability should return UNAVAILABLE when Google Play Services not available`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SERVICE_MISSING

        // When
        val result = availabilityManager.determineRecognitionCapability()

        // Then
        assertEquals(RecognizerAvailabilityManager.RecognitionCapability.UNAVAILABLE, result)
    }

    @Test
    fun `determineRecognitionCapability should return CLOUD_ONLY when language ID not available`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        val result = availabilityManager.determineRecognitionCapability()

        // Then
        assertEquals(RecognizerAvailabilityManager.RecognitionCapability.CLOUD_ONLY, result)
    }

    @Test
    fun `determineRecognitionCapability should return ON_DEVICE_AVAILABLE when both language ID and translation work`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createSuccessTask("en")
        every { mockTranslator.downloadModelIfNeeded(any()) } returns createSuccessTask(null)
        every { mockTranslator.translate("Hello") } returns createSuccessTask("Hola")

        // When
        val result = availabilityManager.determineRecognitionCapability()

        // Then
        assertEquals(RecognizerAvailabilityManager.RecognitionCapability.ON_DEVICE_AVAILABLE, result)
    }

    @Test
    fun `determineRecognitionCapability should return CLOUD_ONLY when language ID works but translation fails`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createSuccessTask("en")
        every { mockTranslator.translate("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        val result = availabilityManager.determineRecognitionCapability()

        // Then
        assertEquals(RecognizerAvailabilityManager.RecognitionCapability.CLOUD_ONLY, result)
    }

    @Test
    fun `isOnDeviceRecognitionAvailable should return true when capability is ON_DEVICE_AVAILABLE`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createSuccessTask("en")
        every { mockTranslator.downloadModelIfNeeded(any()) } returns createSuccessTask(null)
        every { mockTranslator.translate("Hello") } returns createSuccessTask("Hola")

        // When
        availabilityManager.determineRecognitionCapability()
        val result = availabilityManager.isOnDeviceRecognitionAvailable()

        // Then
        assertTrue(result)
    }

    @Test
    fun `isCloudRecognitionAvailable should return true when capability is CLOUD_ONLY`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        availabilityManager.determineRecognitionCapability()
        val result = availabilityManager.isCloudRecognitionAvailable()

        // Then
        assertTrue(result)
    }

    @Test
    fun `getCapabilityMessage should return appropriate message for ON_DEVICE_AVAILABLE`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createSuccessTask("en")
        every { mockTranslator.downloadModelIfNeeded(any()) } returns createSuccessTask(null)
        every { mockTranslator.translate("Hello") } returns createSuccessTask("Hola")

        // When
        availabilityManager.determineRecognitionCapability()
        val message = availabilityManager.getCapabilityMessage()

        // Then
        assertEquals("On-device translation available - works offline", message)
    }

    @Test
    fun `getCapabilityMessage should return appropriate message for CLOUD_ONLY`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        availabilityManager.determineRecognitionCapability()
        val message = availabilityManager.getCapabilityMessage()

        // Then
        assertEquals("Cloud-based translation available - requires internet connection", message)
    }

    @Test
    fun `getCapabilityMessage should return appropriate message for UNAVAILABLE`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SERVICE_MISSING

        // When
        availabilityManager.determineRecognitionCapability()
        val message = availabilityManager.getCapabilityMessage()

        // Then
        assertEquals("Translation not available - Google Play Services required", message)
    }

    @Test
    fun `getCapabilityMessage should return appropriate message for CHECKING`() = runTest {
        // Given
        // No setup needed, capability starts as CHECKING

        // When
        val message = availabilityManager.getCapabilityMessage()

        // Then
        assertEquals("Checking translation availability...", message)
    }

    @Test
    fun `getRecommendedAction should return appropriate action for ON_DEVICE_AVAILABLE`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createSuccessTask("en")
        every { mockTranslator.downloadModelIfNeeded(any()) } returns createSuccessTask(null)
        every { mockTranslator.translate("Hello") } returns createSuccessTask("Hola")

        // When
        availabilityManager.determineRecognitionCapability()
        val action = availabilityManager.getRecommendedAction()

        // Then
        assertEquals("You can use translation offline", action)
    }

    @Test
    fun `getRecommendedAction should return appropriate action for CLOUD_ONLY`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SUCCESS
        every { mockLanguageIdentifier.identifyLanguage("Hello") } returns createFailureTask(RuntimeException("Test error"))

        // When
        availabilityManager.determineRecognitionCapability()
        val action = availabilityManager.getRecommendedAction()

        // Then
        assertEquals("Ensure you have an internet connection for translation", action)
    }

    @Test
    fun `getRecommendedAction should return appropriate action for UNAVAILABLE`() = runTest {
        // Given
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns 
            ConnectionResult.SERVICE_MISSING

        // When
        availabilityManager.determineRecognitionCapability()
        val action = availabilityManager.getRecommendedAction()

        // Then
        assertEquals("Please install or update Google Play Services", action)
    }

    @Test
    fun `getRecommendedAction should return appropriate action for CHECKING`() = runTest {
        // Given
        // No setup needed, capability starts as CHECKING

        // When
        val action = availabilityManager.getRecommendedAction()

        // Then
        assertEquals("Please wait while we check availability...", action)
    }


}
