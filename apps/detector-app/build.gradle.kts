// apps/detector-app/build.gradle.kts — DetectorLab on-device runner (APK).
//
// Closes the in-container attestation gap recorded in
// audit/apk-in-container-2026-05-29.md: there was NO installable
// `com.android.application` module that runs the JVM probe inventory
// IN-PROCESS inside Android, so in-process-only signals
// (TelephonyManager IMEI, WifiManager MAC, ContentResolver settings,
// app-process TracerPid, Play Integrity) could not be measured on-device.
//
// This module hosts the SAME `:detection` probe library behind an
// `AndroidProbeContext : ProbeContext` backed by real Android framework
// APIs (Build.*, Settings.*, TelephonyManager, WifiManager, /proc reads).
// It emits the identical schemaVersion 1.0 JSON report
// (shared/probe-schema.md) as `:detection-cli`, but the inputs are read
// live from the booted Android runtime instead of a frozen DeviceSnapshot.
//
// Plugin / SDK conventions match the repo's existing Android modules:
//   - AGP 8.1.1            (infrastructure/spoof-stack-lsposed uses 8.1.1)
//   - compileSdk 34        (stack/L4/hide-frida-maps uses 34)
//   - minSdk 28            (stack/L4/hide-frida-maps; ReDroid 12 = API 31)
//   - JDK 17 toolchain     (all modules)
//   - Kotlin 1.9.25        (lockstep with :detection / :detection-cli)
//
// Acceptance command:
//   ./gradlew :detector-app:assembleDebug    → expect BUILD SUCCESSFUL
//   ./gradlew :detector-app:testDebugUnitTest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.detectorlab.detectorapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.detectorlab.detectorapp"
        minSdk = 28          // Android 9+; ReDroid 12 container target is API 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The headless instrumented runner uses AndroidJUnitRunner so the
        // probe pipeline can be driven by `adb shell am instrument` without a
        // foreground Activity (apk-deliver verified the install/launch/pull path).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The probe core lives in :detection; the report serializer in this
    // module pulls in kotlinx-serialization-json. No AndroidX Compose, no
    // resource-heavy surfaces — the UI is a single status Activity.
    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    // The unified DetectorLab probe library + ProbeContext interface.
    implementation(project(":detection"))

    // Coroutines: ProbeRunner.runAll() is a suspend fun.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Report serialization — mirrors :detection-cli's ReportSerializer
    // (manual Report → JsonElement mapping; :detection stays serialization-free).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Minimal AndroidX surface for the status Activity + lifecycle scope.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Local (JVM) unit tests — schema-shape assertions against the registry.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("junit:junit:4.13.2")

    // Instrumented (on-device) test that runs the real AndroidProbeContext.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
