// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}


// Repository configuration moved to settings.gradle.kts

// Global configuration removed - each module handles its own configuration

// Task to clean all build directories
tasks.register("cleanAll") {
    group = "build"
    description = "Clean all build directories"
    dependsOn(":app:clean", ":core:clean", ":speech:clean", ":nlp:clean", ":tts:clean")
}

// Task to build all modules
tasks.register("buildAll") {
    group = "build"
    description = "Build all modules"
    dependsOn(":app:assembleDebug", ":core:assemble", ":speech:assemble", ":nlp:assemble", ":tts:assemble")
}

// Task to run all tests
tasks.register("testAll") {
    group = "verification"
    description = "Run all tests"
    dependsOn(":app:testDebugUnitTest", ":core:testDebugUnitTest", ":speech:testDebugUnitTest", ":nlp:testDebugUnitTest", ":tts:testDebugUnitTest")
}
