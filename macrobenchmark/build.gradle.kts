plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}


android {
    namespace = "com.example.gloabtranslate.macrobenchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Build config fields for benchmarking
        buildConfigField("String", "BENCHMARK_VERSION", "\"1.0.0\"")
        buildConfigField("boolean", "ENABLE_DETAILED_LOGGING", "true")
    }

    // Add flavor dimensions to match the app module
    flavorDimensions += "environment"
    productFlavors {
        create("development") {
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
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test:rules:1.5.0")
    implementation("androidx.test.ext:junit:1.1.5")
    
    // Memory profiling
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.2")
    
    // Kotlin dependencies
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Core app dependencies for testing
    implementation(project(":core"))
    implementation(project(":speech"))
    implementation(project(":nlp"))
    implementation(project(":tts"))
    
    // App dependency for benchmarking - specify development flavor
    implementation(project(":app", configuration = "developmentBenchmarkRuntimeElements"))
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark" && it.flavorName == "development"
    }
    
    onVariants(selector().all()) { variant ->
        variant.instrumentationRunnerArguments.put("targetApp", "com.example.gloabtranslate.dev")
    }
}
