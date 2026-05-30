# Proof 4 — CLI Fixture Classification (E2E Verification Slice 4)

**Date:** 2026-05-30
**Scope:** Prove the 65-probe detector classifies all 8 named in-source snapshot
fixtures correctly: clean devices score CLEAN, emulators DETECTED, and the
Iter-1 spoof (RedroidSpoofed) collapses to weightedScore 0.0000 / CLEAN.
**Binary:** `agents/detection-cli/build/install/detection-cli/bin/detection-cli`
(already built; no gradle build performed for the production binary).

---

## Classification gate (source of truth)

`anyDetected` — the verdict that drives the exit code — is **not** the 65-probe
`weightedScore`. It is the six-detector composite OR-union
(`CompositeDetector.anyDetectorFires`), per
`agents/detection-cli/src/main/kotlin/com/detectorlab/cli/ReplaySnapshotCommand.kt`.
The `weightedScore` is reported for diagnostic value only and intentionally
undershoots on third-party emulators (it is a centroid statistic). Exit-code
contract:

| Exit | Meaning |
|------|---------|
| 0 | CLEAN — `aggregate.anyDetected == false` |
| 1 | DETECTED — `aggregate.anyDetected == true` |

Category mapping used in the table below:
- **CLEAN** = `anyDetected == false`, exit 0
- **DETECTED** = `anyDetected == true`, exit 1

---

## Two registries (Power-15 / Power-18 D1 leak guard)

The production binary only ships the 4 main-sourceSet fixtures. The other 4
vendor-emulator fixtures live in `:detection`'s **test** sourceSet and are
deliberately quarantined out of the deployable artifact
(`agents/detection-cli/src/main/kotlin/.../SnapshotRegistry.kt`).

| Registry | Fixtures | Reachable via |
|----------|----------|---------------|
| `MainSnapshotRegistry` (production) | Pixel7Clean, SamsungS22Clean, RedroidV12, RedroidSpoofed | the shipped `detection-cli` binary |
| `TestSnapshotRegistry` (test) | the above 4 + FridaInjectedRedroid, Nox, BlueStacks, Genymotion | JVM-embedded replay path (`runReplaySnapshot(TestSnapshotRegistry, …)`) |

Requesting a test-set fixture from the production binary is refused **by
design** with the `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` marker (see
"Test-fixture leak guard" section below). To exercise the test-set 4 against the
detector I therefore drove the **same** `runReplaySnapshot` entry point used by
`ReplaySnapshotCliTest`, against the already-compiled test classes (no gradle
build — reused `build/classes/kotlin/test` + the installed dist `lib/`).

The 4 production fixtures were verified through **both** paths and the numbers
match bit-for-bit (cross-validates the embedded harness).

---

## Results — all 8 fixtures

| Fixture | weightedScore | criticalFailures | anyDetected | Category | Exit | Expected | PASS/FAIL |
|---------|--------------:|-----------------:|-------------|----------|-----:|----------|-----------|
| Pixel7Clean          | 0.1171 | 0 | false | CLEAN    | 0 | CLEAN    | **PASS** |
| SamsungS22Clean      | 0.0835 | 0 | false | CLEAN    | 0 | CLEAN    | **PASS** |
| RedroidV12           | 0.3697 | 4 | true  | DETECTED | 1 | DETECTED | **PASS** |
| RedroidSpoofed       | 0.0000 | 0 | false | CLEAN    | 0 | CLEAN (~0.0) | **PASS** |
| FridaInjectedRedroid | 0.3697 | 4 | true  | DETECTED | 1 | DETECTED | **PASS** |
| Nox                  | 0.1771 | 1 | true  | DETECTED | 1 | DETECTED | **PASS** |
| Genymotion           | 0.1771 | 1 | true  | DETECTED | 1 | DETECTED | **PASS** |
| BlueStacks           | 0.1418 | 0 | true  | DETECTED | 1 | DETECTED | **PASS** |

**8 / 8 PASS.** Clean real devices score CLEAN; every emulator and the
Frida-injected variant score DETECTED; RedroidSpoofed collapses to exactly
0.0000 / CLEAN as required.

Note on BlueStacks: `weightedScore=0.1418` with `criticalFailures=0`, yet
`anyDetected=true`. This is the gate working as designed — the composite
EmulatorDetector fires on a BlueStacks artifact even though no single probe
crosses the "critical" threshold and the centroid weightedScore stays low. The
exit-code gate is the composite OR-union, not the centroid.

---

## Raw output — production binary (4 main-set fixtures)

`detection-cli replay-snapshot <NAME>` (trailing summary line + real exit code):

```
detection-cli replay-snapshot: Pixel7Clean | deviceLabel=pixel-7-panther-clean-2026-05-20 | weightedScore=0.1171 | criticalFailures=0 | anyDetected=false | exit=0
detection-cli replay-snapshot: SamsungS22Clean | deviceLabel=samsung-s22-sm-s901b-2026-05-20 | weightedScore=0.0835 | criticalFailures=0 | anyDetected=false | exit=0
detection-cli replay-snapshot: RedroidV12 | deviceLabel=redroid-12-amd64-2026-05-20 | weightedScore=0.3697 | criticalFailures=4 | anyDetected=true | exit=1
detection-cli replay-snapshot: RedroidSpoofed | deviceLabel=redroid-12-amd64-2026-05-20-spoofed-v1 | weightedScore=0.0000 | criticalFailures=0 | anyDetected=false | exit=0
```

Real process exit codes captured separately:

```
Pixel7Clean    -> production-binary exit=0
SamsungS22Clean-> production-binary exit=0
RedroidV12     -> production-binary exit=1
RedroidSpoofed -> production-binary exit=0
```

Each invocation also emits the full deterministic JSON dump (65 probes +
`aggregate` block). Example tail for Pixel7Clean:

```json
  "aggregate": {
    "weightedScore": 0.1171,
    "criticalFailures": 0,
    "anyDetected": false,
    "totalProbes": 65
  }
```

---

## Raw output — JVM-embedded replay path (all 8 via TestSnapshotRegistry)

Driven through the same `runReplaySnapshot(...)` function the CLI's `main()`
uses, against the already-compiled `build/classes/kotlin/test` classes
(`appVersion="0.1.0"`):

```
FIXTURE=Pixel7Clean | deviceLabel=pixel-7-panther-clean-2026-05-20 | weightedScore=0.1171 | criticalFailures=0 | anyDetected=false | exitCode=0
FIXTURE=SamsungS22Clean | deviceLabel=samsung-s22-sm-s901b-2026-05-20 | weightedScore=0.0835 | criticalFailures=0 | anyDetected=false | exitCode=0
FIXTURE=RedroidV12 | deviceLabel=redroid-12-amd64-2026-05-20 | weightedScore=0.3697 | criticalFailures=4 | anyDetected=true | exitCode=1
FIXTURE=RedroidSpoofed | deviceLabel=redroid-12-amd64-2026-05-20-spoofed-v1 | weightedScore=0.0000 | criticalFailures=0 | anyDetected=false | exitCode=0
FIXTURE=FridaInjectedRedroid | deviceLabel=redroid-12-amd64-2026-05-21-frida-injected | weightedScore=0.3697 | criticalFailures=4 | anyDetected=true | exitCode=1
FIXTURE=Nox | deviceLabel=nox-vbox86-2026-05-21 | weightedScore=0.1771 | criticalFailures=1 | anyDetected=true | exitCode=1
FIXTURE=Genymotion | deviceLabel=genymotion-vbox86p-2026-05-21 | weightedScore=0.1771 | criticalFailures=1 | anyDetected=true | exitCode=1
FIXTURE=BlueStacks | deviceLabel=bluestacks-x86_64-2026-05-21-minimal-public-only | weightedScore=0.1418 | criticalFailures=0 | anyDetected=true | exitCode=1
```

The first 4 rows match the production-binary numbers exactly (0.1171 / 0.0835 /
0.3697 / 0.0000 and matching exit codes), confirming the embedded harness is
faithful to the shipped binary's replay path.

---

## Test-fixture leak guard (production binary refuses test-set names)

Running the 4 test-set names against the **production** binary returns the
deliberate refusal (real output, each `exit=0` because it is a clean refusal,
not a crash):

```
$ detection-cli replay-snapshot FridaInjectedRedroid
detection-cli replay-snapshot: PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES: snapshot 'FridaInjectedRedroid' lives in :detection's test sourceSet (positive-path detection fixture). Production binaries deliberately do not ship test fixtures. Use the test CLI (`:detection-cli:test`) for full 8-snapshot coverage.

$ detection-cli replay-snapshot Nox
detection-cli replay-snapshot: PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES: snapshot 'Nox' lives in :detection's test sourceSet ...

$ detection-cli replay-snapshot Genymotion
detection-cli replay-snapshot: PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES: snapshot 'Genymotion' lives in :detection's test sourceSet ...

$ detection-cli replay-snapshot BlueStacks
detection-cli replay-snapshot: PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES: snapshot 'BlueStacks' lives in :detection's test sourceSet ...
```

This is the expected, correct behavior: the shipped artifact must not carry
detector test fixtures.

---

## Verification commands (reproducible)

```bash
cd /home/coder/vk-repos/phantomdroid
BIN=agents/detection-cli/build/install/detection-cli/bin/detection-cli

# Production 4 (summary line is the last line of stdout):
for f in Pixel7Clean SamsungS22Clean RedroidV12 RedroidSpoofed; do
  $BIN replay-snapshot "$f" 2>&1 | tail -1
done

# Test-set 4 (refused by production binary, by design):
for f in FridaInjectedRedroid Nox Genymotion BlueStacks; do
  $BIN replay-snapshot "$f" 2>&1 | head -1
done

# All 8 through the same runReplaySnapshot() entry point via the
# already-compiled test classes (no gradle build):
CP=$(ls agents/detection-cli/build/install/detection-cli/lib/*.jar | tr '\n' ':')
CP="agents/detection-cli/build/classes/kotlin/test:agents/detection/build/classes/kotlin/test:$CP"
java -classpath "/tmp/proof4:$CP" ReplayHarness \
  Pixel7Clean SamsungS22Clean RedroidV12 RedroidSpoofed \
  FridaInjectedRedroid Nox Genymotion BlueStacks
```

(`/tmp/proof4/ReplayHarness.java` calls
`ReplaySnapshotCommandKt.runReplaySnapshot(TestSnapshotRegistry.INSTANCE, name, "0.1.0")`
via the public API and prints the aggregate.)

---

## Verdict

**VERIFIED — 8/8 PASS.**

- Clean real devices (Pixel7Clean, SamsungS22Clean) → CLEAN, exit 0.
- Unspoofed emulator RedroidV12 → DETECTED (4 critical failures), exit 1.
- RedroidSpoofed (Iter-1 spoof) → weightedScore exactly 0.0000, CLEAN, exit 0.
- Frida + all three vendor emulators (FridaInjectedRedroid, Nox, Genymotion,
  BlueStacks) → DETECTED, exit 1.
- Production binary correctly refuses test-set fixtures with the
  `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` leak guard.
