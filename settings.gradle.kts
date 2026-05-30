// settings.gradle.kts — Cloud Phone Research Planner
//
// Root Gradle settings. Declares the canonical detection module under
// agents/detection/. The historical W3 detector-lab scaffold
// (docs/super-action/W3/detector-lab/, com.detectorlab.*) is intentionally
// NOT registered: per CLO-115 the agents/detection tree is the unified home
// for all DetectorLab probes and the namespace has been normalised to
// com.detectorlab.* across all 22 .kt files.
//
// To add a new agent module later:
//   include(":<agent-name>")
//   project(":<agent-name>").projectDir = file("agents/<agent-name>")

// pluginManagement is required so the Android Gradle Plugin
// (com.android.application) and the Kotlin Android plugin resolve from
// google() / mavenCentral(). The JVM-only modules (:detection,
// :detection-cli) declare their plugin versions inline and are unaffected by
// this block. AGP 8.1.1 matches infrastructure/spoof-stack-lsposed; Kotlin
// 1.9.25 is the repo-wide pin (see agents/detection/build.gradle.kts).
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.1.1"
        id("org.jetbrains.kotlin.android") version "1.9.25"
    }
}

// dependencyResolutionManagement: detector-app pulls AndroidX + AGP artifacts
// from google(). PREFER_PROJECT lets the inline `repositories {}` blocks in the
// existing JVM modules keep working without being flagged as a conflict.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cloud-phone-research-planner"

include(":detection")
project(":detection").projectDir = file("agents/detection")

include(":detection-cli")
project(":detection-cli").projectDir = file("agents/detection-cli")

// :detector-app — com.android.application that hosts the :detection probe
// inventory in-process inside Android via AndroidProbeContext, closing the
// in-container attestation gap (audit/apk-in-container-2026-05-29.md).
include(":detector-app")
project(":detector-app").projectDir = file("apps/detector-app")
