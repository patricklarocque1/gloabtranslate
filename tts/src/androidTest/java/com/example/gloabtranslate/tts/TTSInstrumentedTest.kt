package com.example.gloabtranslate.tts

import android.content.Context
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue

/**
 * Instrumented tests for TTS module that run on Android device/emulator.
 * These tests require a real Android environment with TTS engines installed.
 * 
 * Run with: ./gradlew tts:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class TTSInstrumentedTest {
    
    private lateinit var context: Context
    private lateinit var ttsService: TextToSpeechService
    
    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ttsService = TextToSpeechService(context)
    }
    
    @Test
    fun testTTSInitialization() = runTest {
        // Test that TTS can initialize on real device
        val result = ttsService.initialize()
        assertTrue("TTS should initialize successfully on device", result)
    }
    
    @Test
    fun testTTSBasicSpeaking() = runTest {
        // Initialize first
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for speaking test", initResult)
        
        // Test basic speaking functionality
        val speakResult = ttsService.speak("Hello from TTS instrumented test")
        assertTrue("Speaking should succeed on device", speakResult.success)
    }
    
    @Test
    fun testLanguageSupport() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for language test", initResult)
        
        val languages = ttsService.getSupportedLanguages()
        assertFalse("Should have supported languages on device", languages.isEmpty())
        assertTrue("Should support English on device", languages.contains(Locale.ENGLISH))
    }
    
    @Test
    fun testSpeechRateConfiguration() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for rate test", initResult)
        
        // Test speech rate adjustment
        val result1 = ttsService.setSpeechRate(0.5f) // Slow
        assertTrue("Should set slow speech rate", result1)
        
        val result2 = ttsService.setSpeechRate(2.0f) // Fast
        assertTrue("Should set fast speech rate", result2)
        
        val result3 = ttsService.setSpeechRate(1.0f) // Normal
        assertTrue("Should set normal speech rate", result3)
    }
    
    @Test
    fun testPitchConfiguration() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for pitch test", initResult)
        
        // Test pitch adjustment
        val result1 = ttsService.setPitch(0.5f) // Low pitch
        assertTrue("Should set low pitch", result1)
        
        val result2 = ttsService.setPitch(2.0f) // High pitch  
        assertTrue("Should set high pitch", result2)
        
        val result3 = ttsService.setPitch(1.0f) // Normal pitch
        assertTrue("Should set normal pitch", result3)
    }
    
    @Test
    fun testTTSAvailability() = runTest {
        // Test before initialization
        val availableBefore = ttsService.isAvailable()
        assertFalse("Should not be available before initialization", availableBefore)
        
        // Initialize and test again
        val initResult = ttsService.initialize()
        assumeTrue("TTS must initialize for availability test", initResult)
        
        val availableAfter = ttsService.isAvailable()
        assertTrue("Should be available after initialization", availableAfter)
    }
    
    @Test
    fun testTTSStopFunctionality() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for stop test", initResult)
        
        // Start speaking
        val speakResult = ttsService.speak("This is a long message that should be stoppable during playback")
        assumeTrue("Speaking must start successfully", speakResult.success)
        
        // Stop speaking
        val stopResult = ttsService.stop()
        assertTrue("Should be able to stop TTS", stopResult)
    }
    
    @Test
    fun testErrorHandling() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for error handling test", initResult)
        
        // Test empty text
        val emptyResult = ttsService.speak("")
        assertFalse("Should handle empty text gracefully", emptyResult.success)
        
        // Test null text should not crash
        val nullResult = ttsService.speak(null as String?)
        assertFalse("Should handle null text gracefully", nullResult.success)
    }
    
    @Test
    fun testTTSCleanup() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized for cleanup test", initResult)
        
        // Verify it's available
        assertTrue("Should be available before cleanup", ttsService.isAvailable())
        
        // Cleanup
        ttsService.cleanup()
        
        // Should not be available after cleanup
        assertFalse("Should not be available after cleanup", ttsService.isAvailable())
    }
}