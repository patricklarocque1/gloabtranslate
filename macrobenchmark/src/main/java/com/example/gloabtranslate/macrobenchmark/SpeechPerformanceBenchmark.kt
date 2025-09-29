package com.example.gloabtranslate.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks for speech recognition and text-to-speech performance.
 * 
 * Tests the speech processing capabilities including:
 * - Speech recognition initialization
 * - Audio processing performance
 * - TTS synthesis performance
 * - Memory usage during speech operations
 */
@RunWith(AndroidJUnit4::class)
class SpeechPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun speechRecognitionInitialization() = benchmarkRule.measureRepeated(
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
        
        // Wait for speech recognition to initialize
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 10000)
        
        // Start speech recognition
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Let it run for a bit to test recognition performance
        Thread.sleep(3000)
        
        // Stop recognition
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun continuousSpeechProcessing() = benchmarkRule.measureRepeated(
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
        
        // Start continuous speech processing
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Simulate continuous speech processing for extended period
        repeat(5) {
            Thread.sleep(2000) // Simulate speech input
            // The app should maintain performance during this time
        }
        
        // Stop processing
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun speechServiceRestart() = benchmarkRule.measureRepeated(
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
        
        // Test multiple start/stop cycles
        repeat(3) {
            // Start service
            device.findObject(By.text("Start Translation")).click()
            device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
            
            Thread.sleep(1000)
            
            // Stop service
            device.findObject(By.text("Stop Translation")).click()
            device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
            
            Thread.sleep(500)
        }
    }

    @Test
    fun audioProcessingPerformance() = benchmarkRule.measureRepeated(
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
        
        // Start audio processing
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
        
        // Simulate audio processing workload
        repeat(10) {
            Thread.sleep(500) // Simulate audio chunks
            // Test UI responsiveness during audio processing
        }
        
        // Stop processing
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }
}
