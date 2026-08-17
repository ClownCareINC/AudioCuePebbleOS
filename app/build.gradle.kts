plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clowncare.audiocuesbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clowncare.audiocuesbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // PebbleKit Android 2 - talks to the Pebble / Core mobile app over a bound service.
    // Check https://github.com/pebble-dev/PebbleKitAndroid2/releases for the newest version.
    // JitPack fallback coordinate: com.github.pebble-dev.PebbleKitAndroid2:client:1.2.0
    implementation("io.rebble.pebblekit2:client:1.2.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
