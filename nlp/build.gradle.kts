plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.gloabtranslate.nlp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
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
}

kotlin {
    jvmToolchain(21)
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
    
    // ML Kit for translation and language identification
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    
    // Play Services for Google API availability
    implementation(libs.play.services.tflite.java)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk) // MockK for Kotlin testing
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit) // For kotlin.test assertions
    testImplementation(libs.robolectric) // For Android unit testing
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
