pluginManagement {
    repositories {
        maven { url = uri("https://maven.google.com") }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.google.com") }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "gloabtranslate"
include(":app")
include(":core")
include(":speech")
include(":nlp")
include(":tts")
include(":macrobenchmark")
include(":mylibrary")
