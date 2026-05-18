# Track D — Detection Probes & Tests

**Date**: 2026-05-19
**Branch**: `report/CLO-143-weekly-W20`
**Module**: `:detection` (mapped to `agents/detection/`)
**Outcome**: GREEN — all 186 tests pass across 10 probe test classes.

---

## 1. CLO-19 TikTokArgusSigningProbe — Package Relocation Verified

| Check | Result |
|---|---|
| Old file `agents/detection/src/test/kotlin/com/example/detectorlab/probes/app/TikTokArgusSigningProbeTest.kt` | Deleted (path no longer exists on disk) |
| New file `agents/detection/src/test/kotlin/com/detectorlab/probes/app/TikTokArgusSigningProbeTest.kt` | Present (untracked, ready for commit by Track B) |
| `package com.detectorlab.probes.app` declaration (test) | Correct (line 1) |
| Probe package `com.detectorlab.probes.app` (production) | Matches (line 1 of `TikTokArgusSigningProbe.kt`) |
| All imports rewritten `com.example.detectorlab.*` → `com.detectorlab.*` | Verified via diff (no `com.example` left in `src/`) |
| Stale `com.example.detectorlab` references | Only in `agents/detection/SKELETON.md` (doc, not code — out of scope for Track D) |
| Test class executes against new probe | 15/15 PASSED (see §2) |

Note: `agents/detection/build.gradle.kts` has a `checkNamespace` guard task that fails the build if `com.example.detectorlab` reappears in `src/**`. The current tree passes that guard.

---

## 2. Gradle Test Run

Command: `./gradlew :detection:test --no-daemon --console=plain --rerun-tasks`

(Note: task path is `:detection`, not `:agents:detection` — settings.gradle.kts remaps `agents/detection/` to `:detection`.)

| Test class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `TikTokArgusSigningProbeTest` (CLO-19) | 15 | 0 | 0 | 0 |
| `IgFamilyDeviceIdHeaderProbeTest` (CLO-96) | 21 | 0 | 0 | 0 |
| `ScreenRecordingProbeTest` (CLO-114) | 23 | 0 | 0 | 0 |
| `LocationMockProbeTest` (CLO-129) | 10 | 0 | 0 | 0 |
| `TimeSpoofingProbeTest` (CLO-113) | 22 | 0 | 0 | 0 |
| `ScreenLockProbeTest` | 11 | 0 | 0 | 0 |
| `WifiSecurityTypeProbeTest` | 17 | 0 | 0 | 0 |
| `CpuInfoProbeTest` | 19 | 0 | 0 | 0 |
| `AutomationToolsProbeTest` | 19 | 0 | 0 | 0 |
| `MultiInstanceProbeTest` | 29 | 0 | 0 | 0 |
| **TOTAL** | **186** | **0** | **0** | **0** |

`BUILD SUCCESSFUL in 23s` (exit 0).

---

## 3. Diff Review — 3 Modified Files

### `agents/detection/src/probes/app/IgFamilyDeviceIdHeaderProbe.kt` (CLO-96)

Adds a `looksLikeJsonObject(json)` pre-flight guard at the top of `parseCaptureFile()`. Implementation is a hand-rolled brace/bracket-depth scanner with quote and escape handling. Returns false for inputs that do not look like a balanced JSON object before invoking the regex-based field extractors. Existing test "parseCaptureFile returns null for malformed JSON" still passes — the new guard short-circuits earlier without changing observable behavior. Sane defensive hardening; no new test required because the regex path was already null-safe and now the function fails fast.

### `agents/detection/src/probes/app/TikTokArgusSigningProbe.kt` (CLO-19)

Pure package rename: 7 import lines and the `package` declaration changed from `com.example.detectorlab.*` to `com.detectorlab.*`. Body unchanged. Required to align with CLO-115 namespace normalisation. Companion test was already relocated to the matching new path.

### `agents/detection/src/test/kotlin/com/detectorlab/probes/runtime/ScreenRecordingProbeTest.kt` (CLO-114)

One-line rename of a test method (`>= 35` → `is at least 35`). Backticked Kotlin identifiers do not accept `>=` reliably under all JUnit reporters — this is a benign rename for tooling compatibility. Test body unchanged; assertions still cover the same SDK boundary behavior.

---

## 4. Probe Inventory Discrepancy

`agents/detection/README.md` line 49 still says `# TODO: 74 more probes`. Actual probe-file count under `src/probes/`:

- 11 probe `.kt` files (BuildFingerprint, IgFamilyDeviceIdHeader, TikTokArgusSigning, LocationMock, ScreenLock, TimeSpoofing, WifiSecurityType, CpuInfo, AutomationTools, MultiInstance, ScreenRecording)
- Inventory target: 75 probes total
- Remaining: **75 − 11 = 64 probes TODO**

Suggested wording for Track B/E:
```
# TODO: 64 more probes (root, integrity, identity, sensors, network, etc.)
```

Not editing per Track D constraint — leaving for Track B/E as instructed.

---

## 5. Findings / Recommendations

1. **CLO-19 relocation is complete and green.** Safe for Track B to commit.
2. **CLO-96 guard is non-breaking.** No new test needed but Track B could add a single-line case `assertNull(parseCaptureFile("not even close"))` to lock in the new fast-path; optional.
3. **No probe code required edits** during this run.
4. **CLO-115 namespace audit clean.** The build's `checkNamespace` guard would have failed if any `com.example.detectorlab` survived under `src/`; it didn't.
5. **Gradle task path mismatch in plan.** The 8h plan references `:agents:detection:test`; the actual Gradle ID is `:detection:test`. Worth noting in any CI / wrapper script that downstream tracks reuse.

No git operations performed. No source files modified.
