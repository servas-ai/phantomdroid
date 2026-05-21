// agents/detection-cli/src/main/kotlin/com/detectorlab/cli/SnapshotRegistry.kt
//
// Power-18 D1 — production-binary snapshot registry for the `replay-snapshot`
// subcommand. Holds ONLY the 4 main-sourceSet snapshots that the :detection
// module compiles into its main classpath:
//
//   Pixel7Clean       — real-device baseline (Pixel 7, Android 13)
//   SamsungS22Clean   — real-device baseline (Samsung S22, Android 13)
//   RedroidV12        — unspoofed ReDroid 12 emulator
//   RedroidSpoofed    — Iter-1 spoof-stack applied to RedroidV12
//
// ─────────────────────────────────────────────────────────────────────────────
// TEST-FIXTURE LEAK GUARD (Power-15 reviewer invariant)
// ─────────────────────────────────────────────────────────────────────────────
// The other 4 fixtures used by the 656-cell coverage matrix (FridaInjected,
// Nox, BlueStacks, Genymotion) live in `:detection`'s TEST sourceSet, NOT its
// main sourceSet. They are deliberately quarantined there because they were
// authored as positive-path detection fixtures and are NOT part of the
// production probe-runner contract — shipping them in the production CLI
// binary would leak detector test fixtures into the deployable artifact.
//
// The production `detection-cli` binary therefore exposes ONLY the 4 main-set
// snapshots through `MainSnapshotRegistry`. The 4 test-set snapshots are
// exposed through `TestSnapshotRegistry` in :detection-cli's TEST sourceSet
// (which has a `testImplementation` dependency on :detection's test outputs).
// Production callers requesting a test-set snapshot name (case-insensitive)
// receive `SnapshotNotFound` with `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES`
// in the message — explicit, loud, and easy to grep.
//
// `replay-snapshot <name>` resolution order:
//   1. case-insensitive lookup against the active registry's `names()`
//   2. miss → if name matches a known test-set fixture → throw with the
//      `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` token
//   3. miss + not test-set → throw with "unknown snapshot" + name list

package com.detectorlab.cli

import com.detectorlab.core.replay.DeviceSnapshot
import com.detectorlab.core.replay.Pixel7CleanSnapshot
import com.detectorlab.core.replay.RedroidSpoofedSnapshot
import com.detectorlab.core.replay.RedroidV12Snapshot
import com.detectorlab.core.replay.SamsungS22CleanSnapshot

/**
 * A pluggable registry of snapshot fixtures for the `replay-snapshot`
 * subcommand. The production binary wires `MainSnapshotRegistry` (4
 * snapshots); :detection-cli's test sourceSet wires `TestSnapshotRegistry`
 * (8 snapshots) via the cross-project `testImplementation` dep on
 * :detection's test outputs.
 */
interface SnapshotRegistry {

    /** Canonical names in display order. Case is the canonical casing. */
    fun names(): List<String>

    /**
     * Look up a snapshot by name. Matching is case-insensitive — both
     * "Pixel7Clean" and "pixel7clean" must resolve to the same fixture.
     * Returns `null` if no fixture matches; the caller decides whether to
     * throw "unknown snapshot" or the test-fixture-leak guard message.
     */
    fun find(name: String): DeviceSnapshot?
}

/**
 * Names of the 4 fixtures that live in :detection's TEST sourceSet. The
 * production CLI cannot reach them — they are mentioned here ONLY so the
 * `replay-snapshot` subcommand can detect a test-set-name request from a
 * production binary and emit the precise
 * `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` error rather than the
 * generic "unknown snapshot" message.
 *
 * Keep this set in sync with `TestSnapshotRegistry.names()` in the test
 * sourceSet — drift here causes a misleading error message but no
 * functional break (the production binary still refuses correctly).
 */
internal val TEST_SOURCE_SET_SNAPSHOT_NAMES: Set<String> = setOf(
    "fridainjectedredroid",
    "nox",
    "bluestacks",
    "genymotion",
)

/**
 * Production-binary registry. Exposes the 4 snapshots that live in
 * :detection's main sourceSet (i.e. compile-time visible to the deployed
 * `detection-cli` jar).
 */
object MainSnapshotRegistry : SnapshotRegistry {

    private val entries: List<Pair<String, DeviceSnapshot>> = listOf(
        "Pixel7Clean" to Pixel7CleanSnapshot.SNAPSHOT,
        "SamsungS22Clean" to SamsungS22CleanSnapshot.SNAPSHOT,
        "RedroidV12" to RedroidV12Snapshot.SNAPSHOT,
        "RedroidSpoofed" to RedroidSpoofedSnapshot.SNAPSHOT,
    )

    private val byLowerCaseName: Map<String, DeviceSnapshot> =
        entries.associate { (name, snap) -> name.lowercase() to snap }

    override fun names(): List<String> = entries.map { it.first }

    override fun find(name: String): DeviceSnapshot? =
        byLowerCaseName[name.lowercase()]
}

/**
 * Looking up a snapshot by name failed. Two distinct reasons:
 *
 *  - [UnknownName]: the name doesn't match anything we know about.
 *  - [TestFixtureLeakGuard]: the name matches a fixture that lives in
 *    :detection's TEST sourceSet. The production CLI binary cannot
 *    instantiate it — by design — and reports this as a deliberate
 *    refusal rather than a name typo.
 */
sealed class SnapshotNotFound(message: String) : IllegalArgumentException(message) {

    class UnknownName(name: String, available: List<String>) : SnapshotNotFound(
        "unknown snapshot '$name'. Available: ${available.joinToString(", ")}",
    )

    class TestFixtureLeakGuard(name: String) : SnapshotNotFound(
        "PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES: snapshot '$name' lives in " +
            ":detection's test sourceSet (positive-path detection fixture). " +
            "Production binaries deliberately do not ship test fixtures. " +
            "Use the test CLI (`:detection-cli:test`) for full 8-snapshot coverage.",
    )
}

/**
 * Top-level resolution helper. Looks up `name` in `registry`. If absent
 * AND the name matches a known test-set snapshot, throws
 * [SnapshotNotFound.TestFixtureLeakGuard]; otherwise throws
 * [SnapshotNotFound.UnknownName].
 *
 * Returns a Pair of (canonical-name, snapshot) so callers can echo back
 * the canonical casing in the JSON output.
 */
fun resolveSnapshot(registry: SnapshotRegistry, name: String): Pair<String, DeviceSnapshot> {
    val lower = name.lowercase()
    val snap = registry.find(name)
    if (snap != null) {
        // Echo the canonical casing from the registry's names() list.
        val canonical = registry.names().firstOrNull { it.lowercase() == lower } ?: name
        return canonical to snap
    }
    if (lower in TEST_SOURCE_SET_SNAPSHOT_NAMES &&
        registry.names().none { it.lowercase() == lower }
    ) {
        throw SnapshotNotFound.TestFixtureLeakGuard(name)
    }
    throw SnapshotNotFound.UnknownName(name, registry.names())
}
