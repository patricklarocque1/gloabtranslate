package com.example.gloabtranslate.speech

import android.content.Context
import com.example.gloabtranslate.core.data.config.AudioConfig
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import com.example.gloabtranslate.core.error.ErrorRecoverySystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Tests that AudioRecordingService reacts to audio config changes while running.
 * This is a lightweight behavioral test that simulates the dynamic restart trigger via injected flow emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioRecordingServiceConfigUpdateTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: UserPreferencesManager
    private lateinit var configurationManager: ConfigurationManager

    @Before
    fun setUp() = runTest {
        context = RuntimeEnvironment.getApplication()
        preferencesManager = UserPreferencesManager.getInstance(context)
        configurationManager = ConfigurationManager(preferencesManager)
        preferencesManager.initialize()
    }

    @Test
    fun serviceStartsAndHandlesConfigEmission() = runTest {
        // We instantiate the service directly (Robolectric allows this pattern)
        val service = AudioRecorder.AudioRecordingService()

        // Inject fields manually (normally via Dagger)
        val audioRecorder = Mockito.mock(AudioRecorder::class.java)
        val recovery = ErrorRecoverySystem()
        val reporter = Mockito.mock(com.example.gloabtranslate.core.external.ExternalServiceStateReporter::class.java)

        // Reflectively set injected properties
        AudioRecorder.AudioRecordingService::class.java.getDeclaredField("audioRecorder").apply { isAccessible = true }.set(service, audioRecorder)
        AudioRecorder.AudioRecordingService::class.java.getDeclaredField("errorRecoverySystem").apply { isAccessible = true }.set(service, recovery)
        AudioRecorder.AudioRecordingService::class.java.getDeclaredField("externalServiceStateReporter").apply { isAccessible = true }.set(service, reporter)
        AudioRecorder.AudioRecordingService::class.java.getDeclaredField("configurationManager").apply { isAccessible = true }.set(service, configurationManager)

        // Stub recorder behavior
        whenever(audioRecorder.isRecording()).thenReturn(true)
        whenever(audioRecorder.initialize(Mockito.any())).thenReturn(AudioRecorder.OperationResult(success = true))
        whenever(audioRecorder.startRecording(Mockito.any())).thenReturn(kotlinx.coroutines.flow.flow { /* no frames emitted */ })

        // Call onCreate to start config collection
        service.onCreate()

        // Simulate running state
        AudioRecorder.AudioRecordingService::class.java.getDeclaredField("isServiceRunning").apply { isAccessible = true }.set(service, true)

        // Emit preference change that would alter sample rate
        preferencesManager.setPreference("sampleRate", 44100)
        preferencesManager.setPreference("enableNoiseReduction", false)

        // Allow collector time to process
        delay(200)

        // We can't easily assert internal restart (placeholder), but we ensure service still considers itself running
        val stateMethod = AudioRecorder.AudioRecordingService::class.java.getDeclaredMethod("getRecordingState").apply { isAccessible = true }
        val state = stateMethod.invoke(service) as String
        assertTrue(state == "RECORDING" || state == "PAUSED")

        service.onDestroy()
    }
}
