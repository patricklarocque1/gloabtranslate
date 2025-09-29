package com.example.gloabtranslate.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget
import kotlin.annotation.Retention
import kotlin.annotation.Target

/**
 * Comprehensive benchmark suite that combines multiple performance metrics.
 * 
 * This suite provides end-to-end performance testing for the Global Translate app,
 * covering all major performance aspects in realistic usage scenarios.
 */
@OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ComprehensiveBenchmarkSuite {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun fullAppWorkflow() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(
            StartupTimingMetric(),
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max)
        ),
        iterations = 2,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial()
    ) {
        // Complete app workflow test
        pressHome()
        startActivityAndWait()
        
        // Wait for main UI
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        // Grant permissions if needed
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Complete translation workflow
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Simulate real usage
        Thread.sleep(10000) // 10 seconds of translation
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
        
        // Test background/foreground transition
        pressHome()
        Thread.sleep(2000)
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun stressTest() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max)
        ),
        iterations = 1,
        compilationMode = CompilationMode.Partial()
    ) {
        // Stress test with rapid operations
        pressHome()
        startActivityAndWait()
        
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Rapid start/stop cycles to stress test the system
        repeat(50) {
            device.findObject(By.text("Start Translation")).click()
            Thread.sleep(100)
            
            device.findObject(By.text("Stop Translation")).click()
            Thread.sleep(100)
        }
        
        // Test that app still functions after stress
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        Thread.sleep(5000)
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun batteryOptimizationTest() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max)
        ),
        iterations = 2,
        compilationMode = CompilationMode.Partial()
    ) {
        // Test app behavior under battery optimization scenarios
        pressHome()
        startActivityAndWait()
        
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Start service
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Simulate battery optimization scenarios
        repeat(5) {
            // Go to background (simulates battery optimization)
            pressHome()
            Thread.sleep(10000) // 10 seconds in background
            
            // Return to foreground
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        }
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun networkInterruptionTest() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max)
        ),
        iterations = 2,
        compilationMode = CompilationMode.Partial()
    ) {
        // Test app behavior during network interruptions
        pressHome()
        startActivityAndWait()
        
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Start service
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Simulate network interruptions by rapid background/foreground cycles
        repeat(10) {
            pressHome()
            Thread.sleep(1000)
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        }
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }
}
