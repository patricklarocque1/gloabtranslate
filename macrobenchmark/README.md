# Global Translate - Macrobenchmark Module

This module contains comprehensive performance benchmarks for the Global Translate app, designed to measure and optimize various aspects of the application's performance.

## Overview

The macrobenchmark module provides automated performance testing for:
- **App Startup Performance** - Cold, warm, and hot startup times
- **Translation Performance** - Core translation functionality and UI responsiveness
- **Speech Processing** - Speech recognition and TTS performance
- **UI Performance** - Button interactions, text rendering, and layout performance
- **Memory Usage** - Long-running sessions and memory leak detection
- **Comprehensive Testing** - End-to-end workflow and stress testing

## Benchmark Classes

### 1. AppStartupBenchmark
Tests different startup scenarios:
- `startupCold()` - Cold app startup
- `startupWarm()` - Warm app startup  
- `startupHot()` - Hot app startup
- `startupColdFullCompilation()` - Cold startup with full compilation
- `startupWithPermissions()` - Startup including permission flow

### 2. TranslationPerformanceBenchmark
Tests core translation functionality:
- `translationWorkflow()` - Complete translation workflow
- `uiResponsiveness()` - UI responsiveness during operations
- `serviceStartupTime()` - Translation service startup performance

### 3. SpeechPerformanceBenchmark
Tests speech processing capabilities:
- `speechRecognitionInitialization()` - Speech recognition setup
- `continuousSpeechProcessing()` - Extended speech processing
- `speechServiceRestart()` - Service restart performance
- `audioProcessingPerformance()` - Audio processing efficiency

### 4. UIPerformanceBenchmark
Tests UI performance and responsiveness:
- `buttonInteractionPerformance()` - Button response times
- `textRenderingPerformance()` - Text rendering efficiency
- `layoutPerformance()` - Layout performance during orientation changes
- `memoryPressureUI()` - UI performance under memory pressure
- `backgroundForegroundTransition()` - Background/foreground transitions

### 5. MemoryPerformanceBenchmark
Tests memory usage patterns:
- `longRunningTranslationSession()` - Memory usage during extended sessions
- `memoryUsageDuringServiceRestarts()` - Memory usage during service cycles
- `memoryPressureTest()` - Memory behavior under pressure
- `backgroundMemoryUsage()` - Memory usage in background
- `memoryCleanupAfterServiceStop()` - Memory cleanup verification

### 6. ComprehensiveBenchmarkSuite
End-to-end performance testing:
- `fullAppWorkflow()` - Complete app workflow with all metrics
- `stressTest()` - Stress testing with rapid operations
- `batteryOptimizationTest()` - Battery optimization scenarios
- `networkInterruptionTest()` - Network interruption handling

## Running Benchmarks

### Prerequisites
1. Ensure the main app has `<profileable android:shell="true" />` in its manifest
2. Build the app in release mode for accurate benchmarking
3. Use a physical device for best results (emulators may give inconsistent results)

### Running Individual Benchmarks
```bash
# Run all benchmarks
./gradlew :macrobenchmark:connectedAndroidTest

# Run specific benchmark class
./gradlew :macrobenchmark:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.gloabtranslate.macrobenchmark.AppStartupBenchmark

# Run specific test method
./gradlew :macrobenchmark:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.gloabtranslate.macrobenchmark.AppStartupBenchmark#startupCold
```

### Running from Android Studio
1. Open the macrobenchmark module
2. Right-click on a benchmark class or method
3. Select "Run 'TestName'"
4. View results in the "Run" window

## Benchmark Results

Results are saved in:
- **Android Studio**: View in the "Run" window or "Profiler" tab
- **Command Line**: Check `app/build/outputs/connected_android_test_additional_outputs/`
- **CI/CD**: Results are typically stored in build artifacts

### Key Metrics
- **Startup Time**: Time from app launch to first frame
- **Frame Timing**: Frame rendering performance (FPS, jank)
- **Memory Usage**: Heap usage and memory allocation patterns
- **CPU Usage**: CPU utilization during operations

## Configuration

### Build Variants
The benchmark module uses the "benchmark" build type which:
- Enables minification and resource shrinking (like release)
- Uses debug signing for easy testing
- Enables detailed logging for debugging

### Customization
To customize benchmarks:
1. Modify iteration counts in individual test methods
2. Adjust sleep times to simulate different usage patterns
3. Add new test scenarios in existing classes
4. Create new benchmark classes for specific features

## Best Practices

1. **Use Physical Devices**: Emulators may not provide accurate performance measurements
2. **Warm Up**: Run benchmarks multiple times to get consistent results
3. **Isolate Tests**: Ensure each benchmark tests one specific aspect
4. **Realistic Scenarios**: Use realistic user interactions and timing
5. **Monitor Results**: Track performance trends over time
6. **Profile Issues**: Use Android Studio Profiler to investigate performance problems

## Troubleshooting

### Common Issues
- **Permission Denied**: Ensure all required permissions are granted
- **Service Not Starting**: Check that the main app's service is properly configured
- **UI Elements Not Found**: Verify UI element selectors match the current app version
- **Inconsistent Results**: Use physical devices and ensure stable conditions

### Debug Tips
- Enable detailed logging in benchmark configuration
- Use UI Automator Viewer to inspect UI elements
- Check device logs for errors during benchmark execution
- Verify app state before and after each benchmark iteration

## Contributing

When adding new benchmarks:
1. Follow the existing naming conventions
2. Include comprehensive documentation
3. Test on multiple devices and Android versions
4. Ensure benchmarks are deterministic and repeatable
5. Add appropriate error handling and timeouts
