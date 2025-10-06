plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.gloabtranslate.speech"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    jvmToolchain(17)
}

dependencies {
    // Module dependencies
    implementation(project(":core"))
    
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Lifecycle components
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    
    // Using Android Speech API instead of ML Kit
    
    // Media3 for audio playback and speech processing
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.14.6") // MockK for Kotlin testing
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit) // For kotlin.test assertions
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
