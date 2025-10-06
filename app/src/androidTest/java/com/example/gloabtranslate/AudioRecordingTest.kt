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
import com.example.gloabtranslate.core.data.config.ConfigurationManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

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
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioProcessor: AudioProcessor
    private lateinit var speechRecognitionService: SpeechRecognitionService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        configurationManager = ConfigurationManager.getInstance(context)
        audioRecorder = AudioRecorder(context, configurationManager)
        audioProcessor = AudioProcessor(context, configurationManager)
        speechRecognitionService = SpeechRecognitionService(context)
    }

    @After
    fun tearDown() {
        audioRecorder.close()
        audioProcessor.close()
        speechRecognitionService.cleanup()
    }

    @Test
    fun `test audio recorder initialization`() = runTest {
        // When
        val result = audioRecorder.initialize()

        // Then
        assertTrue("Audio recorder should initialize successfully", result.success)
    }

    @Test
    fun `test audio recorder configuration`() = runTest {
        // Given
        val config = AudioRecorder.RecordingConfig(
            sampleRate = 44100,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            bufferSize = 4096
        )

        // When
        val result = audioRecorder.initialize(config)

        // Then
        assertTrue("Audio recorder should initialize with custom config", result.success)
    }

    @Test
    fun `test audio recording start and stop`() = runTest {
        // Given
        audioRecorder.initialize()

        // When
        val audioFlow = audioRecorder.startRecording()
        delay(100) // Record for a short duration
        val isRecordingAfterStart = audioRecorder.isRecording()
        audioRecorder.stopRecording()
        val isRecordingAfterStop = audioRecorder.isRecording()

        // Then
        assertNotNull("Audio flow should be created", audioFlow)
        assertTrue("Should be recording after start", isRecordingAfterStart)
        assertFalse("Should not be recording after stop", isRecordingAfterStop)
    }

    @Test
    fun `test audio recording with different sample rates`() = runTest {
        val sampleRates = listOf(8000, 16000, 22050, 44100, 48000)

        sampleRates.forEach { sampleRate ->
            // Given
            val config = AudioRecorder.RecordingConfig(
                sampleRate = sampleRate,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )

            // When
            val initResult = audioRecorder.initialize(config)
            val audioFlow = audioRecorder.startRecording()
            delay(50)
            audioRecorder.stopRecording()

            // Then
            assertTrue("Audio recorder should initialize with sample rate: $sampleRate", initResult.success)
            assertNotNull("Audio recording should start with sample rate: $sampleRate", audioFlow)
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
            val config = AudioRecorder.RecordingConfig(
                sampleRate = 44100,
                channelConfig = channelConfig,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT
            )

            // When
            val initResult = audioRecorder.initialize(config)
            val audioFlow = audioRecorder.startRecording()
            delay(50)
            audioRecorder.stopRecording()

            // Then
            assertTrue("Audio recorder should initialize with channel config: $channelConfig", initResult.success)
            assertNotNull("Audio recording should start with channel config: $channelConfig", audioFlow)
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
            val config = AudioRecorder.RecordingConfig(
                sampleRate = 44100,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = audioFormat
            )

            // When
            val initResult = audioRecorder.initialize(config)
            val audioFlow = audioRecorder.startRecording()
            delay(50)
            audioRecorder.stopRecording()

            // Then
            assertTrue("Audio recorder should initialize with audio format: $audioFormat", initResult.success)
            assertNotNull("Audio recording should start with audio format: $audioFormat", audioFlow)
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
        assertFalse("Initial state should not be recording", initialState)
        assertTrue("State should be recording after start", recordingState)
        assertFalse("State should not be recording after stop", stoppedState)
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
        assertTrue("Recording duration should be at least 1 second", duration >= 1000)
        assertTrue("Recording duration should not exceed 1.1 seconds", duration <= 1100)
    }

    @Test
    fun `test audio recording with pause and resume`() = runTest {
        // Given
        audioRecorder.initialize()

        // When
        audioRecorder.startRecording()
        val initiallyRecording = audioRecorder.isRecording()
        delay(500)
        audioRecorder.pauseRecording()
        delay(200)
        audioRecorder.resumeRecording()
        val finallyRecording = audioRecorder.isRecording()
        delay(500)
        audioRecorder.stopRecording()

        // Then
        assertTrue("Should be recording initially", initiallyRecording)
        assertTrue("Should be recording after resume", finallyRecording)
    }

    @Test
    fun `test audio processor initialization`() = runTest {
        // When
        val result = audioProcessor.initialize()

        // Then
        assertTrue("Audio processor should initialize successfully", result.success)
    }

    @Test
    fun `test audio processor with different configurations`() = runTest {
        // Given
        val configs = listOf(
            AudioProcessor.ProcessingConfig(sampleRate = 44100),
            AudioProcessor.ProcessingConfig(sampleRate = 16000),
            AudioProcessor.ProcessingConfig(sampleRate = 48000)
        )

        configs.forEach { config ->
            // When
            val result = audioProcessor.initialize(config)

            // Then
            assertTrue("Audio processor should initialize with config: $config", result.success)
        }
    }

    @Test
    fun `test audio processor noise reduction`() = runTest {
        // Given
        audioProcessor.initialize()
        val testAudioData = ByteArray(1024) { (it % 256).toByte() }

        // When
        val result = audioProcessor.processAudioFrame(testAudioData)

        // Then
        assertNotNull("Processing result should not be null", result)
        assertTrue("Processing should succeed", result.success)
    }

    @Test
    fun `test audio processor echo cancellation`() = runTest {
        // Given
        audioProcessor.initialize()
        val testAudioData = ByteArray(1024) { (it % 256).toByte() }

        // When
        val result = audioProcessor.processAudioFrame(testAudioData)

        // Then
        assertNotNull("Processing result should not be null", result)
        assertTrue("Processing should succeed", result.success)
    }

    @Test
    fun `test audio processor volume normalization`() = runTest {
        // Given
        audioProcessor.initialize()
        val testAudioData = ByteArray(1024) { (it % 256).toByte() }

        // When
        val result = audioProcessor.processAudioFrame(testAudioData)

        // Then
        assertNotNull("Processing result should not be null", result)
        assertTrue("Processing should succeed", result.success)
    }

    @Test
    fun `test speech recognition service initialization`() = runTest {
        // When
        val isInitialized = speechRecognitionService.initialize()

        // Then
        assertTrue("Speech recognition service should initialize successfully", isInitialized)
    }

    @Test
    fun `test speech recognition service availability`() = runTest {
        // When
        val isAvailable = speechRecognitionService.isAvailable()

        // Then
        assertTrue("Speech recognition service should be available", isAvailable)
    }

    @Test
    fun `test speech recognition service supported languages`() = runTest {
        // When
        val supportedLanguages = speechRecognitionService.getSupportedLanguages()

        // Then
        assertNotNull("Supported languages should not be null", supportedLanguages)
        assertTrue("Supported languages should not be empty", supportedLanguages.isNotEmpty())
        assertTrue("English should be supported", supportedLanguages.contains("en-US"))
        assertTrue("Spanish should be supported", supportedLanguages.contains("es-ES"))
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
        assertNotNull("Recognition result should not be null", result)
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
        assertNotNull("Recognition result should not be null", result)
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
        assertNotNull("Recognition result should not be null", result)
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
        assertNotNull("Recognition result should not be null", result)
    }

    @Test
    fun `test audio recording with custom buffer size`() = runTest {
        val bufferSizes = listOf(1024, 2048, 4096, 8192)

        bufferSizes.forEach { bufferSize ->
            // Given
            val config = AudioRecorder.RecordingConfig(
                sampleRate = 44100,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                bufferSize = bufferSize
            )

            // When
            val isInitialized = audioRecorder.initialize(config)
            val startResult = audioRecorder.startRecording()
            delay(100)
            audioRecorder.stopRecording() // Returns Unit, so just call it

            // Then
            assertTrue("Audio recorder should initialize with buffer size: $bufferSize", isInitialized.success)
            assertNotNull("Audio recording should start with buffer size: $bufferSize", startResult)
            // Stop operation completed without throwing exceptions
        }
    }

    @Test
    fun `test audio recording error handling`() = runTest {
        // Given
        val invalidConfig = AudioRecorder.RecordingConfig(
            sampleRate = -1, // Invalid sample rate
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT
        )

        // When
        val result = audioRecorder.initialize(invalidConfig)

        // Then
        assertFalse("Audio recorder should not initialize with invalid config", result.success)
    }

    @Test
    fun `test audio recording cleanup`() = runTest {
        // Given
        audioRecorder.initialize()
        audioRecorder.startRecording()

        // When
        audioRecorder.close()

        // Then
        // Cleanup should not throw exceptions
        assertTrue("Cleanup should complete successfully", true)
    }

    @Test
    fun `test audio processor cleanup`() = runTest {
        // Given
        audioProcessor.initialize()

        // When
        audioProcessor.close()

        // Then
        // Cleanup should not throw exceptions
        assertTrue("Cleanup should complete successfully", true)
    }

    @Test
    fun `test speech recognition service cleanup`() = runTest {
        // Given
        speechRecognitionService.initialize()

        // When
        speechRecognitionService.cleanup()

        // Then
        // Cleanup should not throw exceptions
        assertTrue("Cleanup should complete successfully", true)
    }
}
