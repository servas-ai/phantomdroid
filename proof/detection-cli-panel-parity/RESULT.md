# detection-cli panel parity — 69 → 84 → 83 → 82 probes (snapshot panel = canonical minus 2 un-snapshot-able)

> **CAPTURE-GAP CLOSURE (2026-06-01, builder follow-up — supersedes the
> "tracked follow-up" hand-waving below):** every bucket-B probe that scored
> nonzero on B2 **from absence of capture** has been resolved. The 14
> newly-registered bucket-B probes were re-classified **A/B/C/D** and acted on:
> the CAPTURABLE ones (A) are now captured by an extended `live_matrix.py`
> (vendor fingerprint, touch/audio HAL props + `/dev/input/event*` + `/dev/snd`,
> GMS package+props, usb config + redroid/qemud props, `/data/adb/modules`
> listing, `init.svc.*` map) so they discriminate on the container's REAL state;
> the UN-CAPTURABLE one (B) — `IntegrityInstallSourceProbe`, whose only signal
> is the detector app's OWN `getInstallSourceInfo()` (an APPLICATION-layer fact
> a `docker exec` capture cannot observe) — was **EXCLUDED** like keystore
> (`EXPECTED_COUNT` 83 → **82**). The honest B2 re-measure on the **82-panel** is
> **0.1764 SUSPICIOUS, 2 GENUINE critical** (see the
> **Capture-gap closure (A/B/C/D resolution)** section at the bottom + the full
> per-probe table in `../b2-sensor-lte/RESULT.md` §3a-ter). Relationship:
> `CLI(82) == canonical(84) − {KeystoreAttestationProbe, IntegrityInstallSourceProbe}`.

**Date:** 2026-06-01
**Branch:** session/e2e-2026-05-30
**Posture:** DEFENSIVE ReDroid lab. Host-side Kotlin work + ONE serial container boot for the B2 re-measure.

> **CORRECTION (2026-06-01, adversarial-validator follow-up):** the original
> "no bucket-C / all 15 are snapshot-scoreable" conclusion was **wrong for ONE
> probe**. `KeystoreAttestationProbe` (`integrity.keystore_attestation`) is
> **genuinely live-only (bucket C)** and has been **excluded from the snapshot
> panel** → **EXPECTED_COUNT 84 → 83**. See the **Correction** section below.
> The snapshot panel is now `CLI(83) == canonical(84) − {KeystoreAttestationProbe}`.
> The canonical/live `FullProbeRunnerSpoofTest` panel **stays 84** (the live
> attestation path can run a real TEE challenge).

## Summary

The detection-cli scoring panel (`agents/detection-cli/.../ProbeRegistry.kt`,
`EXPECTED_COUNT`) was a **69-probe SUBSET** of the canonical **84-probe** panel
(`FullProbeRunnerSpoofTest.allProbes()`). 15 probes were missing, so every proof
snapshot (incl. B2) was scored against an **under-covering** detector. This work
first brought the CLI panel to full parity (69 → 84), then — after adversarial
validation — **excluded the one genuinely-live-only probe** so the snapshot
panel is the honest `canonical − {KeystoreAttestationProbe}` = **83**.

**Headline honesty payoff:** re-scoring B2 on the **83-probe** panel (the two
GENUINE root/integrity criticals, no keystore absence-noise) gives **0.1697
(SUSPICIOUS, 2 critical)**. The earlier **0.1853 (DETECTED, 3 critical)**
84-panel figure is **SUPERSEDED** — its 3rd critical was the non-discriminating
keystore absence-noise. Measuring more completely (69 → 83) still raises the
aggregate above the prior 0.1279 subset figure and keeps the rooted container
detectable on its REAL signals.

## Per-probe A/B/C classification

Each probe's `ctx.*` accessors were read from its `.kt`, checked against
`SnapshotReplayContext.kt` (accessor implemented?) and `live_matrix.py`
(backing snapshot field POPULATED by the Python capture?).

**Verification overturned the task's bucket-C hypotheses for 14 of 15 — but
adversarial validation later overturned that for KeystoreAttestation:** four of
the five "live-only candidates" (NetworkIpAsn, PlayIntegrityLive,
AudioFingerprint, TouchPressure) ARE declarative L3 variants that score from
`getSystemProperty` / `fileExists` / `readFile` (implemented in
`SnapshotReplayContext`). But **`KeystoreAttestationProbe` is NOT** a usable
snapshot probe: its only non-zero snapshot branch is
`SCORE_HARDWARE_KEYSTORE_ABSENT = 0.70`, triggered purely by ABSENCE of
`ro.hardware.keystore` / `/dev/keymaster` — fields that the real clean-device
fixtures (Pixel7Clean, SamsungS22Clean) do NOT record. So it fired **0.70
IDENTICALLY on clean Pixel, clean Samsung, AND the ReDroid B2 container** — a
non-discriminating signal, and (because it ranks in the top-10) a **false
CRITICAL on a genuinely-clean real device**. The probe's own doc says
"Confidence: 0.75 (declarative inference). Real attestation via [live
challenge]" — i.e. hardware-backed attestation fundamentally needs a LIVE TEE
challenge-response and cannot be honestly assessed from a static snapshot.
**It is genuinely live-only (bucket C) and is excluded from the snapshot panel.**
The repo invariant that "PROHIBITS live IP probes" (Probe invariant #2, no live
network) is *honored by* `NetworkIpAsnProbe` precisely because it is declarative
(reads build-props + `/proc/net/route`), not violated by it.

| # | Probe | Accessors | Impl in SnapshotReplayContext? | Backing field populated by live_matrix? | Bucket | Registered? |
|---|-------|-----------|-------------------------------|-----------------------------------------|--------|-------------|
| 1 | APatchRootProbe | fileExists, getSystemProperty | yes | absent-branch 0.0; NOW captures `/data/adb/ap*` (none present → clean) | **D→A (captured)** | yes |
| 2 | KernelSURootProbe | fileExists, getSystemProperty | yes | absent-branch 0.0; NOW captures `/data/adb/ksu*` (none present → clean) | **D→A (captured)** | yes |
| 3 | MagiskModuleDirProbe | queryDirEntries | yes | NOW captures `/data/adb/modules` listing → B2 = present-but-empty → **0.95 GENUINE** (Magisk installed) | **A (captured)** | yes |
| 4 | FingerprintCrossPartitionProbe | getSystemProperty | yes | NOW captures `ro.vendor.build.fingerprint` → B2 vendor=redroid DIVERGES from spoofed system → **1.0 GENUINE** | **A (captured)** | yes |
| 5 | ThirdPartyEmulatorArtifactsProbe | fileExists | yes | absent-branch 0.0 (positive-observation only; Nox/MEmu init.rc genuinely absent) | **D** | yes |
| 6 | InitSvcEnumerationProbe | queryInitSvcProps | yes | NOW captures the full `init.svc.*` map → B2 = 62 services, few_unknown → **0.5 GENUINE** | **A (captured)** | yes |
| 7 | FridaMemoryMapsProbe | queryProcSelfMapsLibs, queryRuntimeThreadNames, queryOpenTcpPorts | yes | absent-branch 0.0 (positive-observation only; native ptrace harness out of scope) | **D** | yes |
| 8 | NativePrologueHashProbe | queryPrologueHashDeltas, queryTrampolinePatternCount | yes | absent-branch 0.0 (native ptrace harness out of scope) | **D** | yes |
| 9 | PrologueGotHooksProbe | queryGotPltAnomalies, queryRwxpMemorySegments | yes | absent-branch 0.0 (native GOT walker out of scope) | **D** | yes |
| 10 | IntegrityInstallSourceProbe | queryInstallSourcePackage | yes | absent-branch 0.85 `unknown_installer`; signal is the detector app's OWN `getInstallSourceInfo()` — an APPLICATION-layer fact a `docker exec` capture is NOT inside the app to observe → un-capturable on the snapshot path | **B (un-capturable) → EXCLUDED** | **NO — excluded** |
| 11 | NetworkIpAsnProbe | getSystemProperty, readFile | yes | NOW captures `persist.sys.usb.config`/`redroid.*`/qemud → B2 usb=`adb` (not empty), qemud unset → **0.0** (prior 0.5 was absence-noise, NOT a genuine board inference) | **A (captured)** | yes |
| 12 | PlayIntegrityLiveProbe | getSystemProperty, queryPackageManager | yes | NOW captures GMS package + `ro.com.google.*` → B2 has ZERO Google pkgs → **0.95 GENUINE** noGmsAtAll | **A (captured)** | yes |
| 13 | KeystoreAttestationProbe | fileExists, getSystemProperty | yes | only non-zero snapshot branch is `SCORE_HARDWARE_KEYSTORE_ABSENT=0.70` from ABSENCE of `ro.hardware.keystore`/`/dev/keymaster` — fires 0.70 identically on clean Pixel/Samsung AND ReDroid (non-discriminating); real attestation needs a LIVE TEE challenge | **C (live-only) → EXCLUDED** | **NO — excluded** |
| 14 | AudioFingerprintProbe | fileExists, getSystemProperty | yes | NOW captures `ro.hardware.audio`/`/dev/snd/controlC0` → B2 HAL genuinely UNSET (null, not set-empty) → **0.0 clean** (the no-HAL branch needs a SET-EMPTY prop; honest capture gives null) | **A (captured)** | yes |
| 15 | TouchPressureProbe | fileExists, getSystemProperty | yes | NOW captures `ro.hardware.touchscreen` + `/dev/input/event0..4` → B2 genuinely no HAL + 0 event devices → **0.95 GENUINE** no_touch_hal | **A (captured)** | yes |

**Bucket A (scoreable + data fully captured today):** none are purely-A; the
closest is NetworkIpAsnProbe whose `ro.boot.hardware` input IS captured (so it
produces a real `0.5` emulator-board inference on B2) while its other inputs are
capture-gaps.
**Bucket B (scoreable, accessor implemented, backing data not yet captured):**
14 of the 15 — registered. Each scores a conservative verdict on its absent-data
branch (0.0 for the "absent != clean" native probes; "unknown/suspicious" for
the install-source / vendor-fingerprint / no-HAL probes whose ABSENCE is itself
anomalous). **No data was fabricated.**
**Bucket C (genuinely live-only / EXCLUDED from the snapshot panel):**
**KeystoreAttestationProbe (`integrity.keystore_attestation`).** Its only
snapshot-reachable non-zero score is a 0.70 ABSENCE penalty that fires
identically on clean real devices and on the container — non-discriminating, and
a false top-10 CRITICAL on a clean Pixel. Hardware-backed key attestation needs
a live TEE challenge-response, so it is honestly assessable ONLY on the live
path. It is **removed from the CLI snapshot panel (EXPECTED_COUNT 84 → 83)** and
**retained in the canonical/live `FullProbeRunnerSpoofTest` panel (84)**.

## What changed (code)

1. `ProbeRegistry.kt` — registered all 15 probes; `EXPECTED_COUNT` **69 → 84**;
   updated header doc + section counts (buildprop 4→5, emulator 4→5, integrity
   2→6, network 4→5, root 6→9, runtime 7→10, ui 5→7).
2. `Main.kt`, `ReplaySnapshotCommand.kt`, `CliIntegrationTest.kt`,
   `ReplaySnapshotCliTest.kt` — "69-probe"/"65-probe"/"(65)" references → 84.
3. `SnapshotLoader.SnapshotDto` — added deserialization for `procSelfMapsLibs`,
   `runtimeThreadNames`, `openTcpPorts`, `prologueHashDeltas`,
   `trampolinePatternCount`, `gotPltAnomalies`, `rwxpMemorySegments`,
   `initSvcProps`, `dirEntries`, `installSourcePackage` (deserialization only —
   a missing field stays empty/default; nothing fabricated).
4. `redroid-spoofed-snapshot.yml` (CLI clean fixture) — re-synced the spoof-mask
   fields from `RedroidSpoofedSnapshot.kt` that the YAML had drifted from, so the
   newly-registered probes score their clean branches: `ro.vendor.build.fingerprint`
   (mirrors system → no MHPC divergence), keystore masks (`ro.boot.veritymode=enforcing`,
   `ro.boot.warranty_bit=0`, `ro.boot.warranty=1`, `ro.bootmode=normal`,
   `ro.hardware.keystore=gs201`, `/dev/keymaster`), Play-Integrity masks
   (`com.google.android.gms` package, `ro.com.google.gmsversion`/`clientidbase`,
   `ro.gms.disabled=0`), touch masks (`ro.hardware.touchscreen`,
   `/dev/input/event0-3`), audio masks (`ro.hardware.audio`, `/dev/snd/controlC0`),
   `installSourcePackage=com.android.vending`, clean `initSvcProps`, clean
   `/proc/net/route`.

## CLEAN-invariant finding (IntegrityInstallSourceProbe 0.05 floor)

`IntegrityInstallSourceProbe.SCORE_CLEAN = 0.05` **by design** — even a legit
`com.android.vending` install scores 0.05 (the install source is forgeable, so a
0.05 floor is the cleanest achievable verdict; there is NO input that scores 0.00).
This made `ReplaySnapshotCliTest`'s prior "weightedScore == **exactly** 0.0000"
invariant incompatible with including the probe. The canonical 84-panel
`FullProbeRunnerSpoofTest` already tolerates this (it asserts **CLEAN category +
0 critical**, weighted ~2.0E-4, not exactly 0). The CLI invariant was updated to
match: **weightedScore stays in the CLEAN band (< 0.10) and the ONLY residual
permitted is the install-source 0.05 floor** — any OTHER probe firing is still a
spoof-bleed-through regression. This preserves the load-bearing guarantee while
honestly reflecting the probe's irreducible floor.

## Correction — KeystoreAttestation excluded; clean fixtures verified 0-critical

The adversarial-validator follow-up (2026-06-01) found `KeystoreAttestationProbe`
is absence-noise on the snapshot panel (above). It was **removed from
`ProbeRegistry.allProbes()`** and **`EXPECTED_COUNT` set 84 → 83**. The canonical
`FullProbeRunnerSpoofTest` was **left at 84** (live attestation path unchanged).
Relationship asserted in code + comments: `CLI(83) == canonical(84) −
{KeystoreAttestationProbe}`.

**Clean-fixture verification on the 83-panel (the bar: a genuinely-clean
real-device fixture MUST be 0-critical):**

| fixture | totalProbes | weightedScore | criticalFailures | anyDetected | category |
|---------|-------------|---------------|------------------|-------------|----------|
| Pixel7Clean | 83 | 0.1008 | **0** | false | CLEAN (exit 0) |
| SamsungS22Clean | 83 | 0.0720 | **0** | false | CLEAN (exit 0) |

Both are now **0 critical** (the keystore 0.70 top-10 critical is gone;
Pixel7Clean was 0.1184 / 1 critical on the 84-panel). **No other
newly-registered probe is absence-noise on the clean fixtures** — all 14 remaining
newly-added probes score 0.0 on both clean fixtures except `integrity.install_source`
at its documented irreducible **0.05 clean floor** (not a critical, not absence-noise).
The Pixel7Clean residual 0.1008 is driven by **pre-existing** probes
`identity.android_id` (`(null)` fixture value) and `env.language_country`
(`<unreadable>` fixture value) — long-standing probes reacting to fixture-data
gaps, NOT introduced by the parity work, and NOT criticals.

## Test results

- detection-cli Kotlin: **19/19 PASS** (`CliIntegrationTest` 4, `ReplaySnapshotCliTest` 15) on the 83-panel.
- canonical `FullProbeRunnerSpoofTest` (84-panel, `-PrunSpoofPanel=true`): **PASS** (CLEAN, 0 critical) — unchanged.
- Python suite: **119 passed**.

## B2 re-measure (the honesty payoff)

Fresh ONE-container boot (`parity-build`, port 5821,
`build_hardened_run_argv`, Privileged=false, root=uid=0), live-captured via
`live_matrix.capture_live_snapshot`, scored with the 84-panel CLI. Container +
datadir removed after.

| panel | weightedScore | criticalFailures | category |
|-------|---------------|------------------|----------|
| 69-probe (prior) | 0.1279 | 1 | SUSPICIOUS |
| 84-probe (intermediate, SUPERSEDED) | 0.1853 | 3 | DETECTED |
| **83-probe (HONEST — keystore absence-noise excluded)** | **0.1697** | **2** | **SUSPICIOUS** |

The 84-panel **0.1853 DETECTED / 3 critical** figure is **SUPERSEDED**: its 3rd
critical was `integrity.keystore_attestation = 0.70`, the non-discriminating
absence-noise (same 0.70 it gives a clean Pixel). With it excluded, the honest
B2 aggregate on the **83-panel** is **0.1697 SUSPICIOUS with 2 GENUINE
criticals** (re-scored from `parity-build-fresh-snapshot.yml`).

**The 2 critical failures (rank 1..10, score ≥ 0.7) — both validator-confirmed REAL:**
- `root.su_detection = 1.0` (rank 3) — REAL: Magisk `su` present (was the 1 critical on the 69-panel).
- `integrity.play_integrity = 0.95` (rank 2) — rooted no-GMS container fails the live-verdict inference.

(`integrity.keystore_attestation` no longer appears — excluded from the snapshot
panel. The category drops DETECTED → SUSPICIOUS because criticals fall from 3 to
2, below the `critFailures ≥ 3` DETECTED threshold; the two REAL root/integrity
criticals and the detectability of the rooted container are unaffected.)

**Newly-firing probes on the rooted B2 container (14 snapshot probes, by score;
`integrity.keystore_attestation` EXCLUDED as live-only):**
- `integrity.play_integrity = 0.95`, `ui.touch_pressure = 0.95`,
  `buildprop.fingerprint_cross_partition = 0.85`, `integrity.install_source = 0.85`,
  `network.ip_asn = 0.5` — fire.
- `emulator.third_party_artifacts`, `root.apatch`, `root.kernelsu`,
  `root.magisk_module_dir`, `runtime.frida_memory_maps`,
  `runtime.init_svc_enumeration`, `runtime.native_prologue_hash`,
  `integrity.prologue_got_hooks`, `ui.audio_fingerprint` — score 0.0 (clean OR
  no-observation; see capture-gaps below).

### Honesty caveat — capture-gap-driven vs genuine signal

Several of the new firing probes fire because the **live_matrix capture does not
yet read their inputs**, so they hit their "absent → anomalous" branch:

| probe | B2 score | driver | genuine signal or capture-gap? |
|-------|----------|--------|-------------------------------|
| `integrity.play_integrity` | 0.95 | GMS package + `ro.com.google.*` not captured → noGmsAtAll | mostly GENUINE (the container truly has no GMS) but capture-gap-amplified |
| `ui.touch_pressure` | 0.95 | `ro.hardware.touchscreen`/`/dev/input/event*` not captured | GENUINE (ReDroid has no touchscreen HAL) but capture-gap-amplified |
| `network.ip_asn` | 0.5 | `ro.boot.hardware=redroid` IS captured | GENUINE (real emulator-board inference) |
| `buildprop.fingerprint_cross_partition` | 0.85 | `ro.vendor.build.fingerprint` not in `_PROP_KEYS` → vendor_absent | CAPTURE-GAP (untested whether vendor fp diverges) |
| `integrity.install_source` | 0.85 | `installSourcePackage` not captured → unknown_installer | CAPTURE-GAP (installer genuinely unknown for a non-app-context capture) |

The **0.1697 SUSPICIOUS (2 critical)** figure on the 83-panel is the **honest
TRUE score given the current capture fidelity**, with the keystore absence-noise
removed. It is conservative in the detection direction: extending the capture
(below) could only confirm/raise the bucket-B probes, not lower the genuine root
signal. (The superseded 0.1853/84-panel number is retained above only to document
the correction.)

## Tracked capture-gap follow-ups (extend live_matrix.py to fire these properly)

To turn the bucket-B probes from "absent-branch verdict" into a directly-measured
verdict, `live_matrix.capture_live_snapshot` must additionally capture:

1. `ro.vendor.build.fingerprint` → add to `_PROP_KEYS` (FingerprintCrossPartition).
2. GMS package presence + `ro.com.google.gmsversion`/`clientidbase`/`ro.gms.disabled`,
   `ro.boot.veritymode` (PlayIntegrityLive).
3. `ro.hardware.keystore`, `ro.boot.warranty*`, `ro.bootmode`, `/dev/keymaster`
   (KeystoreAttestation).
4. `ro.hardware.touchscreen`, `/dev/input/event0..4` presence (TouchPressure).
5. `ro.hardware.audio`, `persist.audio.loopback`, `ro.audio.silent.in`,
   `/dev/snd/controlC0` (AudioFingerprint).
6. `PackageManager.getInstallSourceInfo()` for the detector app → `installSourcePackage`
   (IntegrityInstallSource).
7. `init.svc.*` enumeration → `initSvcProps` map (InitSvcEnumeration).
8. `ls /data/adb/modules` → `dirEntries` (MagiskModuleDir); `/data/adb/ksu*`,
   `/data/adb/ap*` into the root path-set (KernelSU, APatch).
9. `redroid.*`/`ro.docker.container`/`ro.kernel.android.qemud` props + `/proc/net/route`
   (NetworkIpAsn — second-tier signals).
10. Native harness for `/proc/self/maps` libs, task thread names, `/proc/net/tcp`
    ports, prologue-hash deltas, GOT/PLT anomalies, rwxp segments (Frida /
    NativePrologueHash / PrologueGotHooks — needs a native ptrace-style probe;
    these remain "absent != clean" 0.0 until then).

None of these were fabricated for the B2 measurement — the absent fields stay
empty/default and the probes score their honest conservative branch.

---

## Capture-gap closure (A/B/C/D resolution) — 2026-06-01

The "Tracked capture-gap follow-ups" list above is now **CLOSED**. Every
bucket-B probe was re-classified and acted on; no probe contributes a nonzero
score to B2 from absence-of-capture anymore.

### `live_matrix.py` extensions (A — CAPTURABLE)

`capture_live_snapshot` now additionally captures (all read-only `docker exec`,
nothing fabricated; a genuinely-absent input stays null/empty so the probe
scores its honest branch):

1. **Prop dump rewrite (the decisive honesty fix):** props are now parsed from
   the FULL `getprop` bracket-dump (`[key]: [value]`) via `_parse_getprop_dump`.
   A genuinely-UNSET prop is OMITTED from `systemProperties` (→
   `getSystemProperty` null); a SET-EMPTY prop (`[key]: []`) is recorded as `""`.
   The previous `getprop <key>` form could not distinguish the two and recorded
   every requested key as `""` (non-null) — which FABRICATES presence for probes
   that key off non-null-ness (`NetworkIpAsn.qemudExists = prop != null`,
   `Audio.noHalNoDevice = hal != null && hal.isEmpty()`). This eliminated the
   only NEW absence-noise the extension could have introduced.
2. `ro.vendor.build.fingerprint` (+ touch/audio/usb/gms/redroid prop keys) added
   to `_PROP_KEYS`.
3. `capture_probe_filesystem` — `/dev/input/event0..4`, `/dev/snd/controlC0`,
   `/data/adb/{ksu,ksud,ap,ap/bin/apd}` (records only paths that exist).
4. `capture_dir_entries` — `/data/adb/modules` listing → `dirEntries` map
   (present-empty `[]` = Magisk installed; absent = key omitted → null).
5. `capture_init_svc_props` — the full `init.svc.*` map → `initSvcProps`.

The SnapshotDto already deserialized `dirEntries` / `initSvcProps`; the YAML
emitter was extended to render them.

### Exclusion (B — UN-CAPTURABLE on the snapshot path)

`IntegrityInstallSourceProbe` (`integrity.install_source`) was **removed from
`ProbeRegistry.allProbes()`** and **`EXPECTED_COUNT` set 83 → 82**. Its only
signal is the detector app's OWN `PackageManager.getInstallSourceInfo()` — an
APPLICATION-layer fact observable only from inside the running detector app. The
`live_matrix` capture is a read-only `docker exec` harness NOT running inside
that app, so the field is structurally un-capturable on the snapshot path and
its uncaptured `null` is a false 0.85 absence-nonzero (the same class as
keystore). It is retained in the canonical/live `FullProbeRunnerSpoofTest` panel
(84). Relationship asserted in code + the count test:
`CLI(82) == canonical(84) − {KeystoreAttestationProbe, IntegrityInstallSourceProbe}`.

### Clean-fixture verification on the 82-panel (the bar: 0 critical, no absence-noise)

| fixture | totalProbes | weightedScore | criticalFailures | anyDetected | category |
|---------|-------------|---------------|------------------|-------------|----------|
| Pixel7Clean | 82 | 0.1010 | **0** | false | CLEAN (exit 0) |
| SamsungS22Clean | 82 | 0.0720 | **0** | false | CLEAN (exit 0) |
| RedroidSpoofed | 82 | **0.0000** | **0** | false | CLEAN (exit 0) |

Both real-device clean fixtures stay **0 critical**. NONE of the resolved
bucket-B probes (`fingerprint_cross_partition`, `touch_pressure`,
`magisk_module_dir`, `init_svc_enumeration`, `play_integrity`, `network.ip_asn`,
`audio_fingerprint`) fires on either clean fixture (their clean-branch inputs are
populated by the in-source fixtures). The Pixel7Clean residual 0.1010 is driven
by **pre-existing** fixture-data probes (`identity.android_id` `(null)`,
`env.language_country` `<unreadable>`), NOT introduced here and NOT criticals.
`RedroidSpoofed` is now **strictly exactly 0.0** — the install_source 0.05 floor
that was the sole permitted residual is gone (probe excluded), so the
`ReplaySnapshotCliTest` CLEAN invariant tightened to "NO probe scores > 0.0".

### Honest B2 re-measure (the payoff)

Fresh ONE-container boot (`an-build`, port 5825, `build_hardened_run_argv`,
Privileged=false, root uid=0), live-captured via the EXTENDED
`live_matrix.capture_live_snapshot`, scored on the 82-panel. Container + datadir
removed after.

| panel | weightedScore | criticalFailures | category |
|-------|---------------|------------------|----------|
| 69-probe (prior) | 0.1279 | 1 | SUSPICIOUS |
| 83-probe (keystore excluded; SUPERSEDED — still carried absence-noise) | 0.1697 | 2 | SUSPICIOUS |
| **82-probe (HONEST — capture-gaps CLOSED, install_source excluded)** | **0.1764** | **2** | **SUSPICIOUS** |

The aggregate moved **0.1697 → 0.1764** (UP). WHY (honest): closing the gaps
added two GENUINE root signals that were previously UNDER-reported
(`magisk_module_dir` 0.0→0.95, `init_svc_enumeration` 0.0→0.5) and turned the
fingerprint tell from a 0.85 absence-guess into a **1.0 measured vendor
divergence** — these outweigh removing the `install_source` (0.85) and
`network.ip_asn` (0.5) absence-noise. The 2 GENUINE criticals remain:
`root.su_detection = 1.0` and `integrity.play_integrity = 0.95`. Snapshot +
report: `../b2-sensor-lte/an-build-honest-{snapshot.yml,report.json}`. Full
per-probe before/after table: `../b2-sensor-lte/RESULT.md` §3a-ter.
