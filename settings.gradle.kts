pluginManagement {
    repositories {
        maven { url = uri("https://maven.google.com") }
        mavenCentral()
        gradlePluginPortal()
    }
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
