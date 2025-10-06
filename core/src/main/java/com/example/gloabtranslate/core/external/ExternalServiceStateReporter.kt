package com.example.gloabtranslate.core.external

/**
 * Cross-module abstraction allowing feature modules (e.g., speech) to report service lifecycle
 * state without depending on app-layer ServiceCoordinator concrete type (avoids circular dep).
 */
interface ExternalServiceStateReporter {
    fun report(
        type: ExternalServiceType,
        status: ExternalServiceStatus,
        error: String? = null,
        metrics: Map<String, Any?>? = null
    )
}

enum class ExternalServiceType {
    AUDIO_RECORDING
}

enum class ExternalServiceStatus {
    INITIALIZING,
    READY,
    ERROR,
    STOPPED
}
