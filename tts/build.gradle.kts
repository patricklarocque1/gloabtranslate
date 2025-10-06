plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.gloabtranslate.tts"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        targetSdk = libs.versions.targetSdk.get().toInt()
    }

    lint {
        targetSdk = libs.versions.targetSdk.get().toInt()
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
    
    // Media3 for text-to-speech audio playback
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk) // MockK for Kotlin testing
    testImplementation(libs.robolectric) // Robolectric for Android unit tests
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit) // For kotlin.test assertions
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
