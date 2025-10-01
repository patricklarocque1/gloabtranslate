# TTS Module Testing Guide

## Testing on Android Emulator/Device

### Prerequisites
1. **Android Studio** installed with SDK
2. **Android Emulator** or physical Android device
3. **ADB (Android Debug Bridge)** configured

### Setup Steps

#### 1. Create/Start Android Emulator
```bash
# List available emulators
emulator -list-avds

# Start emulator (replace 'your_avd_name' with actual AVD name)
emulator -avd your_avd_name
```

#### 2. Build and Install the App
```bash
# From project root
cd /workspaces/gloabtranslate

# Build debug APK
./gradlew assembleDebug

# Install on device/emulator
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### 3. Enable TTS Engine on Device
```bash
# Check available TTS engines
adb shell settings get secure tts_default_synth

# List installed TTS engines
adb shell pm list packages | grep -i tts
```

### Testing Scenarios

#### 1. Basic TTS Functionality Test
Create a simple test activity to verify TTS works:

```kotlin
// Add to app/src/debug/java/com/example/gloabtranslate/debug/TTSTestActivity.kt
class TTSTestActivity : ComponentActivity() {
    private lateinit var ttsService: TextToSpeechService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        ttsService = TextToSpeechService(this)
        
        // Test TTS initialization
        lifecycleScope.launch {
            val initialized = ttsService.initialize()
            Log.d("TTS_TEST", "TTS Initialized: $initialized")
            
            if (initialized) {
                // Test speaking
                val result = ttsService.speak("Hello world, this is a TTS test")
                Log.d("TTS_TEST", "Speak result: $result")
            }
        }
    }
}
```

#### 2. Logcat Monitoring
Monitor TTS-related logs during testing:

```bash
# Filter TTS logs
adb logcat | grep -i "tts\|speech"

# Specific to our service
adb logcat | grep "TextToSpeechService"

# Full debug logs
adb logcat -v time | grep -E "(TTS|TextToSpeech|speech)"
```

#### 3. System TTS Settings
Check system TTS configuration:

```bash
# Open TTS settings on device
adb shell am start -a android.settings.TTS_SETTINGS

# Check current TTS engine
adb shell settings get secure tts_default_synth

# List available voices
adb shell "service call audio 1" # Audio service info
```

### Debugging Common Issues

#### Issue 1: TTS Engine Not Available
**Symptoms:** Initialize returns false
**Debug:**
```bash
# Check if Google TTS is installed
adb shell pm list packages | grep google.tts

# Install Google TTS if missing
adb install path/to/google-tts.apk
```

#### Issue 2: No Audio Output
**Symptoms:** TTS appears to work but no sound
**Debug:**
```bash
# Check audio volume
adb shell media volume --show

# Test system audio
adb shell media volume --set 10

# Check audio focus
adb logcat | grep AudioFocus
```

#### Issue 3: Language Not Supported
**Symptoms:** setLanguage fails
**Debug:**
```bash
# Check available languages
adb logcat | grep "TTS.*language"

# Test with different locales
# Use EN-US as fallback
```

### Instrumented Tests for TTS

Create Android instrumented tests that run on device:

```kotlin
// tts/src/androidTest/java/com/example/gloabtranslate/tts/TTSInstrumentedTest.kt
@RunWith(AndroidJUnit4::class)
class TTSInstrumentedTest {
    
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)
    
    private lateinit var context: Context
    private lateinit var ttsService: TextToSpeechService
    
    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ttsService = TextToSpeechService(context)
    }
    
    @Test
    fun testTTSInitialization() = runTest {
        val result = ttsService.initialize()
        assertTrue("TTS should initialize successfully", result)
    }
    
    @Test
    fun testTTSSpeaking() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized", initResult)
        
        val speakResult = ttsService.speak("Test message")
        assertTrue("Speaking should succeed", speakResult.success)
    }
    
    @Test
    fun testLanguageSupport() = runTest {
        val initResult = ttsService.initialize()
        assumeTrue("TTS must be initialized", initResult)
        
        val languages = ttsService.getSupportedLanguages()
        assertFalse("Should have supported languages", languages.isEmpty())
        assertTrue("Should support English", languages.contains(Locale.ENGLISH))
    }
}
```

### Run Instrumented Tests
```bash
# Run TTS instrumented tests on connected device
./gradlew tts:connectedAndroidTest

# Run specific test
./gradlew tts:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.gloabtranslate.tts.TTSInstrumentedTest
```

### Manual Testing Checklist

1. **☐ TTS Initialization**
   - Service starts without exceptions
   - Returns true for successful init
   - Logs show TTS engine connection

2. **☐ Basic Speaking**
   - Simple text speaks audibly  
   - Different languages work
   - Volume levels appropriate

3. **☐ Configuration Changes**
   - Speech rate adjustment works
   - Pitch changes are audible
   - Voice selection functions

4. **☐ Error Handling**
   - Handles empty text gracefully
   - Recovers from TTS engine crashes
   - Reports meaningful errors

5. **☐ Lifecycle Management**
   - Proper initialization/cleanup
   - Handles app backgrounding
   - Memory usage reasonable

### Performance Testing

Monitor TTS performance metrics:

```bash
# Memory usage
adb shell dumpsys meminfo com.example.gloabtranslate

# CPU usage during TTS
adb shell top | grep gloabtranslate

# Battery impact
adb shell dumpsys batterystats | grep gloabtranslate
```

## Expected Results vs Unit Test Issues

**On Device:** TTS should work normally because:
- Real Android TTS framework available
- Proper audio system integration
- System-level TTS engines installed

**In Unit Tests:** Mocking challenges because:
- Android framework classes are complex
- Callback-based initialization patterns
- Hardware audio dependencies

This explains why unit tests fail but device testing should succeed.