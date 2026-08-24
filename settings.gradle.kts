pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kithmoot-android"

// :protocol is a pure Kotlin/JVM module with no Android dependencies, so the
// interop vector suite runs as a plain JVM unit test. The Android app module
// lands alongside it once WebRTC media work starts.
include(":protocol")
