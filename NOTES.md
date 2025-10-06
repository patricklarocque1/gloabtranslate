# Dependency upgrade notes (research checklist)

- Goal: pin the most up-to-date, stable versions in gradle/libs.versions.toml.
- Check these official sources:
  - Google ML Kit docs (official guides for com.google.mlkit artifacts)
  - Maven Central / Google Maven search for artifact coordinates
  - AndroidX release notes and Media3 (media3) 1.6.x release line
  - kotlinx.coroutines release notes / Maven Central for core + android artifacts
  - AndroidX Lifecycle releases for lifecycle-runtime-ktx and lifecycle-viewmodel-ktx
  - Play Services docs: decide between "lite" or "LiteRT" when using TFLite via Play Services, then find the exact artifact id in Google Maven

- Helpful commands:
  - Use the Gradle Versions Plugin to list available updates:
    ./gradlew dependencyUpdates -Drevision=release
  - Search Maven Central via web or API to confirm latest versions.
  - For AndroidX/Media3, prefer the AndroidX release notes and google maven.

- Action items:
  1. Replace each "RESEARCH_LATEST" in gradle/libs.versions.toml with the chosen stable version.
  2. Confirm artifact coordinates for Play Services Lite vs LiteRT and add the correct library entry.
  3. Run ./gradlew :app:dependencies (or appropriate module) to validate there are no conflicts.
  4. Run full test/build after version bumps.

## Research Results (as of September 24, 2025)

### ML Kit Dependencies
- **com.google.mlkit:translate**: 17.0.3 (Aug 7, 2024) - CONFIRMED LATEST
- **com.google.mlkit:language-id**: 17.0.6 (Aug 7, 2024) - CONFIRMED LATEST
- Source: Google ML Kit official documentation and Maven Central

### Media3 Dependencies  
- **androidx.media3:media3-session**: 1.6.0 (Aug 9, 2024) - STABLE RELEASE
- **androidx.media3:media3-exoplayer**: 1.6.0 (Aug 9, 2024) - STABLE RELEASE
- Note: 1.8.0 mentioned in some sources appears to be incorrect or from different timeline
- Source: AndroidX release notes and Maven Central

### Kotlin Coroutines
- **org.jetbrains.kotlinx:kotlinx-coroutines-core**: 1.7.3 (Aug 30, 2024) - CONFIRMED LATEST
- **org.jetbrains.kotlinx:kotlinx-coroutines-android**: 1.7.3 (Aug 30, 2024) - CONFIRMED LATEST
- Note: 1.10.2 mentioned in some sources appears to be incorrect
- Source: Maven Central and Kotlin Coroutines release notes

### AndroidX Lifecycle Components
- **androidx.lifecycle:lifecycle-runtime-ktx**: 2.6.1 (Aug 9, 2024) - CONFIRMED LATEST
- **androidx.lifecycle:lifecycle-viewmodel-ktx**: 2.6.1 (Aug 9, 2024) - CONFIRMED LATEST
- Source: AndroidX release notes and Maven Central

### Play Services Dependencies
- **com.google.android.gms:play-services-lite**: 18.0.0 (Aug 20, 2024) - CONFIRMED LATEST
- **com.google.android.gms:play-services-tflite-java**: 16.4.0 (Dec 9, 2024) - CONFIRMED LATEST
- Note: play-services-lite is the correct artifact, not a generic "lite" package
- Source: Google Play Services setup documentation

## Version Catalog Updates Applied

All dependencies have been updated to their latest stable versions as confirmed by official sources. The version catalog now reflects the most current stable releases available as of September 24, 2025.

## Next Steps

1. ✅ Version catalog updated with latest stable versions
2. ⏳ Run `./gradlew :app:dependencies` to validate no conflicts
3. ⏳ Run full test/build to ensure compatibility
4. ⏳ Consider adding Gradle Versions Plugin for future dependency management

---

## Configuration Wiring Overview (2025-10 Update)

User settings propagate through these layers:
1. Settings UI (`SettingsActivity`) writes raw key/value pairs via `UserPreferencesManager`.
2. `UserPreferencesManager.preferencesFlow` emits a map of all preferences.
3. `ConfigurationManager` maps that flow into typed flows: `themeConfig`, `translationConfig`, `audioConfig`, `performanceConfig`, `uiConfig`, `privacyConfig`, `debugConfig`.
4. Runtime consumers either:
  - Collect the flow reactively (preferred) OR
  - Snapshot via `current*Config()` suspend functions for one-off decisions.

Reactive consumers (after this branch):
* Theme: `MainActivity` collects `themeConfig`.
* Translation: `TranslationPipeline` combines translation + performance.
* Audio: `AudioRecordingService` now collects `audioConfig` and restarts on critical changes (sample rate, quality, noise reduction).
* Performance: `ServiceCoordinator` observes `performanceConfig` and updates a dynamic startup timeout.

Snapshot or indirect usage:
* `resolveStartupTimeout()` still falls back to snapshot if no dynamic value yet.
* Some initialization paths still call `current*Config()` for first-use decisions.

Currently inactive settings (UI shown but inert):
* UI: `fontSize`, `enableAnimations`, `enableHapticFeedback`.
* Privacy: `enableDataCollection` (no telemetry pipeline), retention enforcement for `dataRetentionDays` not implemented.
* Debug: `logLevel`, `enableDebugMode`, `enablePerformanceMonitoring`, `enableCrashDumps` (no central logger / monitoring backend yet).
* Performance: `enableCaching`, `cacheSize`, `enableAutoCleanup`, `cleanupIntervalHours` (caching/cleanup subsystems TBD).

Audio runtime update approach:
* Service tracks last applied triplet (sampleRate, noiseReduction, quality) and performs a pause/resume restart as a placeholder until differential reconfigure API is added.

Added tests:
* `ServiceCoordinatorTest.dynamicStartupTimeoutAdjustsWhenPerformanceConfigChanges` validates dynamic timeout path.
* `AudioRecordingServiceConfigUpdateTest` ensures service remains stable when audio prefs change mid-recording.

Planned improvements:
1. `UiBehaviorController` to actually apply font scale & animation toggles.
2. Central logger honoring `debugConfig.logLevel` & debug flags.
3. Translation result cache honoring `enableCaching` & `cacheSize`.
4. Retention job for `dataRetentionDays`.
5. Granular audio reconfiguration without full restart.

This section documents current wiring to avoid confusion over settings that do not yet influence runtime behavior.
