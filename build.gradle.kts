// PebbleKit Android 2 (1.2.0) is published with Kotlin 2.3 metadata, so the Kotlin
// plugin here must be 2.3.x or newer. Older versions fail with
// "module was compiled with an incompatible version of Kotlin".
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
