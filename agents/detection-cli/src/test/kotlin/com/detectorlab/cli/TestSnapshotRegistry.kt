// agents/detection-cli/src/test/kotlin/com/detectorlab/cli/TestSnapshotRegistry.kt
//
// Power-18 D1 — TEST-ONLY snapshot registry. Extends MainSnapshotRegistry's
// 4 production fixtures with the 4 third-party-emulator fixtures that live
// in :detection's TEST sourceSet:
//
//   FridaInjectedRedroid   — RedroidV12 + frida-server injection
//   Nox                    — Nox vbox86 emulator
//   BlueStacks             — BlueStacks 5 emulator
//   Genymotion             — Genymotion x86 emulator
//
// This registry is COMPILE-VISIBLE only to :detection-cli's test sourceSet
// because the 4 test fixtures are exposed through the `testArtifacts`
// consumable configuration on :detection (declared in :detection's
// build.gradle.kts, consumed via `testImplementation` in :detection-cli's
// build.gradle.kts).
//
// The production binary of :detection-cli cannot link against this class —
// it lives in `src/test/kotlin/` and is excluded from the application
// distribution. The TEST-FIXTURE LEAK GUARD invariant from
// SnapshotRegistry.kt is therefore enforced structurally by Gradle: the
// production classpath simply has no path to FridaInjectedRedroidSnapshot et al.

package com.detectorlab.cli

import com.detectorlab.core.replay.BlueStacksSnapshot
import com.detectorlab.core.replay.DeviceSnapshot
import com.detectorlab.core.replay.FridaInjectedRedroidSnapshot
import com.detectorlab.core.replay.GenymotionSnapshot
import com.detectorlab.core.replay.NoxSnapshot
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot

/**
 * Test-only registry exposing the full 8-snapshot panel that
 * `CoverageMatrixGeneratorTest.allSnapshots()` uses. Column ordering matches
 * the audit matrix's frozen ordering — Pixel7Clean first, Genymotion last —
 * so JSON dumps from this CLI can be cross-referenced against
 * `audit/spoof-stack/full-coverage-matrix.md` row-by-row.
 */
object TestSnapshotRegistry : SnapshotRegistry {

    private val entries: List<Pair<String, DeviceSnapshot>> = listOf(
        // Main-set first (matches MainSnapshotRegistry's order).
        "Pixel7Clean" to Pixel7CleanSnapshot.SNAPSHOT,
        "SamsungS22Clean" to SamsungS22CleanSnapshot.SNAPSHOT,
        "RedroidV12" to RedroidV12Snapshot.SNAPSHOT,
        "RedroidSpoofed" to RedroidSpoofedSnapshot.SNAPSHOT,
        // Test-set extension (only resolvable from :detection-cli test classpath).
        "FridaInjectedRedroid" to FridaInjectedRedroidSnapshot.SNAPSHOT,
        "Nox" to NoxSnapshot.SNAPSHOT,
        "BlueStacks" to BlueStacksSnapshot.SNAPSHOT,
        "Genymotion" to GenymotionSnapshot.SNAPSHOT,
    )

    private val byLowerCaseName: Map<String, DeviceSnapshot> =
        entries.associate { (name, snap) -> name.lowercase() to snap }

    override fun names(): List<String> = entries.map { it.first }

    override fun find(name: String): DeviceSnapshot? =
        byLowerCaseName[name.lowercase()]
}
