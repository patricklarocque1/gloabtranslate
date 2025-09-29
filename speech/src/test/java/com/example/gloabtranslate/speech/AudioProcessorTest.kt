package com.example.gloabtranslate.speech

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.config.AudioConfig as PreferencesAudioConfig
import com.example.gloabtranslate.speech.config.AudioConfigurationObserver
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AudioProcessorTest {
    private val context: Context = mockk(relaxed = true)
    private lateinit var platform: FakeAudioProcessorPlatform
    private lateinit var processor: AudioProcessor

    @Before
    fun setUp() {
        clearAllMocks()
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        platform = FakeAudioProcessorPlatform()
        processor = AudioProcessor(context, platform)
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkStatic(Log::class)
    }

    @Test
    fun initialize_returnsTrueWithValidConfig() = runTest {
        val config = AudioProcessor.ProcessingConfig(
            sampleRate = 16000,
            bufferSize = 1024,
            chunkSize = 320
        )

        val result = processor.initialize(config)

        assertTrue(result.success)
        // Processor should initialize successfully
    }

    @Test
    fun initialize_handlesInvalidConfig() = runTest {
        platform.shouldFailInitialization = true

        val result = processor.initialize()

        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error?.contains("Initialization failed") == true)
    }

    @Test
    fun processAudioFrame_returnsValidResultForValidData() = runTest {
        assertTrue(processor.initialize().success)

        val audioData = platform.generateSineWave(1000, 16000, 0.5f, 1024)
        val result = processor.processAudioFrame(audioData)

        assertTrue(result.success)
        assertNotNull(result.processedAudio)
        assertNotNull(result.features)
        assertNotNull(result.vadResult)
        assertTrue(result.features!!.energy > 0f)
    }

    @Test
    fun processAudioFrame_handlesEmptyData() = runTest {
        assertTrue(processor.initialize().success)

        val result = processor.processAudioFrame(ByteArray(0))

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun processAudioFrame_detectsVoiceActivity() = runTest {
        assertTrue(processor.initialize().success)

        // Generate audio with significant energy (voice-like)
        val voiceAudio = platform.generateSineWave(1000, 16000, 0.8f, 1024)
        val voiceResult = processor.processAudioFrame(voiceAudio)

        assertTrue(voiceResult.success)
        assertTrue(voiceResult.vadResult!!.isVoice)
        assertTrue(voiceResult.vadResult!!.energy > 0.1f)

        // Generate low-energy audio (silence-like)
        val silenceAudio = platform.generateSineWave(1000, 16000, 0.001f, 1024)
        val silenceResult = processor.processAudioFrame(silenceAudio)

        assertTrue(silenceResult.success)
        assertFalse(silenceResult.vadResult!!.isVoice)
    }

    @Test
    fun processAudioFrame_extractsValidFeatures() = runTest {
        assertTrue(processor.initialize().success)

        val audioData = platform.generateSineWave(1000, 16000, 0.5f, 1024)
        val result = processor.processAudioFrame(audioData)

        assertTrue(result.success)
        val features = result.features!!
        
        assertTrue(features.energy > 0f)
        assertTrue(features.spectralCentroid > 0f)
        assertTrue(features.zeroCrossingRate >= 0f)
    }

    @Test
    fun processAudioFrame_appliesNoiseReductionWhenEnabled() = runTest {
        val configWithNoise = AudioProcessor.ProcessingConfig(enableNoiseReduction = true)
        val configWithoutNoise = AudioProcessor.ProcessingConfig(enableNoiseReduction = false)

        assertTrue(processor.initialize(configWithNoise).success)

        val noisyAudio = platform.generateNoisyAudio(16000, 1024)
        val resultWithReduction = processor.processAudioFrame(noisyAudio, configWithNoise)
        val resultWithoutReduction = processor.processAudioFrame(noisyAudio, configWithoutNoise)

        assertTrue(resultWithReduction.success)
        assertTrue(resultWithoutReduction.success)

        // Noise reduction should reduce energy level
        assertTrue(resultWithReduction.features!!.energy < resultWithoutReduction.features!!.energy)
    }

    @Test
    fun createProcessingFlow_transformsAudioFrames() = runTest {
        assertTrue(processor.initialize().success)

        val audioFrames = listOf(
            AudioRecorder.AudioFrame(
                data = platform.generateSineWave(1000, 16000, 0.5f, 1024),
                timestamp = System.currentTimeMillis()
            ),
            AudioRecorder.AudioFrame(
                data = platform.generateSineWave(2000, 16000, 0.3f, 1024),
                timestamp = System.currentTimeMillis() + 100
            )
        )

        val processedFrames = processor.createProcessingFlow(flowOf(*audioFrames.toTypedArray())).toList()

        assertEquals(2, processedFrames.size)
        processedFrames.forEach { frame ->
            assertNotNull(frame.features)
            assertNotNull(frame.vadResult)
            assertTrue(frame.audioData.isNotEmpty())
        }
    }

    @Test
    fun cleanup_stopsProcessingAndClearsResources() = runTest {
        assertTrue(processor.initialize().success)

        processor.cleanup()

        assertEquals(1, platform.cleanupCallCount)
    }
}

// Fake platform for testing AudioProcessor without dependencies
private class FakeAudioProcessorPlatform : AudioProcessorPlatform {
    var shouldFailInitialization = false
    var cleanupCallCount = 0

    override fun getCurrentAudioConfig(): PreferencesAudioConfig {
        if (shouldFailInitialization) {
            throw RuntimeException("Failed to get audio config")
        }
        return PreferencesAudioConfig(
            sampleRate = 16000,
            audioBufferSize = 1024,
            enableNoiseReduction = true,
            enableVoiceRecognition = true,
            enableAudioRecording = true,
            audioQuality = PreferencesAudioConfig.AudioQuality.HIGH,
            enableEchoCancellation = false,
            enableContinuousRecording = true
        )
    }

    override fun createConfigurationObserver(): AudioConfigurationObserver {
        return mockk(relaxed = true)
    }

    override fun cleanup() {
        cleanupCallCount += 1
    }

    // Test data generation helpers
    fun generateSineWave(frequency: Int, sampleRate: Int, amplitude: Float, samples: Int): ByteArray {
        val result = ByteArray(samples * 2) // 16-bit samples = 2 bytes each
        for (i in 0 until samples) {
            val sample = (amplitude * Short.MAX_VALUE * kotlin.math.sin(2.0 * Math.PI * frequency * i / sampleRate)).toInt().toShort()
            result[i * 2] = (sample.toInt() and 0xFF).toByte()
            result[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return result
    }

    fun generateNoisyAudio(sampleRate: Int, samples: Int): ByteArray {
        val result = ByteArray(samples * 2)
        val random = kotlin.random.Random
        for (i in 0 until samples) {
            val noise = (random.nextFloat() * 0.1f * Short.MAX_VALUE).toInt().toShort()
            result[i * 2] = (noise.toInt() and 0xFF).toByte()
            result[i * 2 + 1] = ((noise.toInt() shr 8) and 0xFF).toByte()
        }
        return result
    }
}

// Platform interface for AudioProcessor testability  
interface AudioProcessorPlatform {
    fun getCurrentAudioConfig(): PreferencesAudioConfig
    fun createConfigurationObserver(): AudioConfigurationObserver
    fun cleanup()
}
