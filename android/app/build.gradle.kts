plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.blackorange.mdm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.blackorange.mdm"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The Rust core is built for these (CI: cargo ndk).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            // Off until the keep rules for the TLS verifier are tested on a release build.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// The Kotlin part must match the Rust crate: the version of "rustls-platform-verifier-android"
// that Cargo locked (run `cargo fetch` first).
val rustlsAndroidVersion: String = Regex("""name = "rustls-platform-verifier-android"\s+version = "([^"]+)"""")
    .find(rootProject.file("../Cargo.lock").readText())?.groupValues?.get(1)
    ?: error("rustls-platform-verifier-android is not in Cargo.lock (run `cargo fetch`)")

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // The Kotlin half of rustls-platform-verifier (see settings.gradle.kts).
    implementation("rustls:rustls-platform-verifier:$rustlsAndroidVersion")
}
