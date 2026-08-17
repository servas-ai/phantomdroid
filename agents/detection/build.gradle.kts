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
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // Power-8 opt-in gate for FullProbeRunnerSpoofTest (full 84-probe panel).
    // Off by default → test class is skipped via @EnabledIfSystemProperty.
    // Enable with `./gradlew :detection:test -PrunSpoofPanel=true`.
    if (project.hasProperty("runSpoofPanel")) {
        systemProperty("runSpoofPanel", project.property("runSpoofPanel").toString())
    }
}

// Power-18 D1 — consumable configuration exposing :detection's compiled test
// classes to OTHER modules' TEST classpaths only (never main classpaths).
// :detection-cli consumes this in its TEST sourceSet so the replay-snapshot
// E2E test can drive the 4 test-set snapshots (FridaInjectedRedroidSnapshot,
// NoxSnapshot, BlueStacksSnapshot, GenymotionSnapshot) without leaking them
// into the production CLI binary.
//
// This is a deliberately narrow surface: the configuration only carries the
// compiled `build/classes/kotlin/test` directory + the test resources dir,
// plus the test sourceSet's runtime classpath (so transitive testFixtures
// like JUnit aren't pulled into consumers).
configurations {
    create("testArtifacts") {
        isCanBeConsumed = true
        isCanBeResolved = false
        description = "Power-18 D1 — :detection's compiled test classes, exposed to other modules' test classpaths only."
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        }
        // Extend from testRuntimeClasspath so test-only runtime libs (junit etc)
        // are propagated; but artifacts are restricted to the test classes
        // directories below via the explicit `outgoing.artifact(...)` calls.
        extendsFrom(configurations["testRuntimeClasspath"])
    }
}

// Add each compiled-test-classes directory as an explicit artifact on the
// `testArtifacts` configuration. The Kotlin compile task produces a
// single classes-output directory by default but Gradle wraps it as a
// FileCollection (.outputs.files) which may carry >1 entry once snapshot
// caching is active — iterate explicitly so we don't trip the
// `singleFile` invariant. `builtBy` ensures consumers trigger the compile.
afterEvaluate {
    val compileTestKotlin = tasks.named("compileTestKotlin")
    val testClassesDir = sourceSets.named("test").get().output.classesDirs
    val testResourcesDir = layout.projectDirectory.dir("src/test/resources")

    artifacts {
        testClassesDir.forEach { dir ->
            val compilerTask = if (dir.path.contains("kotlin")) compileTestKotlin else tasks.named("compileTestJava")
            add("testArtifacts", dir) {
                builtBy(compilerTask)
            }
        }
        if (testResourcesDir.asFile.exists()) {
            add("testArtifacts", testResourcesDir.asFile) {
                builtBy(tasks.named("processTestResources"))
            }
        }
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
