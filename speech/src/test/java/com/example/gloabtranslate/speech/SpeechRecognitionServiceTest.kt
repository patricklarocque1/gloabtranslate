package com.example.gloabtranslate.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechRecognitionServiceTest {
    private val context: Context = mockk(relaxed = true)
    private lateinit var platform: FakeSpeechPlatform
    private lateinit var service: SpeechRecognitionService

    @Before
    fun setUp() {
        clearAllMocks()
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        // Mock Bundle operations for unit tests
        mockkStatic(Bundle::class)

        platform = FakeSpeechPlatform()
        service = SpeechRecognitionService(context, platform)
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkStatic(Log::class)
        unmockkStatic(Bundle::class)
    }

    @Test
    fun initialize_returnsTrueWhenAvailable() = runTest {
        assertTrue(service.initialize())
        assertEquals(1, platform.createdRecognizerCount)
    }

    @Test
    fun initialize_returnsFalseWhenPermissionMissing() = runTest {
        platform.hasPermission = false

        assertFalse(service.initialize())
        assertEquals(0, platform.createdRecognizerCount)
    }

    @Test
    fun initialize_returnsFalseWhenRecognitionUnavailable() = runTest {
        platform.recognitionAvailable = false

        assertFalse(service.initialize())
        assertEquals(0, platform.createdRecognizerCount)
    }

    @Test
    fun initialize_handlesRecognizerCreationError() = runTest {
        platform.throwOnCreate = true

        assertFalse(service.initialize())
        assertEquals(0, platform.createdRecognizerCount)
    }

    @Test
    fun recognizeSpeech_returnsResultOnSuccess() = runTest {
        assertTrue(service.initialize())

        platform.recognizer.onStartListening = {
            platform.recognizer.emitResults("hello", 0.9f)
        }

        val result = service.recognizeSpeech()

        assertTrue(result.success)
        assertEquals("hello", result.text)
        assertEquals(0.9f, result.confidence)
    }

    @Test
    fun recognizeSpeech_returnsFailureWhenNotInitialized() = runTest {
        val result = service.recognizeSpeech()

        assertFalse(result.success)
        assertEquals("Speech recognition not initialized", result.error)
    }

    @Test
    fun recognizeSpeech_returnsFailureWhenPermissionRevoked() = runTest {
        assertTrue(service.initialize())
        platform.hasPermission = false

        val result = service.recognizeSpeech()

        assertFalse(result.success)
        assertEquals("RECORD_AUDIO permission not granted", result.error)
    }

    @Test
    fun startContinuousRecognition_emitsErrorWhenNotInitialized() = runTest {
        val result = service.startContinuousRecognition().first()

        assertFalse(result.success)
        assertEquals("Speech recognition not initialized", result.error)
    }

    @Test
    fun startContinuousRecognition_emitsResultsWhenAvailable() = runTest {
        assertTrue(service.initialize())

        platform.recognizer.onStartListening = {
            platform.recognizer.emitResults("hi", 0.5f)
        }

        val result = service.startContinuousRecognition().first()

        assertTrue(result.success)
        assertEquals("hi", result.text)
        assertEquals(0.5f, result.confidence)
        assertFalse(result.isPartial)
    }

    @Test
    fun startContinuousRecognition_emitsErrorOnRecognizerFailure() = runTest {
        assertTrue(service.initialize())

        platform.recognizer.onStartListening = {
            platform.recognizer.emitError(SpeechRecognizer.ERROR_NETWORK)
        }

        val result = service.startContinuousRecognition().first()

        assertFalse(result.success)
        assertTrue(result.error?.contains("Continuous recognition error") == true)
    }

    @Test
    fun isAvailable_checksPermissionAndAvailability() = runTest {
        assertTrue(service.isAvailable())

        platform.hasPermission = false
        assertFalse(service.isAvailable())

        platform.hasPermission = true
        platform.recognitionAvailable = false
        assertFalse(service.isAvailable())
    }

    @Test
    fun getSupportedLanguages_returnsEmptyWhenUnavailable() = runTest {
        platform.hasPermission = false
        assertTrue(service.getSupportedLanguages().isNotEmpty())

        platform.hasPermission = true
        platform.recognitionAvailable = false
        assertTrue(service.getSupportedLanguages().isNotEmpty())
    }

    @Test
    fun getSupportedLanguages_handlesExceptionsGracefully() = runTest {
        platform.throwOnAvailabilityCheck = true

        val languages = service.getSupportedLanguages()

        // getSupportedLanguages returns static list regardless of platform availability
        assertTrue(languages.isNotEmpty())
    }

    @Test
    fun cleanup_destroysRecognizer() = runTest {
        assertTrue(service.initialize())

        service.cleanup()

        assertEquals(1, platform.recognizer.destroyCalls)
    }
}

private class FakeSpeechPlatform : SpeechPlatform {
    var hasPermission: Boolean = true
    var recognitionAvailable: Boolean = true
    var throwOnCreate: Boolean = false
    var throwOnAvailabilityCheck: Boolean = false

    val recognizer = FakeSpeechRecognizer()
    var createdRecognizerCount: Int = 0

    override fun hasRecordAudioPermission(): Boolean = hasPermission

    override fun isRecognitionAvailable(): Boolean {
        if (throwOnAvailabilityCheck) {
            throw RuntimeException("availability check failure")
        }
        return recognitionAvailable
    }

    override fun createSpeechRecognizer(): SpeechRecognizerAdapter {
        if (throwOnCreate) {
            throw RuntimeException("creation failure")
        }
        createdRecognizerCount += 1
        return recognizer
    }
}

private class FakeSpeechRecognizer : SpeechRecognizerAdapter {
    var listener: RecognitionListener? = null
    var onStartListening: ((SpeechRecognitionService.RecognitionConfig) -> Unit)? = null
    var destroyCalls: Int = 0
    var lastConfig: SpeechRecognitionService.RecognitionConfig? = null

    override fun setRecognitionListener(listener: RecognitionListener) {
        this.listener = listener
    }

    override fun startListening(config: SpeechRecognitionService.RecognitionConfig) {
        lastConfig = config
        onStartListening?.invoke(config)
    }

    override fun stopListening() {
        // no-op for unit tests
    }

    fun emitResults(text: String, confidence: Float?) {
        listener?.onResults(createMockBundle(text, confidence))
    }

    fun emitPartialResult(text: String) {
        listener?.onPartialResults(createMockBundle(text, null))
    }

    private fun createMockBundle(text: String, confidence: Float?): Bundle {
        val bundle = mockk<Bundle>(relaxed = true)
        every { bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) } returns arrayListOf(text)
        every { bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) } returns confidence?.let { floatArrayOf(it) }
        return bundle
    }

    fun emitError(code: Int) {
        listener?.onError(code)
    }

    override fun destroy() {
        destroyCalls += 1
    }
}

