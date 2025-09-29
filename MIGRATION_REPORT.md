| Library | Version | Expected packages | Code changes to apply |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.0 | `com.android.build.api.dsl.*` | Align module DSL with AGP 8.13 syntax, remove unsupported helpers (e.g. `compileSdk { version = release(36) }`). |
| Kotlin stdlib / compiler | 2.2.20 | `kotlin.*` | Ensure all modules rely on `libs.plugins.kotlin.android`/`kotlin` aliases; drop hardcoded plugin versions. |
| Kotlin Coroutines Core/Android | 1.10.2 | `kotlinx.coroutines.*` | Replace `GlobalScope` usage, migrate `launchWhen*` to `repeatOnLifecycle`, update tests to new APIs. |
| Lifecycle Runtime/ViewModel | 2.9.4 | `androidx.lifecycle.*` | Swap `launchWhenStarted/Resumed` for `repeatOnLifecycle`, update observers to new APIs. |
| Activity Result API | (androidx.activity 1.9.x via BOM) | `androidx.activity.result.*` | Replace `startActivityForResult`/`onActivityResult` with `registerForActivityResult` + `ActivityResultLauncher`. |
| Media3 ExoPlayer/Session | 1.6.1 | `androidx.media3.*` | Migrate `com.google.android.exoplayer2.*` imports to Media3, update player initialization to `ExoPlayer.Builder` & `androidx.media3.ui.PlayerView`. |
| ML Kit Translation | 17.0.3 | `com.google.mlkit.nl.translate.*` | Ensure usage of `TranslatorOptions` + `Translation.getClient`, add model download helpers. |
| ML Kit Language ID | 17.0.6 | `com.google.mlkit.nl.languageid.*` | Switch to `LanguageIdentification.getClient`, update async handling with coroutines/tasks bridging. |
| Room Runtime/KTX | 2.8.1 | `androidx.room.*` | Apply KSP plugin to Room modules, update DAO signatures to `suspend`/`Flow`, wire `ksp(libs.room.compiler)`. |
| WorkManager Runtime KTX | 2.10.5 | `androidx.work.*` | Verify background workers use updated APIs (e.g. `ForegroundInfo` builders), remove deprecated work constraints. |
| Navigation Fragment/UI KTX | 2.8.8 | `androidx.navigation.*` | Update navigation calls/imports to current APIs, ensure SafeArgs/graph references are valid. |
| TTS Module Stubs | n/a | `com.example.gloabtranslate.tts.*` | Provide minimal implementations for `VoiceSynthesizer`, `VoiceSelector`, `SpeechRateController`, `AudioOutputManager`, `TextToSpeechService` to unblock imports. |

## Updates Applied
- **Build tooling**
  - `build.gradle.kts` now applies `libs.plugins.kotlin.android` so KSP loads after the Kotlin plugin.
  - `gradle/libs.versions.toml` + `gradle/wrapper/gradle-wrapper.properties` pin AGP 8.13.0, Gradle 8.13, coroutines 1.10.2, lifecycle 2.9.4, navigation 2.8.8, workmanager 2.10.5, media3 1.6.1, and KSP 2.2.20-2.0.3.
  - Module scripts (`app/build.gradle.kts`, `core/build.gradle.kts`, `nlp/build.gradle.kts`, `speech/build.gradle.kts`, `tts/build.gradle.kts`, `macrobenchmark/build.gradle.kts`, `mylibrary/build.gradle.kts`) now apply `org.jetbrains.kotlin.android`, switch to the `packaging { resources { ... } }` DSL, fix `compileSdk = 36`, and align Java/Kotlin toolchains with 17.
- **ML Kit (`:nlp`)**
  - `ModelManager.kt` introduces `ensureLanguagePairAvailable` with a suspend `Task.await` helper to centralize translation model downloads.
  - `TranslationPipeline.kt`, `RecognitionService.kt`, and `RecognizerAvailabilityManager.kt` normalize imports to the new ML Kit packages, rely on `ModelManager.ensureLanguagePairAvailable`, and simplify translator/task orchestration.
  - Tests (`RecognizerAvailabilityManagerTest.kt`, `RecognitionServiceTest.kt`) mock `ModelManager` and task helpers to reflect the new download flow.
- **Room + KSP (`:core`)**
  - `core/build.gradle.kts` enables the KSP plugin and wires `ksp(libs.room.compiler)` so Room annotation processing uses KSP.
  - Room stack (`TranslationHistoryEntity`, DAO, converters, database, and mappers) now backs `TranslationHistoryRepository`, with legacy JSON history auto-migrated into Room on first init.
- **Room schema tracking**
  - `core/build.gradle.kts` now exports schemas to `core/schemas`, keeping migrations diffable in version control.
- **UI navigation/back handling**
  - `TranscriptionFragment` and `TranslationHistoryFragment` route toolbar/home events through `MenuHost`, `OnBackPressedDispatcher`, and `NavController.navigateUp()` so no deprecated menu/back overrides remain.
- **Room observation flow**
  - `TranslationHistoryRepository` now collects `dao.observeHistory()` inside a dedicated IO scope to keep the in-memory cache and exposed flows synchronized with KSP-generated Room updates.
- **KSP usage elsewhere**
  - `app/build.gradle.kts` and other modules rely on the alias-based plugin ordering to keep Dagger KSP wiring intact under AGP 8.13.

## Follow-ups / Status by Checklist
- **ML Kit** – Endpoints now use the modern API surface and centralized model download. Keep an eye on coroutine flow adapters for potential simplification and ensure any new call sites route through `ModelManager.ensureLanguagePairAvailable`.
- **Activity Result API** – Existing UI already uses `registerForActivityResult`; no legacy `startActivityForResult` usages detected.
- **Lifecycle & Coroutines** – No `launchWhen*`/`GlobalScope` usages remain. Menu/back handling now uses `MenuHost` + `OnBackPressedDispatcher`; keep monitoring for new fragments that might reintroduce the deprecated paths.
- **Media3** – Codebase already on `androidx.media3.*`; no ExoPlayer v2 imports found.
- **Room + KSP** – DAO suspend/Flow signatures verified; repository mirrors Room emissions via `dao.observeHistory()`. Keep exporting schemas (`core/schemas`) and extend test coverage alongside future DAO changes.
- **TTS stubs** – Minimal implementations exist and compile; fuller feature build-out remains future work.
- **Navigation & WorkManager** – No API regressions observed during the build; keep these on the radar when the feature migration passes through those modules.
- **Build hygiene** – `./gradlew clean` and the targeted assemble tasks complete on AGP 8.13. Deprecated Android framework callbacks still emit warnings during compile and should be addressed separately.
