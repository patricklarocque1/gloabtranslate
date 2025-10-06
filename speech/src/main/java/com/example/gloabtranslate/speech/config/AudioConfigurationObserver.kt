package com.example.gloabtranslate.speech.config

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.config.AudioConfig
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Observes audio preference changes and notifies listeners about updates.
 * Critical configuration changes (sample rate, quality toggles, etc.) are surfaced so that
 * callers can restart audio pipelines only when needed.
 */
class AudioConfigurationObserver(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private const val TAG = "AudioConfigObserver"
    }

    // TODO: ConfigurationManager should be injected via constructor for proper DI
    private val configurationManager = com.example.gloabtranslate.core.data.config.ConfigurationManager(
        com.example.gloabtranslate.core.data.preferences.UserPreferencesManager.getInstance(context)
    )
    private var observationJob: Job? = null
    private var currentConfig: AudioConfig? = null

    // Initialize with no-op lambdas that accept (and ignore) the AudioConfig parameter explicitly
    private var onConfigChanged: (AudioConfig) -> Unit = { _ -> }
    private var onCriticalConfigChanged: (AudioConfig) -> Unit = { _ -> }

    fun start() {
        if (observationJob != null) return
        observationJob = scope.launch {
            configurationManager.audioConfig.collectLatest { newConfig ->
                val previous = currentConfig
                currentConfig = newConfig

                if (previous == null) {
                    // First emission – treat as critical so dependents fully configure themselves.
                    onCriticalConfigChanged(newConfig)
                    return@collectLatest
                }

                if (isCriticalChange(previous, newConfig)) {
                    Log.d(TAG, "Critical audio configuration change detected: $newConfig")
                    onCriticalConfigChanged(newConfig)
                } else if (previous != newConfig) {
                    Log.d(TAG, "Audio configuration updated: $newConfig")
                    onConfigChanged(newConfig)
                }
            }
        }
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
    }

    fun cleanup() {
        stop()
        scope.cancel()
    }

    fun setOnConfigChanged(listener: (AudioConfig) -> Unit) {
        onConfigChanged = listener
    }

    fun setOnCriticalConfigChanged(listener: (AudioConfig) -> Unit) {
        onCriticalConfigChanged = listener
    }

    fun updateCurrentConfig(config: AudioConfig) {
        currentConfig = config
    }

    fun getCurrentConfig(): AudioConfig? = currentConfig

    private fun isCriticalChange(previous: AudioConfig, next: AudioConfig): Boolean {
        return previous.sampleRate != next.sampleRate ||
            previous.audioQuality != next.audioQuality ||
            previous.enableContinuousRecording != next.enableContinuousRecording ||
            previous.enableAudioRecording != next.enableAudioRecording ||
            previous.enableVoiceRecognition != next.enableVoiceRecognition
    }
}
