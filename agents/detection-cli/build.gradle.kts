// agents/detection-cli/build.gradle.kts — DetectorLab CLI runner module.
//
// Closes the deployer-loop: takes a YAML/JSON DeviceSnapshot dumped from a
// live container (via `docker exec getprop`, etc.), constructs a
// SnapshotReplayContext, runs the full 65-probe ProbeRunner against it, and
// writes the resulting Report JSON to stdout (or `--output <path>`).
//
// Acceptance commands:
//   gradle :detection-cli:test         → expect exit 0 (CLI integration)
//   gradle :detection-cli:installDist  → produces build/install/detection-cli/
//   gradle test                        → all modules pass (no regressions)

plugins {
    application
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.serialization") version "1.9.25"
}

group = "com.detectorlab"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

// kaml 0.61.0 transitively depends on kotlinx-serialization-core 1.7.1 which
// requires the Kotlin 2.0.0-RC1+ compiler. The repo is pinned to Kotlin 1.9.25
// (:detection module), so we force the kotlinx-serialization-* artifacts to
// the Kotlin-1.9-compatible 1.6.3 line. kaml 0.61.0's surface area against
// these libraries is stable and the downgrade is API-compatible — verified
// against `Yaml.decodeFromString` + `SerializersModule` usage in
// SnapshotLoader.kt. Filed as a deviation note in the task report.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3",
        )
    }
}

dependencies {
    implementation(project(":detection"))
    implementation("com.charleskorn.kaml:kaml:0.61.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Power-18 D1 — test-only access to :detection's TEST sourceSet outputs.
    // The 4 third-party-emulator snapshot fixtures (FridaInjectedRedroidSnapshot,
    // NoxSnapshot, BlueStacksSnapshot, GenymotionSnapshot) live in :detection's
    // test sourceSet (positive-path detection fixtures, not production code).
    // The :detection-cli replay-snapshot subcommand's TEST sourceSet needs
    // access to all 8 snapshots; the production CLI binary's MAIN sourceSet
    // does NOT (TEST-FIXTURE LEAK GUARD — see SnapshotRegistry.kt).
    //
    // :detection exposes its compiled test classes through a custom
    // `testArtifacts` consumable configuration (added in :detection's
    // build.gradle.kts as part of this same Power-18 D1 change). We resolve
    // it here only on the `testImplementation` (and `testRuntimeOnly`) edges,
    // never on `implementation` — ensuring production classpath isolation.
    testImplementation(project(path = ":detection", configuration = "testArtifacts"))
    testRuntimeOnly(project(path = ":detection", configuration = "testArtifacts"))
}

application {
    mainClass.set("com.detectorlab.cli.MainKt")
    applicationName = "detection-cli"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
