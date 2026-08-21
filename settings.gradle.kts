pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
plugins {
id("com.android.application") version "8.13.2"
id("org.jetbrains.kotlin.android") version "2.3.21"
}
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // PebbleKit Android 2 publishes to Maven Central. If resolution ever fails,
        // uncomment JitPack and use the coordinate noted in app/build.gradle.kts.
        // maven("https://jitpack.io")
    }
}

rootProject.name = "AudioCuesPebbleBridge"
include(":app")
