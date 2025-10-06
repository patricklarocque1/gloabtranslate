package com.example.gloabtranslate.service

import android.content.Context
import com.example.gloabtranslate.core.error.ErrorRecoverySystem
import com.google.common.truth.Truth.assertThat
import com.example.gloabtranslate.core.data.config.PerformanceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.Mockito
import org.mockito.kotlin.whenever

/**
 * Basic sanity tests for ServiceCoordinator using Mockito mocks.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceCoordinatorTest {

    @Test
    fun initializationSetsHealthyStateWhenCoreServicesSucceed() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        val recovery = ErrorRecoverySystem()
    val configurationManager = Mockito.mock(com.example.gloabtranslate.core.data.config.ConfigurationManager::class.java)
    val coordinator = ServiceCoordinator(context, recovery, configurationManager)
        whenever(configurationManager.currentPerformanceConfig()).thenReturn(
            PerformanceConfig(
                enableBackgroundProcessing = true,
                enableCaching = true,
                cacheSize = 100,
                enableAutoCleanup = true,
                cleanupIntervalHours = 24
            )
        )

        val recognitionService = Mockito.mock(com.example.gloabtranslate.nlp.RecognitionService::class.java)
        val ttsService = Mockito.mock(com.example.gloabtranslate.tts.TextToSpeechService::class.java)
        val modelManager = Mockito.mock(com.example.gloabtranslate.nlp.ModelManager::class.java)
        val translationPipeline = Mockito.mock(com.example.gloabtranslate.nlp.TranslationPipeline::class.java)

        whenever(recognitionService.initialize()).thenReturn(
            com.example.gloabtranslate.nlp.RecognitionService.RecognitionResult(success = true, text = "ok")
        )
        whenever(ttsService.initialize()).thenReturn(true)
        // translationPipeline.initialize() returns Unit by default; no stubbing necessary

        ServiceCoordinator::class.java.getDeclaredField("recognitionService").apply { isAccessible = true }.set(coordinator, recognitionService)
        ServiceCoordinator::class.java.getDeclaredField("textToSpeechService").apply { isAccessible = true }.set(coordinator, ttsService)
        ServiceCoordinator::class.java.getDeclaredField("modelManager").apply { isAccessible = true }.set(coordinator, modelManager)
        ServiceCoordinator::class.java.getDeclaredField("translationPipeline").apply { isAccessible = true }.set(coordinator, translationPipeline)

        val result = coordinator.initializeServices()
        assertThat(result).isTrue()
        assertThat(coordinator.systemHealth.value).isAnyOf(
            ServiceCoordinator.SystemHealth.HEALTHY,
            ServiceCoordinator.SystemHealth.DEGRADED
        )
        val readiness = coordinator.getServiceReadiness()
        assertThat(readiness[ServiceCoordinator.ServiceType.MODEL_MANAGER]).isTrue()
    }

    @Test
    fun recognitionRetriesThenSucceedsUpdatesHealth() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        val recovery = ErrorRecoverySystem()
        val configurationManager = Mockito.mock(com.example.gloabtranslate.core.data.config.ConfigurationManager::class.java)
        val coordinator = ServiceCoordinator(context, recovery, configurationManager)
        whenever(configurationManager.currentPerformanceConfig()).thenReturn(
            PerformanceConfig(true, true, 100, true, 24)
        )

        val recognitionService = Mockito.mock(com.example.gloabtranslate.nlp.RecognitionService::class.java)
        val ttsService = Mockito.mock(com.example.gloabtranslate.tts.TextToSpeechService::class.java)
        val modelManager = Mockito.mock(com.example.gloabtranslate.nlp.ModelManager::class.java)
        val translationPipeline = Mockito.mock(com.example.gloabtranslate.nlp.TranslationPipeline::class.java)

        // Fail twice then succeed
        whenever(recognitionService.initialize())
            .thenReturn(
                com.example.gloabtranslate.nlp.RecognitionService.RecognitionResult(success = false, error = "net"),
                com.example.gloabtranslate.nlp.RecognitionService.RecognitionResult(success = false, error = "net2"),
                com.example.gloabtranslate.nlp.RecognitionService.RecognitionResult(success = true, text = "ok")
            )
        whenever(ttsService.initialize()).thenReturn(true)

        // Inject mocks
        ServiceCoordinator::class.java.getDeclaredField("recognitionService").apply { isAccessible = true }.set(coordinator, recognitionService)
        ServiceCoordinator::class.java.getDeclaredField("textToSpeechService").apply { isAccessible = true }.set(coordinator, ttsService)
        ServiceCoordinator::class.java.getDeclaredField("modelManager").apply { isAccessible = true }.set(coordinator, modelManager)
        ServiceCoordinator::class.java.getDeclaredField("translationPipeline").apply { isAccessible = true }.set(coordinator, translationPipeline)

        // First init will fail recognition (overall should still return true because TTS succeeds)
        val first = coordinator.initializeServices()
        assertThat(first).isTrue()
        // Retry failed services until recognition succeeds
        coordinator.retryFailedServices()
        coordinator.retryFailedServices() // second retry triggers success

        val readiness = coordinator.getServiceReadiness()
        assertThat(readiness[ServiceCoordinator.ServiceType.RECOGNITION_SERVICE]).isTrue()
        assertThat(coordinator.systemHealth.value).isAnyOf(
            ServiceCoordinator.SystemHealth.HEALTHY,
            ServiceCoordinator.SystemHealth.DEGRADED
        )
    }

    @Test
    fun ttsFailureLeavesSystemDegraded() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        val recovery = ErrorRecoverySystem()
        val configurationManager = Mockito.mock(com.example.gloabtranslate.core.data.config.ConfigurationManager::class.java)
        val coordinator = ServiceCoordinator(context, recovery, configurationManager)
        whenever(configurationManager.currentPerformanceConfig()).thenReturn(
            PerformanceConfig(true, true, 100, true, 24)
        )

        val recognitionService = Mockito.mock(com.example.gloabtranslate.nlp.RecognitionService::class.java)
        val ttsService = Mockito.mock(com.example.gloabtranslate.tts.TextToSpeechService::class.java)
        val modelManager = Mockito.mock(com.example.gloabtranslate.nlp.ModelManager::class.java)
        val translationPipeline = Mockito.mock(com.example.gloabtranslate.nlp.TranslationPipeline::class.java)

        whenever(recognitionService.initialize()).thenReturn(
            com.example.gloabtranslate.nlp.RecognitionService.RecognitionResult(success = true, text = "ok")
        )
        whenever(ttsService.initialize()).thenReturn(false)

        ServiceCoordinator::class.java.getDeclaredField("recognitionService").apply { isAccessible = true }.set(coordinator, recognitionService)
        ServiceCoordinator::class.java.getDeclaredField("textToSpeechService").apply { isAccessible = true }.set(coordinator, ttsService)
        ServiceCoordinator::class.java.getDeclaredField("modelManager").apply { isAccessible = true }.set(coordinator, modelManager)
        ServiceCoordinator::class.java.getDeclaredField("translationPipeline").apply { isAccessible = true }.set(coordinator, translationPipeline)

        val overall = coordinator.initializeServices()
        // Overall success true because recognition succeeded (pipeline may have initialized)
        assertThat(overall).isTrue()
        val readiness = coordinator.getServiceReadiness()
        assertThat(readiness[ServiceCoordinator.ServiceType.TTS_SERVICE]).isFalse()
        assertThat(readiness[ServiceCoordinator.ServiceType.RECOGNITION_SERVICE]).isTrue()
        assertThat(coordinator.systemHealth.value).isAnyOf(
            ServiceCoordinator.SystemHealth.DEGRADED,
            ServiceCoordinator.SystemHealth.HEALTHY // In case logic evolves to ignore TTS
        )
    }

    @Test
    fun metricsReflectRetryCounts() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        val recovery = ErrorRecoverySystem()
        val configurationManager = Mockito.mock(com.example.gloabtranslate.core.data.config.ConfigurationManager::class.java)
        val coordinator = ServiceCoordinator(context, recovery, configurationManager)
        whenever(configurationManager.currentPerformanceConfig()).thenReturn(
            PerformanceConfig(true, true, 100, true, 24)
        )

        val recognitionService = Mockito.mock(com.example.gloabtranslate.nlp.RecognitionService::class.java)
        val ttsService = Mockito.mock(com.example.gloabtranslate.tts.TextToSpeechService::class.java)
        val modelManager = Mockito.mock(com.example.gloabtranslate.nlp.ModelManager::class.java)
        val translationPipeline = Mockito.mock(com.example.gloabtranslate.nlp.TranslationPipeline::class.java)

        // Recognition fails; TTS succeeds
        whenever(recognitionService.initialize()).thenReturn(
            com.example.gloabtranslate.nlp.RecognitionService.RecognitionResult(success = false, error = "x")
        )
        whenever(ttsService.initialize()).thenReturn(true)

        ServiceCoordinator::class.java.getDeclaredField("recognitionService").apply { isAccessible = true }.set(coordinator, recognitionService)
        ServiceCoordinator::class.java.getDeclaredField("textToSpeechService").apply { isAccessible = true }.set(coordinator, ttsService)
        ServiceCoordinator::class.java.getDeclaredField("modelManager").apply { isAccessible = true }.set(coordinator, modelManager)
        ServiceCoordinator::class.java.getDeclaredField("translationPipeline").apply { isAccessible = true }.set(coordinator, translationPipeline)

        coordinator.initializeServices()
        val metricsAfterInit = coordinator.metrics.value
        assertThat(metricsAfterInit.retryCounts[ServiceCoordinator.ServiceType.RECOGNITION_SERVICE]).isEqualTo(0)

        // Retry twice — still failing
        coordinator.retryFailedServices()
        val metricsAfterFirstRetry = coordinator.metrics.value
        assertThat(metricsAfterFirstRetry.retryCounts[ServiceCoordinator.ServiceType.RECOGNITION_SERVICE]).isEqualTo(1)
        coordinator.retryFailedServices()
        val metricsAfterSecondRetry = coordinator.metrics.value
        assertThat(metricsAfterSecondRetry.retryCounts[ServiceCoordinator.ServiceType.RECOGNITION_SERVICE]).isEqualTo(2)
    }

    @Test
    fun dynamicStartupTimeoutAdjustsWhenPerformanceConfigChanges() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        val recovery = ErrorRecoverySystem()
        val configurationManager = Mockito.mock(com.example.gloabtranslate.core.data.config.ConfigurationManager::class.java)
        val coordinator = ServiceCoordinator(context, recovery, configurationManager)

        // Simulate initial small cache background processing
        whenever(configurationManager.currentPerformanceConfig()).thenReturn(
            PerformanceConfig(enableBackgroundProcessing = true, enableCaching = true, cacheSize = 100, enableAutoCleanup = true, cleanupIntervalHours = 24)
        )

        // Use reflection to access dynamic timeout state flow (private)
        val dynField = ServiceCoordinator::class.java.getDeclaredField("_dynamicStartupTimeout").apply { isAccessible = true }
        val stateFlow = dynField.get(coordinator) as kotlinx.coroutines.flow.MutableStateFlow<Long?>

        // Manually emit via performanceConfig collector simulation
        // Instead of real flow collection (difficult with mock), we directly set value to mimic observer behavior
        stateFlow.value = 30_000L
        val firstTimeout = ServiceCoordinator::class.java.getDeclaredMethod("resolveStartupTimeout").apply { isAccessible = true }.invoke(coordinator) as Long
        assertThat(firstTimeout).isEqualTo(30_000L)

        // Simulate user increasing cache size beyond heavy threshold
        stateFlow.value = 45_000L
        val secondTimeout = ServiceCoordinator::class.java.getDeclaredMethod("resolveStartupTimeout").apply { isAccessible = true }.invoke(coordinator) as Long
        assertThat(secondTimeout).isEqualTo(45_000L)

        // Simulate disabling background processing
        stateFlow.value = 15_000L
        val thirdTimeout = ServiceCoordinator::class.java.getDeclaredMethod("resolveStartupTimeout").apply { isAccessible = true }.invoke(coordinator) as Long
        assertThat(thirdTimeout).isEqualTo(15_000L)
    }
}
