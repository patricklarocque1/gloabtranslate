package com.example.gloabtranslate.core.logging

import android.util.Log
import com.example.gloabtranslate.core.data.config.DebugConfigProvider
import com.example.gloabtranslate.core.data.config.DebugConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight runtime-configurable logger that reacts to DebugConfig changes.
 * Delegates persistence/structured needs to StructuredLogger (if/when enabled separately).
 */
@Singleton
class DebugLogger @Inject constructor(
    private val configurationManager: DebugConfigProvider
) {
    enum class Level(val priority: Int) { VERBOSE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4) }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val currentConfig = AtomicReference(
        DebugConfig(
            enableDebugMode = false,
            enableVerboseLogging = false,
            logLevel = "info",
            enablePerformanceMonitoring = false,
            enableCrashDumps = false
        )
    )
    private var collectJob: Job? = null

    init { startCollecting() }

    private fun startCollecting() {
        if (collectJob != null) return
        collectJob = scope.launch {
            configurationManager.debugConfig.collectLatest { cfg ->
                currentConfig.set(cfg)
            }
        }
    }

    private fun isEnabled(): Boolean = currentConfig.get().enableDebugMode

    private fun levelThreshold(): Level {
        val cfg = currentConfig.get()
        val base = when (cfg.logLevel.lowercase()) {
            "verbose" -> Level.VERBOSE
            "debug" -> Level.DEBUG
            "warn" -> Level.WARN
            "error" -> Level.ERROR
            else -> Level.INFO
        }
        // If verbose flag explicitly enabled, allow VERBOSE regardless of logLevel string
        return if (cfg.enableVerboseLogging && base.priority > Level.VERBOSE.priority) Level.VERBOSE else base
    }

    private fun shouldLog(level: Level): Boolean {
        if (!isEnabled()) return false
        return level.priority >= levelThreshold().priority
    }

    // Visible for unit tests (no Android Log assertions) to validate gating logic
    @JvmName("shouldLogForTest")
    fun shouldLogForTest(level: Level): Boolean = shouldLog(level)

    // Test-only helper to bypass async collection timing in unit tests
    @JvmName("setTestConfig")
    fun setTestConfig(config: DebugConfig) {
        currentConfig.set(config)
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) = log(Level.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(Level.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(Level.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Level.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Level.ERROR, tag, msg, tr)

    private fun log(level: Level, tag: String, msg: String, tr: Throwable?) {
        if (!shouldLog(level)) return
        when (level) {
            Level.VERBOSE -> if (tr != null) Log.v(tag, msg, tr) else Log.v(tag, msg)
            Level.DEBUG -> if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
            Level.INFO -> if (tr != null) Log.i(tag, msg, tr) else Log.i(tag, msg)
            Level.WARN -> if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
            Level.ERROR -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
    }
}
