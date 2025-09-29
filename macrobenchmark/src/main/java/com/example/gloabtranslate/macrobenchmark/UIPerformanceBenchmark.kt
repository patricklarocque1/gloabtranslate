package com.example.gloabtranslate.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks for UI performance and responsiveness.
 * 
 * Tests various UI interactions including:
 * - Button responsiveness
 * - Text rendering performance
 * - Layout performance
 * - Animation smoothness
 */
@RunWith(AndroidJUnit4::class)
class UIPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun buttonInteractionPerformance() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
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
        
        // Test rapid button interactions
        repeat(20) {
            val startButton = device.findObject(By.text("Start Translation"))
            val stopButton = device.findObject(By.text("Stop Translation"))
            
            if (startButton != null && startButton.isEnabled) {
                startButton.click()
                Thread.sleep(50)
            }
            
            if (stopButton != null && stopButton.isEnabled) {
                stopButton.click()
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun textRenderingPerformance() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(FrameTimingMetric()),
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
        
        // Test text rendering by starting and stopping service multiple times
        // This will cause text updates in the UI
        repeat(10) {
            device.findObject(By.text("Start Translation")).click()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
            
            Thread.sleep(1000)
            
            device.findObject(By.text("Stop Translation")).click()
            device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
            
            Thread.sleep(500)
        }
    }

    @Test
    fun layoutPerformance() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(FrameTimingMetric()),
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
        
        // Test layout performance by rotating device and testing UI responsiveness
        device.setOrientationLeft()
        Thread.sleep(1000)
        
        device.setOrientationNatural()
        Thread.sleep(1000)
        
        device.setOrientationRight()
        Thread.sleep(1000)
        
        device.setOrientationNatural()
        Thread.sleep(1000)
        
        // Test UI interactions after rotation
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun memoryPressureUI() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(FrameTimingMetric()),
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
        
        // Create memory pressure by rapid service start/stop cycles
        repeat(50) {
            device.findObject(By.text("Start Translation")).click()
            Thread.sleep(100)
            
            device.findObject(By.text("Stop Translation")).click()
            Thread.sleep(100)
        }
        
        // Test UI responsiveness after memory pressure
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
        
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun backgroundForegroundTransition() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(FrameTimingMetric()),
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
        
        // Start service
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
        
        // Test background/foreground transitions
        repeat(5) {
            // Go to background
            pressHome()
            Thread.sleep(2000)
            
            // Return to foreground
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
        }
        
        // Stop service
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }
}
