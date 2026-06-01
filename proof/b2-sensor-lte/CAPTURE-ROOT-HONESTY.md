# B2 Capture Root-Honesty Fix — the 0.0850-CLEAN headline was an overclaim

**Date:** 2026-06-01
**Scope:** Defensive detection-resistance research in an owned lab. NOT an operational evasion tool.
**Supersedes:** the `0.0850 — CLEAN` headline in `RESULT.md` and the prior `after-augmented-*` numbers.

---

## 1. The bug — the live capture probed only 1 of N root paths

`agents/orchestrator/src/live_matrix.py :: capture_live_snapshot` probed root via a
**single** path and recorded only that one file:

```python
su_present = bool(_docker_exec(container, "test -x /system/xbin/su && echo yes").strip())
...
"existingFiles": ((["/system/xbin/su"] if su_present else []) + font_files),
```

Two independent failure modes, both producing a **false-clean** `su_detection`:

1. **Single-path blindness.** The detector's `SuDetectionProbe.kt` scores `1.0` if **ANY**
   of a full canonical set exists (12 su-binary paths + 4 Magisk-artifact paths + the
   `com.topjohnwu.magisk` package). The capture only looked at `/system/xbin/su`, so even
   when `/sbin/su`, `/sbin/.magisk`, `/data/adb/magisk` are present (they always are on a
   Magisk-rooted image), they were never recorded in `existingFiles` and the detector saw
   them as absent.

2. **`test -x` vs the bind-mount mask.** The B2 launcher bind-mounts an **empty,
   non-executable** file over `/system/xbin/su` as a demo mask. `test -x` on that empty file
   returns false, so even the ONE path the capture checked read as "absent" — while
   `/sbin/su` etc. (which the mask cannot touch) stayed fully present and unprobed.

Net effect: on a container where Magisk root is unambiguously present, the capture emitted
`su_detection = 0.0` (false clean), and the B2 aggregate was reported as **0.0850 — CLEAN**
(committed `after-augmented-report.json`, RESULT.md §2/§4). That is an **overclaim**: root
was present the whole time.

### Live proof of the under-report (this re-measure, container `cap-build`)

Raw on-device probe (post-spoof), `[ -e <path> ]`:

```
/system/bin/su      -> absent
/system/xbin/su     -> PRESENT   (bind-mounted empty file; -e true, -x false)
/sbin/su            -> PRESENT   <-- never probed by the old capture
/sbin/.magisk       -> PRESENT   <-- never probed by the old capture
/data/adb/magisk    -> PRESENT   <-- never probed by the old capture
/data/adb/modules   -> PRESENT   <-- never probed by the old capture
/system/bin/magisk  -> absent
```

The old capture saw `/system/xbin/su` as `-x`-false and recorded nothing → `su_detection=0.0`.
Root was present on **four** other paths it never looked at.

---

## 2. The canonical detector surface (from `SuDetectionProbe.kt`)

The fix mirrors `agents/detection/src/probes/root/SuDetectionProbe.kt` exactly.

`SU_BINARY_PATHS` (any present → score 1.0):

```
/system/bin/su            /system/sd/xbin/su        /data/local/su        /su/bin/su
/system/xbin/su           /system/bin/failsafe/su   /system/sbin/su       /magisk/.core/bin/su
/sbin/su                  /data/local/xbin/su       /vendor/bin/su
                          /data/local/bin/su
```

`MAGISK_ARTIFACT_PATHS` (any present → score 1.0):

```
/sbin/.magisk   /system/bin/magisk   /cache/magisk.log   /data/adb/magisk
```

`SUPERUSER_PACKAGES` (any installed, no binary → score 0.85):

```
com.topjohnwu.magisk   eu.chainfire.supersu   com.koushikdutta.superuser   com.thirdparty.superuser
```

Package check semantics: the probe calls `queryPackageManager().isPackageInstalled(pkg)`;
the snapshot replay backs that with `pkg in snapshot.installedPackages`. The honest capture
therefore runs `pm list packages` and records any of the four superuser packages present.

(The capture additionally probes `/data/adb/modules` — a Magisk module dir — as an extra
honest artifact; it is recorded in `existingFiles` but is not part of the probe's own scoring
set, so the canonical mirror lists above stay byte-identical to the Kotlin.)

---

## 3. The fix

`live_matrix.py` now defines `_SU_BINARY_PATHS`, `_MAGISK_ARTIFACT_PATHS`,
`_MAGISK_EXTRA_PATHS`, `_SUPERUSER_PACKAGES` (mirroring the Kotlin) and a new
`capture_root_surface(container)` that:

* probes **every** path with `[ -e "<p>" ] && echo "<p>"` (one shell round-trip) and records
  every present path, and
* runs `pm list packages` and records any present superuser package.

`capture_live_snapshot` now does:

```python
root_files, superuser_packages = capture_root_surface(container)
...
"existingFiles": (root_files + font_files),
"installedPackages": (["android", "com.android.systemui"] + superuser_packages),
```

It does **not** special-case or omit Magisk's own `/sbin` paths — it records what is actually
present. All other snapshot fields (telephony, settings, props, fonts, display, locale, GPS)
are unchanged.

A regression test guards it: `tests/test_orchestrator_snapshot_emitter.py ::
test_capture_root_surface_does_not_underreport_magisk` simulates a device where `/sbin/su` +
`/data/adb/magisk` are present but `/system/xbin/su` is **absent**, and asserts the Magisk
paths appear in the captured root surface (root is NOT under-reported). Full suite: **109
passed** (was 108).

---

## 4. The corrected B2 numbers (honest re-measure)

Container `cap-build`, hardened NON-privileged (`build_hardened_run_argv`), image
`redroid/redroid:12.0.0_magisk`, port 5791. L2/L6 spoof via
`launch-l2-l6-sensor-lte-spoof.sh cap-build 5791`. Captured with the FIXED path, scored with
the same `detection-cli` the B2 proof used.

| Metric | OLD (overclaim) | CORRECTED (honest) |
|---|---|---|
| `root.su_detection` | 0.0 | **1.0** (evidence: `/system/xbin/su`, `/sbin/su`, `/sbin/.magisk`, `/data/adb/magisk` present) |
| aggregate `weightedScore` | 0.0850 | **0.1403** |
| `category` | CLEAN | **SUSPICIOUS** |
| `criticalFailures` | 0 | **1** (`root.su_detection`) |

The build-prop, verified-boot, identity (serial/telephony) and LTE/radio surfaces remain
clean — that part of B2 stands. What changes is that **root is honestly scored as DETECTED**,
so the aggregate is `0.1403 SUSPICIOUS`, not `0.0850 CLEAN`.

Note on the package check: after the spoof, `pm list packages` no longer shows
`com.topjohnwu.magisk` (Magisk DenyList / repackaged manager hides the APK). The honest
capture records `installedPackages` without it — that is correct; the package surface really
is hidden. But the **filesystem su/magisk binaries are still present and unmaskable**, which
is why `su_detection` is still `1.0`. Hiding the manager APK does not hide root.

---

## 5. The real tradeoff — CLEAN-without-root vs rooted-but-detectable

The honest result exposes the actual B2 ceiling:

* The L1/L0b/L2/L6 prop-spoof surface is genuinely clean.
* **Root is not durably hideable on this image.** The Magisk `su` binaries (`/sbin/su`,
  `/system/xbin/su`) and `/data/adb/magisk` survive the spoof pass, and per
  `../b2-l4-zygisk/BLOCKER-L4-FRESH-FORK.md` the L4 runtime-hiding (Zygisk/Shamiko-style
  fresh-fork masking) does **not** durably hide them across fresh forks. Manual file masks
  (the empty-file bind-mount over `/system/xbin/su`) are demo-only and do not cover
  `/sbin/su` / `/data/adb/magisk`.

So the genuine choice is:

* **CLEAN but NOT rooted** — drop Magisk; the device passes `su_detection` but loses the
  resetprop/property_service write capability that L2/L6 depend on, **or**
* **rooted but root-DETECTABLE** — keep Magisk (what B2 actually is); aggregate
  `0.1403 SUSPICIOUS`, `su_detection=1.0`.

There is no "rooted AND clean" state available with the current toolchain. The previously
committed "0.0850 CLEAN with root present" was a measurement artifact, not a real device
state. This document is the honest correction.

---

## 6. Artifacts regenerated with the fixed capture

* `after-snapshot.yml` / `after-report.json` — 0.1403 SUSPICIOUS, `su_detection=1.0`
* `after-augmented-snapshot.yml` / `after-augmented-report.json` — 0.1403 SUSPICIOUS, `su_detection=1.0`
* `L2-augmented-snapshot.yml` / `L2-augmented-report.json` — 0.1403 SUSPICIOUS, `su_detection=1.0`

`RESULT.md` headline and §2/§6 corrected to the honest `0.1403 SUSPICIOUS` number. The
earlier-committed `before`/`mid*` snapshots are historical (pre-fix capture) and left as-is;
they predate the root surface and are not relied on for the headline.

---

## 7. ADDENDUM (2026-06-01) — the mount-namespace / UDS lower-bound, now closed

The `0.1403` in §1–§6 was itself a **LOWER BOUND**. The su-path fix (§1–§2) corrected
`su_detection`, but the capture still recorded a **conservative no-observation NULL** for the
four dispositive mount-namespace / Unix-domain-socket root probes, because of TWO gaps:

1. **Capture gap.** `live_matrix.capture_live_snapshot` never read `/proc/self/mountinfo`,
   `/proc/1/mountinfo` or `/proc/net/unix`, so the snapshot carried no `mountInfo` /
   `procNetUnixSockets` fields. **Fix:** new `capture_mount_and_uds()` reads all three via
   `su -c cat` (root needed for `/proc/1/mountinfo` on the hardened container); a failed read
   stays NULL — never fabricated. The YAML emitter now emits both fields.
2. **Scorer gap.** The detection-cli `ProbeRegistry` (a 65-probe subset of the canonical
   84-probe `FullProbeRunnerSpoofTest.allProbes()`) did **not** include the four probes, and
   `SnapshotLoader.SnapshotDto` did not deserialize the two fields — so even a snapshot that
   carried them would have been silently dropped. **Fix:** registered
   `MountNsMismatchProbe`, `OverlayFsPresentProbe`, `SystemRwMountProbe`, `MagiskUdsProbe`
   (`EXPECTED_COUNT` 65→69) and added the two fields to the DTO + `toDomain()`.

### Live ground truth (container `mnt-build`, post-spoof, full confidence 0.95)

```
/proc/net/unix    -> /sbin/.magisk/device/socket  (x2)  => root.magisk_uds        = 0.95
/ is overlay (overlay2 rootfs)                          => root.overlayfs_present = 0.85
/proc/self == /proc/1 mountinfo (byte-identical),
  no .magisk-PATH substring asymmetry                   => root.mount_ns_mismatch = 0.00 (digest_match)
/ mounted ro, no standalone /system rw entry            => root.system_rw_mount   = 0.00 (root_ro_sar)
```

`mount_ns_mismatch` and `system_rw_mount` are **legitimately clean** here: the probe runs
outside an app process (no zygote mount-namespace isolation → `self`/`1` match), and the
Magisk mount is a bare `tmpfs magisk` on `/sbin` (no `.magisk`-PATH substring, so no
fingerprint asymmetry); `/` is read-only. These are honest 0.0s, not under-measurement.

### Controlled effect of the fuller capture (same snapshot, same 69-probe panel)

| capture | weightedScore |
|---------|---------------|
| conservative-null (mount-ns/UDS stripped — the OLD behaviour) | **0.1175** |
| honest fuller capture (this addendum) | **0.1279** |

Δ = **+0.0104**. The honest figure is **HIGHER** — measuring the mount-ns/UDS surface adds
two real root signals (`magisk_uds 0.95`, `overlayfs 0.85`) that the conservative null hid.
**The B2 headline is updated to `0.1279 SUSPICIOUS`; `0.1403` is superseded as a lower bound.**
`after-*` / `after-augmented-*` artifacts regenerated with the fuller capture (now contain
`mountInfo` + `procNetUnixSockets` and the four probe results).

### Addendum (2026-06-01) — 84-probe parity, then 83-probe HONEST headline (keystore absence-noise excluded)

The `0.1279` figure was scored on the **69-probe** detection-cli panel, itself a
subset of the canonical **84-probe** inventory. The remaining 15 canonical probes
were registered (`EXPECTED_COUNT` 69 → 84; see
`../detection-cli-panel-parity/RESULT.md`). An intermediate re-measure on the full
84-panel gave **0.1853 — DETECTED, 3 critical**.

**That 0.1853/DETECTED figure is now itself SUPERSEDED.** An adversarial validator
found its 3rd critical, `integrity.keystore_attestation = 0.70`, is
**absence-noise**: the probe's only snapshot-reachable non-zero branch penalizes
the mere ABSENCE of `ro.hardware.keystore`/`/dev/keymaster` — fields that the real
clean-device fixtures (Pixel7Clean, SamsungS22Clean) also don't record — so it
fires **0.70 identically on a clean Pixel (falsely flagging it CRITICAL), a clean
Samsung, and the container**. Hardware-backed key attestation can only be honestly
assessed via a **live TEE challenge-response**, so the probe is genuinely
live-only and was **excluded from the snapshot panel** (`EXPECTED_COUNT` 84 → 83);
it remains in the canonical/live panel. Re-measuring B2 on the **83-probe**
snapshot panel gives **0.1697 — SUSPICIOUS, 2 GENUINE critical failures**
(`root.su_detection=1.0`, `integrity.play_integrity=0.95`). **The B2 headline is
updated to `0.1697 SUSPICIOUS`; `0.1279`, `0.1403`, `0.0850`, and the intermediate
`0.1853 DETECTED` are all superseded.** The category drops DETECTED → SUSPICIOUS
only because criticals fall from 3 to 2 (below the `critFailures ≥ 3` DETECTED
threshold) once the noise critical is removed; the rooted container stays clearly
detectable on its REAL signals. Several remaining firing probes are amplified by
live_matrix capture-gaps (their inputs aren't read yet) — tracked as follow-ups in
the parity RESULT; the genuine root signal (`su_detection`, `magisk_uds`,
`overlayfs`) is unaffected.
