package com.example.gloabtranslate.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive startup benchmarks for the Global Translate app.
 * 
 * Tests different startup scenarios including cold, warm, and hot starts,
 * as well as startup with different compilation modes.
 */
@RunWith(AndroidJUnit4::class)
class AppStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCold() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial()
    ) {
        pressHome()
        startActivityAndWait()
        // Wait for UI to be fully loaded
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
    }

    @Test
    fun startupWarm() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial()
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
    }

    @Test
    fun startupHot() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.HOT,
        compilationMode = CompilationMode.Partial()
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
    }

    @Test
    fun startupColdFullCompilation() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Full()
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
    }

    @Test
    fun startupWithPermissions() = benchmarkRule.measureRepeated(
        packageName = "com.example.gloabtranslate",
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial()
    ) {
        pressHome()
        startActivityAndWait()
        
        // Wait for permission dialog and grant permissions
        device.wait(Until.hasObject(By.text("Allow")), 3000)
        device.findObject(By.text("Allow")).click()
        
        // Wait for main UI to load
        device.wait(Until.hasObject(By.text("Global Translate")), 5000)
    }
}