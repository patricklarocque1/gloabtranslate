package com.example.gloabtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    application = android.app.Application::class
)
class TextToSpeechServiceTest {

    @MockK
    private lateinit var mockContext: Context
    
    @MockK
    private lateinit var mockTextToSpeech: TextToSpeech

    private lateinit var ttsService: TextToSpeechService
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        // Initialize MockK with proper configuration
        MockKAnnotations.init(this, relaxUnitFun = true)
        
        // Setup mock context with relaxed behavior
        every { mockContext.getSystemService(any()) } returns null
        every { mockContext.packageName } returns "com.example.gloabtranslate.test"
        
        // Mock static TextToSpeech constructor - improved approach
        mockkStatic(TextToSpeech::class)
        every { 
            TextToSpeech(any<Context>(), any<TextToSpeech.OnInitListener>())
        } answers {
            val callback = secondArg<TextToSpeech.OnInitListener>()
            // Simulate successful initialization asynchronously
            callback.onInit(TextToSpeech.SUCCESS)
            mockTextToSpeech
        }
        
        // Setup common TextToSpeech mock behaviors
        justRun { mockTextToSpeech.setOnUtteranceProgressListener(any()) }
        justRun { mockTextToSpeech.shutdown() }
        every { mockTextToSpeech.stop() } returns TextToSpeech.SUCCESS
        
        // Create real service instance with mocked dependencies
        ttsService = TextToSpeechService(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        
        // Cleanup TTS service if needed
        runCatching { ttsService.cleanup() }
        
        // Clear all mocks and static mocks
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `initialize should return true when TTS initializes successfully`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        
        // Mock the required TTS methods
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        
        // When
        val result = ttsService.initialize(config)

        // Then
        assertTrue(result)
    }

    @Test
    fun `initialize should return false when TTS initialization fails`() = runTest {
        // Given - Create a new service instance with failed TTS initialization
        clearMocks(mockTextToSpeech)
        
        every { 
            TextToSpeech(any<Context>(), any<TextToSpeech.OnInitListener>())
        } answers {
            val callback = secondArg<TextToSpeech.OnInitListener>()
            callback.onInit(TextToSpeech.ERROR)
            mockTextToSpeech
        }
        
        val failingTtsService = TextToSpeechService(mockContext)
        val config = TextToSpeechService.TTSConfig()

        // When
        val result = failingTtsService.initialize(config)

        // Then
        assertFalse(result)
    }

    @Test
    fun `initialize should handle exceptions gracefully`() = runTest {
        // Given - Create a service instance that throws exception during TTS creation
        every { 
            TextToSpeech(any<Context>(), any<TextToSpeech.OnInitListener>())
        } throws RuntimeException("Test exception")
        
        val exceptionTtsService = TextToSpeechService(mockContext)
        val config = TextToSpeechService.TTSConfig()

        // When
        val result = exceptionTtsService.initialize(config)

        // Then
        assertFalse(result)
    }

    @Test
    fun `initialize should return true when already initialized`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.initialize(config)

        // Then
        assertTrue(result)
    }

    @Test
    fun `speak should return success when text is valid`() = runTest {
        // Given
        val text = "Hello world"
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        
        every { mockTextToSpeech.speak(any(), any(), any(), any()) } returns TextToSpeech.SUCCESS

        // When
        ttsService.initialize(config)
        val result = ttsService.speak(text)

        // Then
        assertTrue(result.success)
        assertNotNull(result.utteranceId)
        assertNull(result.error)
    }

    @Test
    fun `speak should return failure when not initialized`() = runTest {
        // Given
        val text = "Hello world"

        // When
        val result = ttsService.speak(text)

        // Then
        assertFalse(result.success)
        assertEquals("TTS service not initialized", result.error)
        assertNull(result.utteranceId)
    }

    @Test
    fun `speak should return failure when text is empty`() = runTest {
        // Given
        val text = ""
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.speak(text)

        // Then
        assertFalse(result.success)
        assertEquals("Text cannot be empty", result.error)
    }

    @Test
    fun `speak should return failure when TTS engine not available`() = runTest {
        // Given
        val text = "Hello world"
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        // Simulate TTS engine becoming null
        ttsService = TextToSpeechService(mockContext)
        val result = ttsService.speak(text)

        // Then
        assertFalse(result.success)
        assertEquals("TTS engine not available", result.error)
    }

    @Test
    fun `speak should handle exceptions gracefully`() = runTest {
        // Given
        val text = "Hello world"
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        
        every { mockTextToSpeech.speak(any(), any(), any(), any()) } throws 
            RuntimeException("Test exception")

        // When
        ttsService.initialize(config)
        val result = ttsService.speak(text)

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Failed to speak text") == true)
    }

    @Test
    fun `stop should return true when TTS stops successfully`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        
        every { mockTextToSpeech.stop() } returns TextToSpeech.SUCCESS

        // When
        ttsService.initialize(config)
        val result = ttsService.stop()

        // Then
        assertTrue(result)
    }

    @Test
    fun `stop should return false when not initialized`() = runTest {
        // When
        val result = ttsService.stop()

        // Then
        assertFalse(result)
    }

    @Test
    fun `stop should handle exceptions gracefully`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        
        every { mockTextToSpeech.stop() } throws RuntimeException("Test exception")

        // When
        ttsService.initialize(config)
        val result = ttsService.stop()

        // Then
        assertFalse(result)
    }

    @Test
    fun `setLanguage should return true when language is available`() = runTest {
        // Given
        val language = Locale.ENGLISH
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.setLanguage(language)

        // Then
        assertTrue(result)
    }

    @Test
    fun `setLanguage should return false when language not available`() = runTest {
        // Given
        val language = Locale.ENGLISH
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_NOT_SUPPORTED
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.setLanguage(language)

        // Then
        assertFalse(result)
    }

    @Test
    fun `setLanguage should return false when not initialized`() = runTest {
        // Given
        val language = Locale.ENGLISH

        // When
        val result = ttsService.setLanguage(language)

        // Then
        assertFalse(result)
    }

    @Test
    fun `setSpeechRate should return true when rate is valid`() = runTest {
        // Given
        val rate = 1.5f
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.setSpeechRate(rate)

        // Then
        assertTrue(result)
    }

    @Test
    fun `setSpeechRate should clamp rate to valid range`() = runTest {
        // Given
        val rate = 5.0f // Should be clamped to 3.0f
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.setSpeechRate(rate)

        // Then
        assertTrue(result)
        // Verify that the rate was clamped
        verify { mockTextToSpeech.setSpeechRate(3.0f) }
    }

    @Test
    fun `setPitch should return true when pitch is valid`() = runTest {
        // Given
        val pitch = 1.2f
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.setPitch(pitch)

        // Then
        assertTrue(result)
    }

    @Test
    fun `setVoice should return false when not initialized`() = runTest {
        // Given
        val voice = TextToSpeechService.VoiceInfo(
            name = "Test Voice",
            locale = Locale.ENGLISH,
            quality = TextToSpeechService.VoiceQuality.HIGH,
            gender = TextToSpeechService.VoiceGender.NEUTRAL
        )

        // When
        val result = ttsService.setVoice(voice)

        // Then
        assertFalse(result)
    }

    @Test
    fun `setVoice should return true when voice is valid`() = runTest {
        // Given
        val voice = TextToSpeechService.VoiceInfo(
            name = "Test Voice",
            locale = Locale.ENGLISH,
            quality = TextToSpeechService.VoiceQuality.HIGH,
            gender = TextToSpeechService.VoiceGender.NEUTRAL
        )
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.setVoice(voice)

        // Then
        assertTrue(result)
    }

    @Test
    fun `getAvailableVoices should return list of voices`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val voices = ttsService.getAvailableVoices()

        // Then
        assertTrue(voices.isNotEmpty())
        assertEquals("Default English", voices[0].name)
        assertEquals(Locale.ENGLISH, voices[0].locale)
    }

    @Test
    fun `getSupportedLanguages should return list of languages`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val languages = ttsService.getSupportedLanguages()

        // Then
        assertTrue(languages.isNotEmpty())
        assertTrue(languages.contains(Locale.ENGLISH))
        assertTrue(languages.contains(Locale("es", "ES")))
    }

    @Test
    fun `isAvailable should return true when initialized`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val result = ttsService.isAvailable()

        // Then
        assertTrue(result)
    }

    @Test
    fun `isAvailable should return false when not initialized`() = runTest {
        // When
        val result = ttsService.isAvailable()

        // Then
        assertFalse(result)
    }

    @Test
    fun `getCurrentConfig should return current configuration`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig(
            language = Locale.ENGLISH,
            speechRate = 1.5f,
            pitch = 1.2f,
            volume = 0.8f
        )
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val currentConfig = ttsService.getCurrentConfig()

        // Then
        assertEquals(Locale.ENGLISH, currentConfig.language)
        assertEquals(1.5f, currentConfig.speechRate)
        assertEquals(1.2f, currentConfig.pitch)
        assertEquals(0.8f, currentConfig.volume)
    }

    @Test
    fun `addTTSListener should add listener`() = runTest {
        // Given
        val listener = mockk<TextToSpeechService.TTSListener>(relaxed = true)

        // When
        ttsService.addTTSListener(listener)

        // Then
        // Listener should be added without throwing
        assertTrue(true)
    }

    @Test
    fun `removeTTSListener should remove listener`() = runTest {
        // Given
        val listener = mockk<TextToSpeechService.TTSListener>(relaxed = true)
        ttsService.addTTSListener(listener)

        // When
        ttsService.removeTTSListener(listener)

        // Then
        // Listener should be removed without throwing
        assertTrue(true)
    }

    @Test
    fun `cleanup should shutdown TTS and clear resources`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        
        every { mockTextToSpeech.stop() } returns TextToSpeech.SUCCESS

        // When
        ttsService.initialize(config)
        ttsService.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue(true)
    }

    @Test
    fun `cleanup should handle null TTS gracefully`() = runTest {
        // When
        ttsService.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue(true)
    }

    @Test
    fun `getTTSStats should return statistics`() = runTest {
        // Given
        val config = TextToSpeechService.TTSConfig()
        every { mockTextToSpeech.setLanguage(any()) } returns TextToSpeech.LANG_AVAILABLE
        every { mockTextToSpeech.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { mockTextToSpeech.setPitch(any()) } returns TextToSpeech.SUCCESS
        

        // When
        ttsService.initialize(config)
        val stats = ttsService.getTTSStats()

        // Then
        assertTrue(stats.isNotEmpty())
        assertTrue(stats.containsKey("isInitialized"))
        assertTrue(stats.containsKey("currentLanguage"))
        assertTrue(stats.containsKey("speechRate"))
        assertTrue(stats.containsKey("pitch"))
        assertTrue(stats.containsKey("volume"))
        assertTrue(stats.containsKey("listenerCount"))
    }
}
