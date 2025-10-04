plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.gloabtranslate.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Keep :core headless (no UI libs)
    implementation(libs.androidx.core.ktx)

    // Kotlin Coroutines - use api() since core exposes coroutine types
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    
    // Kotlin Serialization for data persistence
    implementation(libs.kotlinx.serialization.json)
    
    // Lifecycle components for core functionality
    implementation(libs.lifecycle.runtime.ktx)
    
    // Security and encryption
    implementation(libs.security.crypto)
    
    // Room database for data persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    
    // Work Manager for background tasks
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit) // Updated to use alias
    testImplementation(libs.robolectric) // Added Robolectric
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
