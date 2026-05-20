// agents/detection/src/core/replay/DeviceSnapshot.kt
//
// Closure of the E2E "real-simulator" goal: the detection module is a JVM
// library that needs to run *against* probe-relevant data captured from a
// live Android (or Android-like) runtime. The simulator we have (ReDroid 12
// on PAR822349) does not boot to a fully usable system_server on kernel 4.15
// (binderfs gap), but the probe-relevant property surface IS reachable via
// `docker exec` directly. This file is the data-side of that bridge:
//
//   live container --(docker exec getprop/ls/cat)--> DeviceSnapshot.yaml
//                                                          |
//                                                          v
//                                                 SnapshotReplayContext
//                                                          |
//                                                          v
//                                                  ProbeRunner.runAll()
//
// Snapshots are owner-readable plain data — no live network, no live device
// access required to replay them. The recorded `RedroidV12Snapshot.kt`
// peer file holds the actual ReDroid 12 capture from 2026-05-20.

package com.detectorlab.core.replay

/**
 * Captured probe-relevant device state from a single Android runtime at a
 * single point in time. Field set chosen to cover the probe surface that
 * the existing 62-probe inventory actually reads from `ProbeContext`.
 *
 * Field semantics mirror the corresponding `ProbeContext` accessor:
 *
 *   * `systemProperties` — `getSystemProperty(key)` lookup. Map of
 *     `getprop` key to value. Missing keys map to `null` at the
 *     accessor level (NOT `""`). Empty-string values are preserved
 *     verbatim because some probes distinguish "set-but-empty" from
 *     "not-set".
 *   * `existingFiles` — `fileExists(path)` lookup. Path is treated as
 *     present iff and only if it appears in this set. Order does not
 *     matter; comparison is exact-string match (no globbing).
 *   * `readableFiles` — `readFile(path)` lookup. Used together with
 *     `existingFiles`: a file may exist but not be readable from a
 *     non-root context. Missing key → accessor returns null.
 *   * `settingsSecure` / `settingsGlobal` / `settingsSystem` —
 *     `Settings.Secure` / `Settings.Global` / `Settings.System` namespace
 *     reads. Same null-vs-empty semantics as `systemProperties`.
 *   * `telephony` — `TelephonyManager` field reads. Field-typed enum
 *     map.
 *   * `installedPackages` — `PackageManager.getInstalledPackages()`
 *     output as a flat package-name set.
 *   * `sdkInt` — `Build.VERSION.SDK_INT` claimed by the runtime.
 *
 * `null`-valued entries are equivalent to missing entries — the same
 * "key not in map" branch. Authors should prefer omitting keys to
 * setting explicit `null`.
 */
data class DeviceSnapshot(
    val label: String,
    val capturedAt: String,
    val sdkInt: Int = 0,
    val systemProperties: Map<String, String?> = emptyMap(),
    val existingFiles: Set<String> = emptySet(),
    val readableFiles: Map<String, String> = emptyMap(),
    val settingsSecure: Map<String, String?> = emptyMap(),
    val settingsGlobal: Map<String, String?> = emptyMap(),
    val settingsSystem: Map<String, String?> = emptyMap(),
    val installedPackages: Set<String> = emptySet(),
    val telephony: Map<String, String?> = emptyMap(),
)
