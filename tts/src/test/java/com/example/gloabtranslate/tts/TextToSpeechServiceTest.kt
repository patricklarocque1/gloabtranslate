package com.example.gloabtranslate.tts

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TextToSpeechServiceTest {

    @MockK
    private lateinit var mockContext: Context

    @MockK(relaxed = true)
    private lateinit var mockTtsEngine: TextToSpeech

    private lateinit var ttsService: TextToSpeechService

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val ttsProvider = { _: Context, listener: TextToSpeech.OnInitListener, _: String? ->
            listener.onInit(TextToSpeech.SUCCESS)
            mockTtsEngine
        }

        ttsService = TextToSpeechService(mockContext, testDispatcher, ttsProvider)

        every { mockTtsEngine.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTtsEngine.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTtsEngine.setPitch(any()) } returns TextToSpeech.SUCCESS
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `initialize should return true on success`() = runTest(testDispatcher) {
        val result = ttsService.initialize()
        assertTrue(result)
        assertTrue(ttsService.isAvailable())
    }

    @Test
    fun `initialize should return false on failure`() = runTest(testDispatcher) {
        val failingTtsProvider = { _: Context, listener: TextToSpeech.OnInitListener, _: String? ->
            listener.onInit(TextToSpeech.ERROR)
            mockTtsEngine // Engine is still returned but init fails
        }
        val failingService = TextToSpeechService(mockContext, testDispatcher, failingTtsProvider)

        val result = failingService.initialize()

        assertFalse(result)
        assertFalse(failingService.isAvailable())
    }


    @Test
    fun `speak should return success when initialized and text is valid`() = runTest(testDispatcher) {
        ttsService.initialize()
        every { mockTtsEngine.speak(any<String>(), any(), any(), any()) } returns TextToSpeech.SUCCESS

        val result = ttsService.speak("Hello, world!")

        assertTrue(result.success)
    }

    @Test
    fun `speak should return failure when not initialized`() = runTest(testDispatcher) {
        val result = ttsService.speak("Hello, world!")

        assertFalse(result.success)
        assertEquals("TTS service not initialized", result.error)
    }

    @Test
    fun `setLanguage should return true for available language`() = runTest(testDispatcher) {
        ttsService.initialize()
        every { mockTtsEngine.setLanguage(Locale.FRENCH) } returns TextToSpeech.LANG_AVAILABLE

        val result = ttsService.setLanguage(Locale.FRENCH)

        assertTrue(result)
    }
    
    @Test
    fun `getAvailableVoices should return a list of voices`() = runTest(testDispatcher) {
        val mockVoice = mockk<Voice>(relaxed = true) {
            every { name } returns "test-voice"
            every { locale } returns Locale.US
            every { quality } returns Voice.QUALITY_HIGH
            every { isNetworkConnectionRequired } returns false
        }
        every { mockTtsEngine.voices } returns setOf(mockVoice)

        ttsService.initialize()
        val voices = ttsService.getAvailableVoices()

        assertTrue(voices.isNotEmpty())
        assertEquals("test-voice", voices.first().name)
    }
    
    @Test
    fun `stop should return true when successful`() = runTest(testDispatcher) {
        ttsService.initialize()
        every { mockTtsEngine.stop() } returns TextToSpeech.SUCCESS

        val result = ttsService.stop()

        assertTrue(result)
    }
    
    @Test
    fun `setSpeechRate should succeed`() = runTest(testDispatcher) {
        ttsService.initialize()
        every { mockTtsEngine.setSpeechRate(any()) } returns TextToSpeech.SUCCESS

        val result = ttsService.setSpeechRate(1.5f)

        assertTrue(result)
    }

    @Test
    fun `setPitch should succeed`() = runTest(testDispatcher) {
        ttsService.initialize()
        every { mockTtsEngine.setPitch(any()) } returns TextToSpeech.SUCCESS

        val result = ttsService.setPitch(1.2f)

        assertTrue(result)
    }
    
    @Test
    fun `isAvailable returns true when initialized`() = runTest(testDispatcher) {
        ttsService.initialize()
        assertTrue(ttsService.isAvailable())
    }
    
    @Test
    fun `isAvailable returns false when not initialized`() = runTest(testDispatcher) {
        assertFalse(ttsService.isAvailable())
    }
    
    @Test
    fun `cleanup should shutdown engine`() = runTest(testDispatcher) {
        ttsService.initialize()
        
        ttsService.cleanup()
        
        verify { mockTtsEngine.shutdown() }
        assertFalse(ttsService.isAvailable())
    }
    
    @Test
    fun `speak should return failure for empty text`() = runTest(testDispatcher) {
        ttsService.initialize()
        
        val result = ttsService.speak("")
        
        assertFalse(result.success)
        assertEquals("Text cannot be empty", result.error)
    }
    
    @Test
    fun `getSupportedLanguages returns languages from engine`() = runTest(testDispatcher) {
        val expectedLanguages = setOf(Locale.ENGLISH, Locale.GERMAN)
        every { mockTtsEngine.availableLanguages } returns expectedLanguages
        ttsService.initialize()
        
        val languages = ttsService.getSupportedLanguages()
        
        assertEquals(expectedLanguages.toList(), languages)
    }

    @Test
    fun `getCurrentConfig returns correct configuration`() = runTest(testDispatcher) {
        val config = TextToSpeechService.TTSConfig(language = Locale.JAPAN, speechRate = 1.5f)
        ttsService.initialize(config)

        val currentConfig = ttsService.getCurrentConfig()

        // Note: The mock setup doesn't apply the config in this test, so we check defaults.
        // A more advanced test could verify config application.
        assertEquals(Locale.JAPAN, currentConfig.language)
        assertEquals(1.5f, currentConfig.speechRate)
    }
}
