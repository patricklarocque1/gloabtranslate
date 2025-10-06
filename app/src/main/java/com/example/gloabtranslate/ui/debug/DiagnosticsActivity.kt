package com.example.gloabtranslate.ui.debug

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gloabtranslate.R
import com.example.gloabtranslate.service.ServiceCoordinator
import dagger.android.AndroidInjection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Simple diagnostics screen showing service states, metrics, and circuit breaker snapshot.
 * Intentionally lightweight; can evolve into a fragment or compose screen later.
 */
class DiagnosticsActivity : AppCompatActivity() {

    @Inject lateinit var serviceCoordinator: ServiceCoordinator
    @Inject lateinit var errorRecoverySystem: com.example.gloabtranslate.core.error.ErrorRecoverySystem

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        val textView = findViewById<android.widget.TextView>(R.id.diagnostics_text)
        textView.movementMethod = ScrollingMovementMethod()

        lifecycleScope.launch {
            launch { serviceCoordinator.serviceStates.collectLatest { render(textView) } }
            launch { serviceCoordinator.metrics.collectLatest { render(textView) } }
            launch { errorRecoverySystem.circuitBreakerStates.collectLatest { render(textView) } }
        }

        render(textView)
    }

    private fun render(tv: android.widget.TextView) {
        val states = serviceCoordinator.serviceStates.value
        val metrics = serviceCoordinator.metrics.value
        val breakers = errorRecoverySystem.circuitBreakerStates.value

        val builder = StringBuilder()
            .appendLine("Service States:")
        states.forEach { (type, state) ->
            builder.appendLine(" - ${type.name}: ${state.status} (retries=${state.retryCount}${state.lastError?.let { ", error=$it" } ?: ""})")
        }
        builder.appendLine()
            .appendLine("System Health: ${serviceCoordinator.systemHealth.value}")
            .appendLine()
            .appendLine("Metrics (lastUpdated=${metrics.lastUpdated}):")
        metrics.retryCounts.forEach { (t, c) ->
            builder.appendLine(" - ${t.name} retries=$c lastError=${metrics.lastErrors[t]}")
        }
        builder.appendLine()
            .appendLine("Circuit Breakers:")
        if (breakers.isEmpty()) {
            builder.appendLine(" (none)")
        } else {
            breakers.forEach { (k,v) ->
                builder.appendLine(" - $k failures=${v.failureCount} open=${v.isOpen}")
            }
        }

        tv.text = builder.toString()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onCloseClicked(v: android.view.View) {
        finish()
    }
}
