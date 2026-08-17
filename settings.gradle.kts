pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
