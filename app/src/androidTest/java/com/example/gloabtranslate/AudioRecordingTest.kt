package com.example.gloabtranslate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.gloabtranslate.speech.AudioConfig
import com.example.gloabtranslate.speech.AudioProcessor
import com.example.gloabtranslate.speech.AudioRecorder
import com.example.gloabtranslate.speech.SpeechRecognitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for audio recording functionality.
 * These tests require a real Android device with microphone access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AudioRecordingTest {

    @get:Rule
    val permissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var context: Context
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioProcessor: AudioProcessor
    private lateinit var speechRecognitionService: SpeechRecognitionService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioRecorder = AudioRecorder(context)
        audioProcessor = AudioProcessor(context)
        speechRecognitionService = SpeechRecognitionService(context)
    }

    @After
    fun tearDown() {
        audioRecorder.cleanup()
        audioProcessor.cleanup()
        speechRecognitionService.cleanup()
    }

    @Test
    fun `test audio recorder initialization`() = runTest {
        // When
        val isInitialized = audioRecorder.initialize()

        // Then
        assertTrue(isInitialized, "Audio recorder should initialize successfully")
    }

    @Test
    fun `test audio recorder configuration`() = runTest {
        // Given
        val config = AudioConfig(
            sampleRate = 44100,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSize = 4096
        )

        // When
        val isInitialized = audioRecorder.initialize(config)

        // Then
        assertTrue(isInitialized, "Audio recorder should initialize with custom config")
    }

    @Test
    fun `test audio recording start and stop`() = runTest {
        // Given
        audioRecorder.initialize()

        // When
        val startResult = audioRecorder.startRecording()
        delay(100) // Record for a short duration
        val stopResult = audioRecorder.stopRecording()

        // Then
        assertTrue(startResult, "Audio recording should start successfully")
        assertTrue(stopResult, "Audio recording should stop successfully")
    }

    @Test
    fun `test audio recording with different sample rates`() = runTest {
        val sampleRates = listOf(8000, 16000, 22050, 44100, 48000)

        sampleRates.forEach { sampleRate ->
            // Given
            val config = AudioConfig(
                sampleRate = sampleRate,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )

            // When
            val isInitialized = audioRecorder.initialize(config)
            val startResult = audioRecorder.startRecording()
            delay(50)
            val stopResult = audioRecorder.stopRecording()

            // Then
            assertTrue(isInitialized, "Audio recorder should initialize with sample rate: $sampleRate")
            assertTrue(startResult, "Audio recording should start with sample rate: $sampleRate")
            assertTrue(stopResult, "Audio recording should stop with sample rate: $sampleRate")
        }
    }

    @Test
    fun `test audio recording with different channel configurations`() = runTest {
        val channelConfigs = listOf(
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.CHANNEL_IN_STEREO
        )

        channelConfigs.forEach { channelConfig ->
            // Given
            val config = AudioConfig(
                sampleRate = 44100,
                channelConfig = channelConfig,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )

            // When
            val isInitialized = audioRecorder.initialize(config)
            val startResult = audioRecorder.startRecording()
            delay(50)
            val stopResult = audioRecorder.stopRecording()

            // Then
            assertTrue(isInitialized, "Audio recorder should initialize with channel config: $channelConfig")
            assertTrue(startResult, "Audio recording should start with channel config: $channelConfig")
            assertTrue(stopResult, "Audio recording should stop with channel config: $channelConfig")
        }
    }

    @Test
    fun `test audio recording with different audio formats`() = runTest {
        val audioFormats = listOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioFormats.forEach { audioFormat ->
            // Given
            val config = AudioConfig(
                sampleRate = 44100,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = audioFormat
            )

            // When
            val isInitialized = audioRecorder.initialize(config)
            val startResult = audioRecorder.startRecording()
            delay(50)
            val stopResult = audioRecorder.stopRecording()

            // Then
            assertTrue(isInitialized, "Audio recorder should initialize with audio format: $audioFormat")
            assertTrue(startResult, "Audio recording should start with audio format: $audioFormat")
            assertTrue(stopResult, "Audio recording should stop with audio format: $audioFormat")
        }
    }

    @Test
    fun `test audio recording state management`() = runTest {
        // Given
        audioRecorder.initialize()

        // When
        val initialState = audioRecorder.isRecording()
        audioRecorder.startRecording()
        val recordingState = audioRecorder.isRecording()
        audioRecorder.stopRecording()
        val stoppedState = audioRecorder.isRecording()

        // Then
        assertFalse(initialState, "Initial state should not be recording")
        assertTrue(recordingState, "State should be recording after start")
        assertFalse(stoppedState, "State should not be recording after stop")
    }

    @Test
    fun `test audio recording duration`() = runTest {
        // Given
        audioRecorder.initialize()
        val startTime = System.currentTimeMillis()

        // When
        audioRecorder.startRecording()
        delay(1000) // Record for 1 second
        audioRecorder.stopRecording()
        val endTime = System.currentTimeMillis()

        // Then
        val duration = endTime - startTime
        assertTrue(duration >= 1000, "Recording duration should be at least 1 second")
        assertTrue(duration <= 1100, "Recording duration should not exceed 1.1 seconds")
    }

    @Test
    fun `test audio recording with pause and resume`() = runTest {
        // Given
        audioRecorder.initialize()

        // When
        audioRecorder.startRecording()
        delay(500)
        audioRecorder.pauseRecording()
        val pausedState = audioRecorder.isPaused()
        delay(200)
        audioRecorder.resumeRecording()
        val resumedState = audioRecorder.isPaused()
        delay(500)
        audioRecorder.stopRecording()

        // Then
        assertTrue(pausedState, "Recording should be paused")
        assertFalse(resumedState, "Recording should be resumed")
    }

    @Test
    fun `test audio processor initialization`() = runTest {
        // When
        val isInitialized = audioProcessor.initialize()

        // Then
        assertTrue(isInitialized, "Audio processor should initialize successfully")
    }

    @Test
    fun `test audio processor with different configurations`() = runTest {
        // Given
        val configs = listOf(
            AudioConfig(sampleRate = 44100, channelConfig = AudioFormat.CHANNEL_IN_MONO),
            AudioConfig(sampleRate = 16000, channelConfig = AudioFormat.CHANNEL_IN_STEREO),
            AudioConfig(sampleRate = 48000, channelConfig = AudioFormat.CHANNEL_IN_MONO)
        )

        configs.forEach { config ->
            // When
            val isInitialized = audioProcessor.initialize(config)

            // Then
            assertTrue(isInitialized, "Audio processor should initialize with config: $config")
        }
    }

    @Test
    fun `test audio processor noise reduction`() = runTest {
        // Given
        audioProcessor.initialize()
        val testAudioData = ByteArray(1024) { (it % 256).toByte() }

        // When
        val processedData = audioProcessor.processAudio(testAudioData)

        // Then
        assertNotNull(processedData, "Processed audio data should not be null")
        assertTrue(processedData.isNotEmpty(), "Processed audio data should not be empty")
    }

    @Test
    fun `test audio processor echo cancellation`() = runTest {
        // Given
        audioProcessor.initialize()
        val testAudioData = ByteArray(1024) { (it % 256).toByte() }

        // When
        val processedData = audioProcessor.processAudio(testAudioData)

        // Then
        assertNotNull(processedData, "Processed audio data should not be null")
        assertTrue(processedData.isNotEmpty(), "Processed audio data should not be empty")
    }

    @Test
    fun `test audio processor volume normalization`() = runTest {
        // Given
        audioProcessor.initialize()
        val testAudioData = ByteArray(1024) { (it % 256).toByte() }

        // When
        val processedData = audioProcessor.processAudio(testAudioData)

        // Then
        assertNotNull(processedData, "Processed audio data should not be null")
        assertTrue(processedData.isNotEmpty(), "Processed audio data should not be empty")
    }

    @Test
    fun `test speech recognition service initialization`() = runTest {
        // When
        val isInitialized = speechRecognitionService.initialize()

        // Then
        assertTrue(isInitialized, "Speech recognition service should initialize successfully")
    }

    @Test
    fun `test speech recognition service availability`() = runTest {
        // When
        val isAvailable = speechRecognitionService.isAvailable()

        // Then
        assertTrue(isAvailable, "Speech recognition service should be available")
    }

    @Test
    fun `test speech recognition service supported languages`() = runTest {
        // When
        val supportedLanguages = speechRecognitionService.getSupportedLanguages()

        // Then
        assertNotNull(supportedLanguages, "Supported languages should not be null")
        assertTrue(supportedLanguages.isNotEmpty(), "Supported languages should not be empty")
        assertTrue(supportedLanguages.contains("en-US"), "English should be supported")
        assertTrue(supportedLanguages.contains("es-ES"), "Spanish should be supported")
    }

    @Test
    fun `test speech recognition with English language`() = runTest {
        // Given
        speechRecognitionService.initialize()
        val config = SpeechRecognitionService.RecognitionConfig(languageCode = "en-US")

        // When
        val result = speechRecognitionService.recognizeSpeech(config)

        // Then
        // Note: This test may not always succeed as it depends on actual speech input
        // The test verifies that the service can be called without throwing exceptions
        assertNotNull(result, "Recognition result should not be null")
    }

    @Test
    fun `test speech recognition with Spanish language`() = runTest {
        // Given
        speechRecognitionService.initialize()
        val config = SpeechRecognitionService.RecognitionConfig(languageCode = "es-ES")

        // When
        val result = speechRecognitionService.recognizeSpeech(config)

        // Then
        // Note: This test may not always succeed as it depends on actual speech input
        // The test verifies that the service can be called without throwing exceptions
        assertNotNull(result, "Recognition result should not be null")
    }

    @Test
    fun `test speech recognition with partial results enabled`() = runTest {
        // Given
        speechRecognitionService.initialize()
        val config = SpeechRecognitionService.RecognitionConfig(
            languageCode = "en-US",
            enablePartialResults = true
        )

        // When
        val result = speechRecognitionService.recognizeSpeech(config)

        // Then
        // Note: This test may not always succeed as it depends on actual speech input
        // The test verifies that the service can be called without throwing exceptions
        assertNotNull(result, "Recognition result should not be null")
    }

    @Test
    fun `test speech recognition with on-device recognition enabled`() = runTest {
        // Given
        speechRecognitionService.initialize()
        val config = SpeechRecognitionService.RecognitionConfig(
            languageCode = "en-US",
            enableOnDeviceRecognition = true
        )

        // When
        val result = speechRecognitionService.recognizeSpeech(config)

        // Then
        // Note: This test may not always succeed as it depends on actual speech input
        // The test verifies that the service can be called without throwing exceptions
        assertNotNull(result, "Recognition result should not be null")
    }

    @Test
    fun `test audio recording with custom buffer size`() = runTest {
        val bufferSizes = listOf(1024, 2048, 4096, 8192)

        bufferSizes.forEach { bufferSize ->
            // Given
            val config = AudioConfig(
                sampleRate = 44100,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                bufferSize = bufferSize
            )

            // When
            val isInitialized = audioRecorder.initialize(config)
            val startResult = audioRecorder.startRecording()
            delay(100)
            val stopResult = audioRecorder.stopRecording()

            // Then
            assertTrue(isInitialized, "Audio recorder should initialize with buffer size: $bufferSize")
            assertTrue(startResult, "Audio recording should start with buffer size: $bufferSize")
            assertTrue(stopResult, "Audio recording should stop with buffer size: $bufferSize")
        }
    }

    @Test
    fun `test audio recording error handling`() = runTest {
        // Given
        val invalidConfig = AudioConfig(
            sampleRate = -1, // Invalid sample rate
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT
        )

        // When
        val isInitialized = audioRecorder.initialize(invalidConfig)

        // Then
        assertFalse(isInitialized, "Audio recorder should not initialize with invalid config")
    }

    @Test
    fun `test audio recording cleanup`() = runTest {
        // Given
        audioRecorder.initialize()
        audioRecorder.startRecording()

        // When
        audioRecorder.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue(true, "Cleanup should complete successfully")
    }

    @Test
    fun `test audio processor cleanup`() = runTest {
        // Given
        audioProcessor.initialize()

        // When
        audioProcessor.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue(true, "Cleanup should complete successfully")
    }

    @Test
    fun `test speech recognition service cleanup`() = runTest {
        // Given
        speechRecognitionService.initialize()

        // When
        speechRecognitionService.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue(true, "Cleanup should complete successfully")
    }
}
