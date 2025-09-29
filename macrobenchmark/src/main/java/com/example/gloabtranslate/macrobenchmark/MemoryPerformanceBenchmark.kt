package com.example.gloabtranslate.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks for memory usage and performance during extended operations.
 * 
 * Tests memory consumption patterns including:
 * - Long-running translation sessions
 * - Memory leaks during service operations
 * - Garbage collection impact
 * - Memory usage under different load conditions
 */
@OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MemoryPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun longRunningTranslationSession() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 2,
        compilationMode = CompilationMode.Partial()
    ) {
        // Start the app
        pressHome()
        startActivityAndWait()
        
        // Wait for main UI
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        // Grant permissions if needed
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Start long-running translation session
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Run for extended period to test memory usage
        repeat(10) {
            Thread.sleep(5000) // 5 seconds per iteration = 50 seconds total
            // Memory usage should remain stable during this time
        }
        
        // Stop service
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun memoryUsageDuringServiceRestarts() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 3,
        compilationMode = CompilationMode.Partial()
    ) {
        // Start the app
        pressHome()
        startActivityAndWait()
        
        // Wait for main UI
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        // Grant permissions if needed
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Test memory usage during multiple service restarts
        repeat(20) {
            // Start service
            device.findObject(By.text("Start Translation")).click()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
            
            Thread.sleep(2000)
            
            // Stop service
            device.findObject(By.text("Stop Translation")).click()
            device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
            
            Thread.sleep(1000)
        }
    }

    @Test
    fun memoryPressureTest() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 2,
        compilationMode = CompilationMode.Partial()
    ) {
        // Start the app
        pressHome()
        startActivityAndWait()
        
        // Wait for main UI
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        // Grant permissions if needed
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Create memory pressure with rapid operations
        repeat(100) {
            device.findObject(By.text("Start Translation")).click()
            Thread.sleep(50)
            
            device.findObject(By.text("Stop Translation")).click()
            Thread.sleep(50)
        }
        
        // Test that app still functions after memory pressure
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        Thread.sleep(5000)
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun backgroundMemoryUsage() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 2,
        compilationMode = CompilationMode.Partial()
    ) {
        // Start the app
        pressHome()
        startActivityAndWait()
        
        // Wait for main UI
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        // Grant permissions if needed
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Start service
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Test memory usage while app is in background
        repeat(10) {
            // Go to background
            pressHome()
            Thread.sleep(5000)
            
            // Return to foreground
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
        }
        
        // Stop service
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun memoryCleanupAfterServiceStop() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 3,
        compilationMode = CompilationMode.Partial()
    ) {
        // Start the app
        pressHome()
        startActivityAndWait()
        
        // Wait for main UI
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
        
        // Grant permissions if needed
        if (device.hasObject(By.text("Allow"))) {
            device.findObject(By.text("Allow")).click()
            device.wait(Until.gone(By.text("Allow")), 3000)
        }
        
        // Start and stop service multiple times to test memory cleanup
        repeat(5) {
            // Start service
            device.findObject(By.text("Start Translation")).click()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
            
            Thread.sleep(3000)
            
            // Stop service
            device.findObject(By.text("Stop Translation")).click()
            device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
            
            // Wait for memory cleanup
            Thread.sleep(2000)
        }
    }
}
