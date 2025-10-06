package com.example.gloabtranslate.core.debug

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized debug mode controller.
 */
object DebugTools {
    private const val TAG = "DebugTools"
    private val debugEnabled = AtomicBoolean(false)
    private val analyticsEnabled = AtomicBoolean(false)

    fun setEnabled(enabled: Boolean) {
        if (debugEnabled.getAndSet(enabled) != enabled) {
            Log.i(TAG, "Debug mode ${if (enabled) "enabled" else "disabled"}")
        }
    }

    fun isEnabled(): Boolean = debugEnabled.get()

    fun setAnalyticsEnabled(enabled: Boolean) {
        if (analyticsEnabled.getAndSet(enabled) != enabled) {
            Log.i(TAG, "Analytics ${if (enabled) "enabled" else "disabled"}")
        }
    }

    fun isAnalyticsEnabled(): Boolean = analyticsEnabled.get()
}
