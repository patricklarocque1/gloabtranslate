package com.example.gloabtranslate.ui.uibehavior

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Central reactive UI behavior state derived from ConfigurationManager.uiConfig.
 * Exposes current flags (font scale, animations, haptics) for lightweight polling by views or adapters.
 * Future: integrate with a theming / composition layer.
 */
class UiBehaviorController @Inject constructor(
    private val configurationManager: com.example.gloabtranslate.core.data.config.ConfigurationManager
) {

    data class UiBehaviorState(
        val fontScale: Float = 1.0f,
        val enableAnimations: Boolean = true,
        val enableHaptics: Boolean = true
    )

    private val stateRef = AtomicReference(UiBehaviorState())
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var collectionJob: Job? = null

    fun start() {
        if (collectionJob != null) return
        collectionJob = scope.launch {
            configurationManager.uiConfig.collectLatest { cfg ->
                val scale = when (cfg.fontSize.lowercase()) {
                    "small" -> 0.85f
                    "medium" -> 1.0f
                    "large" -> 1.15f
                    "extra_large" -> 1.30f
                    else -> 1.0f
                }
                stateRef.set(
                    UiBehaviorState(
                        fontScale = scale,
                        enableAnimations = cfg.enableAnimations,
                        enableHaptics = cfg.enableHapticFeedback
                    )
                )
            }
        }
    }

    fun currentState(): UiBehaviorState = stateRef.get()

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }
}