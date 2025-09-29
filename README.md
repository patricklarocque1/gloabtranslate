# gloabtranslate

<p align="center">
  <em>Multi-module Android app for live speech translation, transcription, and text-to-speech output — leveraging ML Kit, on‑device NLP, and modular architecture.</em>
</p>

<p align="center">
  <a href="https://github.com/patricklarocque1/gloabtranslate/actions/workflows/ci.yml"><img src="https://github.com/patricklarocque1/gloabtranslate/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.2.20-%237F52FF" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android%20SDK-36-green" alt="Android SDK" />
  <img src="https://img.shields.io/badge/minSdk-34-orange" alt="minSdk" />
</p>

---

## Overview
`gloabtranslate` is an experimental Android application showcasing an end‑to‑end real‑time translation pipeline:

1. Capture live audio
2. Perform speech recognition / transcription
3. Detect or select source + target language
4. Translate via ML Kit on device (with model download management)
5. Output translated text + optional TTS playback
6. Persist history and provide analytics/monitoring & graceful recovery

The codebase emphasizes resiliency, modular separation, and observability (structured logging, performance monitoring, and recovery strategies).

> NOTE: This is a work in progress; APIs, module boundaries and features may evolve.

## Feature Highlights
- On-device translation (ML Kit) with offline model management
- Language detection and selection UI
- Real-time transcription view and translation result panel
- TTS output (modular speech + tts layers)
- Offline queue / history persistence using Room
- Structured logging & performance monitoring hooks
- Error handling & recovery strategies (graceful degradation)
- Macrobenchmark module for performance evaluation
- Modular dependency injection setup (Dagger)

## Module Map
| Module | Purpose |
|--------|---------|
| `app` | Android app entry point, UI navigation, DI wiring across feature modules |
| `core` | Shared domain/data/utilities (config, logging, security, persistence, i18n, error handling) |
| `speech` | Audio capture, processing, and speech recognition integration |
| `nlp` | Translation pipeline components, model management, language utilities |
| `tts` | Text‑to‑Speech orchestration & voice selection |
| `macrobenchmark` | Performance & startup benchmarks (separate test-only APK) |
| `mylibrary` | Example library module (extension point / experiments) |

## Technology Stack
- Kotlin 2.2.x, Coroutines
- AndroidX (Lifecycle, Navigation, WorkManager, Media3)
- ML Kit: Translation, Language ID
- Play Services TFLite Lite Java
- Room (persistence) & KSP (if/when schema generation is used)
- Dagger (core DI) — could be migrated to Hilt later
- Gradle Version Catalog for dependency centralization
- Macrobenchmark & benchmarking libraries

## Project Structure
See `settings.gradle.kts` for included modules and `gradle/libs.versions.toml` for centralized versions.

## Getting Started
### Prerequisites
- JDK 21 (CI uses Temurin 21)
- Android Studio (latest Canary/Stable supporting AGP 8.13)
- Android SDK platforms + build tools for API 36 installed

### Clone
```bash
git clone https://github.com/patricklarocque1/gloabtranslate.git
cd gloabtranslate
```

### Build
```bash
./gradlew build
```

### Run App
Open in Android Studio and run the `app` configuration (or):
```bash
./gradlew :app:installDebug
adb shell am start -n com.example.gloabtranslate/.MainActivity
```

### Run Unit Tests
```bash
./gradlew test
```

### Assemble Release APK
```bash
./gradlew :app:assembleRelease
```
APK output: `app/build/outputs/apk/release/`

### Macrobenchmark
Run via Android Studio Benchmark Run Configuration or:
```bash
./gradlew :macrobenchmark:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```

## Dependency Management
All versions live in `gradle/libs.versions.toml`. To inspect available updates (if versions plugin added):
```bash
./gradlew dependencyUpdates -Drevision=release
```
Dependabot is configured to open grouped PRs weekly.

## Logging & Monitoring
`core/logging` and `core/monitoring` provide structured logging + performance hooks. Extend these to integrate Crashlytics or custom analytics (Firebase BOM already available via version catalog).

## Roadmap (WIP)
- [ ] Improve speech recognition provider abstraction
- [ ] Add Compose UI layer
- [ ] Expand offline caching & model download UX
- [ ] CodeQL / security workflow
- [ ] Hilt migration evaluation

## Contributing
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit changes: `git commit -m "feat: add new feature"`
4. Push branch & open PR
5. Ensure CI passes & request review

Conventional Commit style recommended (`feat:`, `fix:`, `chore:`, etc.).

### Development Guidelines
- Keep modules cohesive (avoid leaking implementation details)
- Favor suspending functions / flows for async work
- Provide recovery strategies for ML/network errors
- Add/update tests for new logic in `core`, `nlp`, `speech`, `tts`

## License
Apache 2.0 © 2025 Patrick Larocque — see [LICENSE](LICENSE).

---

> If this project helps you, consider ⭐ starring the repo and opening issues for suggestions.
