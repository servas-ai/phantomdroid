// agents/detection/build.gradle.kts — DetectorLab probe module
//
// Filed under CLO-115: brings agents/detection/ under a compilable Gradle module
// so its probes (BuildFingerprintProbe et al.) actually build and the existing
// JUnit-style tests under src/test/kotlin/com/detectorlab/ are executed.
//
// Source layout (non-standard, preserved to avoid churn — see CLO-115 §note):
//   src/core/           ← compiled into main
//   src/probes/<cat>/   ← compiled into main
//   src/test/kotlin/    ← standard Gradle test layout, picked up automatically
//
// Acceptance command (per CLO-115):
//   gradle :detection:test           → expect exit 0
//   gradle :detection:dependencies   → expect no com.example.* references

plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.detectorlab"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

// Custom source set: the probe and core code lives at src/core/ and
// src/probes/, not at the Gradle default src/main/kotlin/. Map them
// explicitly so the Kotlin compiler picks them up. Tests already live at
// src/test/kotlin/com/detectorlab/ (the Gradle default) and need no override.
sourceSets {
    named("main") {
        kotlin.srcDirs("src/core", "src/probes")
        resources.srcDirs("src/assets")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Sanity check that runs as part of `check`: assert the legacy namespace
// prefix has not crept back into the source tree. CLO-115 acceptance
// criterion (c). The forbidden literal is split here so this build file
// itself does not show up in a grep audit of the legacy string.
tasks.register("checkNamespace") {
    group = "verification"
    description = "Fail the build if any legacy 'example' detectorlab prefix reference resurfaces."
    doLast {
        val forbidden = listOf("com", "example", "detectorlab").joinToString(".")
        val offenders = fileTree(projectDir) {
            include("src/**/*.kt", "src/**/*.md")
        }.files.filter { file ->
            file.readText().contains(forbidden)
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Found legacy namespace '$forbidden' references in:\n  - " +
                    offenders.joinToString("\n  - ") { it.relativeTo(projectDir).path }
            )
        }
    }
}
tasks.named("check") { dependsOn("checkNamespace") }
