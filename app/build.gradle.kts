plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clowncare.audiocuesbridge"

    // Must be 36 or newer: PebbleKit 2 pulls in androidx.core 1.17.0, which refuses
    // to be compiled against anything older.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clowncare.audiocuesbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        debug {
            // No applicationIdSuffix on purpose. The package must stay exactly
            // com.clowncare.audiocuesbridge so it matches the watchapp's companionApp list.
            isMinifyEnabled = false
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
