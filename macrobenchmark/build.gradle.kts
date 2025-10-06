plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}


android {
    namespace = "com.example.gloabtranslate.macrobenchmark"
    compileSdk = 36

    defaultConfig {

        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Build config fields for benchmarking
        buildConfigField("String", "BENCHMARK_VERSION", "\"1.0.0\"")
        buildConfigField("boolean", "ENABLE_DETAILED_LOGGING", "true")
    }

    // Match the app module's flavor configuration
    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
        }
        create("staging") {
            dimension = "environment"
        }
        create("production") {
            dimension = "environment"
        }
    }

    buildTypes {
        // This benchmark buildType is used for benchmarking, and should function like your
        // release build (for example, with minification on). It"s signed with a debug key
        // for easy local/CI testing.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    
    // Additional dependencies for comprehensive benchmarking
    implementation(libs.test.runner)
    implementation(libs.test.rules)
    implementation(libs.androidx.junit)
    
    // Kotlin dependencies
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Core app dependencies for testing
    implementation(project(":core"))
    implementation(project(":speech"))
    implementation(project(":nlp"))
    implementation(project(":tts"))
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enable = variantBuilder.buildType == "benchmark"
    }
}
