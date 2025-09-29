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
 * Benchmarks for translation performance and UI responsiveness.
 * 
 * Tests the core translation functionality including:
 * - Text input and processing
 * - Translation result display
 * - UI interactions during translation
 */
@RunWith(AndroidJUnit4::class)
class TranslationPerformanceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun translationWorkflow() = benchmarkRule.measureRepeated(
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
        
        // Wait for service to be ready
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 10000)
        
        // Start translation service
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 5000)
        
        // Simulate some translation work
        Thread.sleep(2000)
        
        // Stop translation service
        device.findObject(By.text("Stop Translation")).click()
        device.wait(Until.hasObject(By.text("Live Translation Service Ready")), 5000)
    }

    @Test
    fun uiResponsiveness() = benchmarkRule.measureRepeated(
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
        
        // Test UI responsiveness with rapid button presses
        repeat(10) {
            if (device.hasObject(By.text("Start Translation"))) {
                device.findObject(By.text("Start Translation")).click()
                Thread.sleep(100)
            }
            if (device.hasObject(By.text("Stop Translation"))) {
                device.findObject(By.text("Stop Translation")).click()
                Thread.sleep(100)
            }
        }
    }

    @Test
    fun serviceStartupTime() = benchmarkRule.measureRepeated(
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
        
        // Measure service startup time
        device.findObject(By.text("Start Translation")).click()
        device.wait(Until.hasObject(By.text("Recording and translating...")), 10000)
    }
}
