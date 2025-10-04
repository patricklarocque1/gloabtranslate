package com.example.gloabtranslate.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.gloabtranslate.tts.TextToSpeechService
import kotlinx.coroutines.launch
import java.util.*

/**
 * Debug activity for manually testing TTS functionality.
 * Add to AndroidManifest.xml in debug builds:
 * 
 * <activity
 *     android:name=".debug.TTSTestActivity"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.MAIN" />
 *         <category android:name="android.intent.category.LAUNCHER" />
 *     </intent-filter>
 * </activity>
 */
class TTSTestActivity : ComponentActivity() {
    
    private lateinit var ttsService: TextToSpeechService
    private var testResults = mutableStateListOf<String>()
    private var isInitialized = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        ttsService = TextToSpeechService(this)
        
        setContent {
            TTSTestScreen()
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TTSTestScreen() {
        var testText by remember { mutableStateOf("Hello world, this is a TTS test") }
        var isInitialized by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(false) }
        var isAvailable by remember { mutableStateOf(false) }
        
        // Check TTS availability when the composable loads
        LaunchedEffect(Unit) {
            isAvailable = ttsService.isAvailable()
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "TTS Debug Testing",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Test Text Input
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("Test Text") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
            
            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { initializeTTS() },
                    enabled = !isInitialized && !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("Initialize TTS")
                    }
                }
                
                Button(
                    onClick = { testSpeak(testText) },
                    enabled = isInitialized && !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Speak Text")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { testStop() },
                    enabled = isInitialized,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop Speaking")
                }
                
                Button(
                    onClick = { testLanguages() },
                    enabled = isInitialized,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Languages")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { testSpeechRate() },
                    enabled = isInitialized,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Speed")
                }
                
                Button(
                    onClick = { testPitch() },
                    enabled = isInitialized,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Pitch")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { clearResults() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Results")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Status Display
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Status: ${if (isInitialized) "TTS Initialized ✓" else "TTS Not Initialized"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isInitialized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    
                    Text(
                        text = "Available: $isAvailable",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Test Results
            Text(
                text = "Test Results:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    if (testResults.isEmpty()) {
                        Text(
                            text = "No test results yet. Click Initialize TTS to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        testResults.forEach { result ->
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    
    private fun addResult(message: String) {
        val timestamp = System.currentTimeMillis()
        val formattedMessage = "[${Date(timestamp)}] $message"
        testResults.add(formattedMessage)
        Log.d("TTS_DEBUG", message)
    }
    
    private fun initializeTTS() {
        lifecycleScope.launch {
            addResult("🔄 Initializing TTS service...")
            try {
                val result = ttsService.initialize()
                if (result) {
                    isInitialized = true
                    addResult("✅ TTS initialized successfully!")
                    
                    // Get system info
                    val languages = ttsService.getSupportedLanguages()
                    addResult("📋 Supported languages: ${languages.size}")
                    addResult("🌍 Available languages: ${languages.joinToString(", ") { it.displayName }}")
                } else {
                    addResult("❌ TTS initialization failed")
                }
            } catch (e: Exception) {
                addResult("💥 TTS initialization error: ${e.message}")
                Log.e("TTS_DEBUG", "TTS init error", e)
            }
        }
    }
    
    private fun testSpeak(text: String) {
        lifecycleScope.launch {
            addResult("🗣 Speaking: \"$text\"")
            try {
                val result = ttsService.speak(text)
                if (result.success) {
                    addResult("✅ Speaking started successfully (ID: ${result.utteranceId})")
                } else {
                    addResult("❌ Speaking failed: ${result.error}")
                }
            } catch (e: Exception) {
                addResult("💥 Speaking error: ${e.message}")
                Log.e("TTS_DEBUG", "Speaking error", e)
            }
        }
    }
    
    private fun testStop() {
        lifecycleScope.launch {
            addResult("⏹ Stopping TTS...")
            try {
                val result = ttsService.stop()
                addResult(if (result) "✅ TTS stopped successfully" else "❌ TTS stop failed")
            } catch (e: Exception) {
                addResult("💥 Stop error: ${e.message}")
            }
        }
    }
    
    private fun testLanguages() {
        lifecycleScope.launch {
            addResult("🌍 Testing language support...")
            try {
                val testLocales = listOf(
                    Locale.ENGLISH,
                    Locale.FRENCH,
                    Locale.GERMAN,
                    Locale("es", "ES") // Spanish
                )
                
                testLocales.forEach { locale ->
                    val result = ttsService.setLanguage(locale)
                    addResult("${locale.displayName}: ${if (result) "✅ Supported" else "❌ Not supported"}")
                }
            } catch (e: Exception) {
                addResult("💥 Language test error: ${e.message}")
            }
        }
    }
    
    private fun testSpeechRate() {
        lifecycleScope.launch {
            addResult("🏃 Testing speech rates...")
            try {
                val rates = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                rates.forEach { rate ->
                    val result = ttsService.setSpeechRate(rate)
                    addResult("Rate ${rate}x: ${if (result) "✅" else "❌"}")
                    if (result) {
                        ttsService.speak("Testing speed $rate")
                        kotlinx.coroutines.delay(2000) // Wait between tests
                    }
                }
                ttsService.setSpeechRate(1.0f) // Reset to normal
            } catch (e: Exception) {
                addResult("💥 Speech rate test error: ${e.message}")
            }
        }
    }
    
    private fun testPitch() {
        lifecycleScope.launch {
            addResult("🎵 Testing pitch levels...")
            try {
                val pitches = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                pitches.forEach { pitch ->
                    val result = ttsService.setPitch(pitch)
                    addResult("Pitch ${pitch}x: ${if (result) "✅" else "❌"}")
                    if (result) {
                        ttsService.speak("Testing pitch $pitch")
                        kotlinx.coroutines.delay(2000) // Wait between tests
                    }
                }
                ttsService.setPitch(1.0f) // Reset to normal
            } catch (e: Exception) {
                addResult("💥 Pitch test error: ${e.message}")
            }
        }
    }
    
    private fun clearResults() {
        testResults.clear()
        addResult("🧹 Results cleared")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up TTS resources
        lifecycleScope.launch {
            ttsService.cleanup()
        }
    }
}