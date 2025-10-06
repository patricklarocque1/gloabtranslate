plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.gloabtranslate.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        targetSdk = libs.versions.targetSdk.get().toInt()
    }

    lint {
        targetSdk = libs.versions.targetSdk.get().toInt()
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

    // Dagger for dependency injection (@Inject, @Singleton usage in core helpers like DebugLogger)
    implementation(libs.dagger)

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

    // Unit test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.hamcrest)
    
    // Android test dependencies
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.truth)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
