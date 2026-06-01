# B2 — L2/L6 identity + LTE-radio spoof on the Magisk-rooted ReDroid 12 image (RESULT)

**Status:** L1 / L0b / L2 / L6 spoof layers **ACHIEVED on-device**. L5 (sensors) **GENUINELY BLOCKED** (no sensor HAL). L3 / L4 out of B2 scope.
**Headline detector score (RE-MEASURED 2026-06-01, HONEST 83-probe snapshot panel):** **0.1697 — SUSPICIOUS, 2 critical failures** on the **83-probe** snapshot panel (= canonical 84-panel MINUS the one genuinely-live-only `KeystoreAttestationProbe`; see `../detection-cli-panel-parity/RESULT.md`). The 2 critical failures are both validator-confirmed REAL signal: **`root.su_detection = 1.0`** (rank 3, Magisk `su`) and **`integrity.play_integrity = 0.95`** (rank 2, rooted no-GMS container fails the live-verdict inference). Full-confidence root signals also firing: **`root.magisk_uds = 0.95`** (`/sbin/.magisk/device/socket` in `/proc/net/unix`) and **`root.overlayfs_present = 0.85`** (overlayfs on `/`). The build/identity/radio surface is still clean; **root remains DETECTABLE** (Magisk `su` + `/data/adb/magisk` + magisk UDS + overlayfs present, not durably hideable — L4 fresh-fork blocker). NOTE: several of the firing probes (`play_integrity`, `touch_pressure`, `install_source`, `fingerprint_cross_partition`) fire partly because `live_matrix.py` does not yet capture their inputs (capture-gaps tracked in `../detection-cli-panel-parity/RESULT.md`) — the verdict is conservative-in-the-detection-direction and the genuine root signal is unaffected. The earlier **0.0850 CLEAN headline was an OVERCLAIM**; see **`CAPTURE-ROOT-HONESTY.md`**. **The 0.1853-DETECTED / 84-panel figure is itself now SUPERSEDED:** its 3rd critical was `integrity.keystore_attestation = 0.70`, which an adversarial validator confirmed is **absence-noise** — it scores that SAME 0.70 on a genuinely-clean Pixel (flagging it CRITICAL), so it is non-discriminating and was excluded from the snapshot panel. The honest aggregate with only the 2 GENUINE criticals is **0.1697 SUSPICIOUS**. (Superseded figures: 0.0850-CLEAN, 0.1403-lower-bound, 0.1279-69-probe, and 0.1853-84-probe → all replaced by **0.1697 (83-panel)**.)
**Posture:** hardened, NON-privileged (B4 recipe via `container_lifecycle.build_hardened_run_argv`), Magisk-rooted (`redroid/redroid:12.0.0_magisk`, B1).
**Launcher:** `agents/stability/stack/launch-l2-l6-sensor-lte-spoof.sh`
**Container under test:** `b2-build-work` (also relaunched as `b2-l2l6-*` / `b2-fix` for the snapshot pass).
**Date:** 2026-05-31
**Scope note:** Defensive detection-resistance research in an owned lab. NOT an operational evasion tool.

---

## 1. Per-layer summary

| Layer | Surface | Technique (exact, from launcher) | Live evidence | Achieved? |
|-------|---------|----------------------------------|---------------|-----------|
| **L1** | build props (Pixel 7 `panther` / A13) | `resetprop` on `ro.product.{brand,manufacturer,model,name,device,board}`, `ro.board.platform=gs201`, `ro.hardware=gs201`, `ro.build.fingerprint=google/panther/panther:13/TQ3A.230805.001/10316531:user/release-keys`, `ro.build.{display.id,tags,type}`, `ro.bootloader`/`ro.boot.bootloader=slider-1.2-9512283`, `ro.product.locale=en-US` | `before`→`mid` snapshots; `buildprop.*` probes 1.00→0.00 | **YES** |
| **L0b** | verified-boot / debuggable lock surface | `resetprop ro.debuggable 0`, `ro.boot.vbmeta.device_state green`, `ro.boot.verifiedbootstate green`, `ro.boot.flash.locked 1`, `ro.oem_unlock_supported 0`, `ro.warranty_bit 0`, `ro.boot.warranty_bit 0` (root unlocks `ro.debuggable=0`) | `mid`→`mid2` progression | **YES** |
| **L2** | identity: device serial + telephony | `resetprop ro.serialno`/`ro.boot.serialno = 2A111FDH2002KQ` (Pixel-class form, non-stock); `gsm.sim.state READY`; `ril.iccid.sim1 = 8901410329988776652` (89-prefix, 19-digit, Luhn-valid, not in `KNOWN_EMULATOR_ICCIDS`). **No IMEI is set** — ReDroid exposes none, and none is invented. | `after-augmented-snapshot.yml` `telephony.{SERIAL,SIM_SERIAL,OPERATOR_NAME,MCC_MNC}` (with `IMEI: null`); probes `identity.sim_iccid` 0.70→0.00, `identity.imei_serial` 0.70→**0.50** (IMEI-null + valid serial = benign) | **YES** (scored via telephony-aware capture — see §4) |
| **L6** | LTE / radio operator + SIM | `resetprop gsm.sim.operator.{numeric=310410,alpha=AT&T,iso-country=us}`, `gsm.operator.*` (same), `gsm.network.type LTE`, `gsm.current.phone-type 1`, `persist.radio.multisim.config ss` — AT&T 310/410, NOT the AVD-canonical T-Mobile 310/260 the `CarrierMccMnc` probe flags | `after-snapshot.yml`/`after-augmented`; `identity.carrier_mccmnc = 0.00` | **YES** |
| **L5** | sensors | — (cannot be done by props/bind-mount) | `BLOCKER-L5.md` + §5 | **NO — genuinely blocked (no sensor HAL)** |
| **L3** | attestation (PIF / TrickyStore keybox) | out of B2 scope (needs keybox; tracked `L3-DEFAULT.md`) | — | out of scope |
| **L4** | runtime hiding (Shamiko / HMA) | out of B2 scope (Zygisk module install) | — | out of scope |

Supporting "post-boot overlay" hardening also applied by the launcher (not core L2/L6 but part of the same pass): `wm size 1080x2400` / `density 420`; bind-mount of spoofed `/proc/cpuinfo`, `/proc/version` (Pixel `5.10.107-android13` string), `/proc/meminfo` (7.8 GB); `persist.sys.timezone=America/Los_Angeles`; `net.dns1/2`; `settings put global {time_zone, development_settings_enabled=0, adb_enabled=0}`; bind-mount of empty file over `/system/xbin/su`.

---

## 2. Detector progression (aggregate weightedScore / category)

| Snapshot | weightedScore | category | What landed |
|----------|---------------|----------|-------------|
| `before` | **0.3294** | **DETECTED** | unspoofed rooted ReDroid 12 |
| `mid` | 0.2159 | SUSPICIOUS | + L1 build props |
| `mid2` | 0.1715 | SUSPICIOUS | + L0b verified-boot / debuggable lock |
| `mid3` | 0.1062 | SUSPICIOUS | + display/proc overlays |
| `after` (RE-MEASURED, fuller capture, 69-probe) | **0.1279** | **SUSPICIOUS** | + L2/L6 props; honest su/Magisk-path capture (`su_detection=1.0`) **+ mount-ns/UDS capture → `magisk_uds=0.95`, `overlayfs_present=0.85` now MEASURED at full confidence** |
| `after-augmented` (RE-MEASURED, fuller capture, 69-probe) | **0.1279** | **SUSPICIOUS** | same device state, telephony-aware + full root-surface capture incl. `/proc/{self,1}/mountinfo` + `/proc/net/unix` |
| ~~`parity-build`-fresh (84-probe panel, SUPERSEDED)~~ | ~~0.1853~~ | ~~DETECTED~~ | **SUPERSEDED** — its 3rd critical `keystore_attestation=0.70` is absence-noise (same 0.70 on a clean Pixel); excluded → see row below |
| **`parity-build`-fresh (RE-MEASURED 2026-06-01, HONEST 83-probe snapshot panel)** | **0.1697** | **SUSPICIOUS** | **same device state, fresh live boot+capture, 83-probe panel (canonical 84 MINUS live-only `keystore_attestation`) — 2 GENUINE critical (`su_detection=1.0`, `play_integrity=0.95`). See `../detection-cli-panel-parity/RESULT.md`.** |
| ~~`after-augmented` (CORRECTED, 65-probe lower bound)~~ | ~~0.1403~~ | ~~SUSPICIOUS~~ | **LOWER BOUND** — mount-ns/UDS probes absent from panel + no capture data; see §3a |
| ~~`after-augmented` (old, SUPERSEDED)~~ | ~~0.0850~~ | ~~CLEAN~~ | **OVERCLAIM** — capture under-reported root (`su_detection=0.0`); see `CAPTURE-ROOT-HONESTY.md` |

**Controlled "fuller capture raises the score" comparison (same container `mnt-build`, same snapshot, same 69-probe panel):**

| Capture | weightedScore | mount-ns/UDS probes |
|---------|---------------|---------------------|
| conservative-null (mount-ns/UDS stripped, the old behaviour) | **0.1175** | all 4 score no-observation null (conf 0.30–0.50) |
| honest fuller capture (this work) | **0.1279** | `magisk_uds=0.95`, `overlayfs_present=0.85` fire at conf 0.95; `mount_ns_mismatch=0.0` (digest_match), `system_rw_mount=0.0` (root_ro_sar) |

Δ = **+0.0104** — honest measurement makes root MORE detectable. The two probes that score 0.0 do so legitimately (self/init mountinfo are byte-identical here because the probe runs outside an app process, so there is no zygote namespace isolation and no `.magisk`-path substring asymmetry; `/` is mounted `ro`).

Net (RE-MEASURED): **0.3294 DETECTED → 0.1279 SUSPICIOUS** with **1 critical (`root.su_detection`) remaining** and the root surface now scored across **all four** mount-ns/UDS probes. `buildprop.*`, `ui.screen_resolution` cleared; **root NOT cleared** (Magisk `su`/`/data/adb/magisk`/UDS/overlayfs present, not durably hideable — see `CAPTURE-ROOT-HONESTY.md` and `../b2-l4-zygisk/BLOCKER-L4-FRESH-FORK.md`).

---

## 3a. Mount-namespace / UDS root probes — now MEASURED (were a hidden lower bound)

The prior 0.1403 figure under-measured root for two compounding reasons, both fixed in this pass:

1. **Capture gap:** `live_matrix.capture_live_snapshot` never read `/proc/self/mountinfo`, `/proc/1/mountinfo`, or `/proc/net/unix`, so the snapshot had no `mountInfo` / `procNetUnixSockets` fields. (Fixed: new `capture_mount_and_uds()` reads them via `su -c cat`; a failed read stays NULL — never fabricated.)
2. **Scorer gap:** the detection-cli `ProbeRegistry` (65-probe subset) did not include the four mount-ns/UDS probes that the canonical 84-probe inventory (`FullProbeRunnerSpoofTest.allProbes()`) does, AND `SnapshotLoader.SnapshotDto` did not deserialize the two fields. (Fixed: registered the 4 probes → `EXPECTED_COUNT` 65→69; added `mountInfo` + `procNetUnixSockets` to the DTO + `toDomain()`.)

Measured on the live rooted `mnt-build` container (full confidence 0.95 = real observation, not the prior conservative null):

| probe | score | pattern | what the device actually showed |
|-------|-------|---------|---------------------------------|
| `root.magisk_uds` | **0.95** | `magisk_uds_present` | `/sbin/.magisk/device/socket` present in `/proc/net/unix` (2 entries) — dispositive Magisk daemon socket |
| `root.overlayfs_present` | **0.85** | `overlay_on_root_sar` | `/` is `overlay` (Docker overlay2 rootfs / system-as-root) |
| `root.mount_ns_mismatch` | **0.00** | `digest_match` | `/proc/self` and `/proc/1` mountinfo are byte-identical (probe ran outside an app process → no zygote isolation; the `magisk` tmpfs mount carries no `.magisk`-PATH substring, so no fingerprint asymmetry). Honest clean. |
| `root.system_rw_mount` | **0.00** | `root_ro_sar` | `/` mounted `ro`; no standalone `/system` rw entry. Honest clean. |

So two of the four mount-ns/UDS probes fire as additional dispositive root signals; the other two are legitimately clean on a system-as-root container scanned outside an app process. The honest aggregate is therefore **0.1279**, not 0.1403 — the figure moved UP, which is the correct result.

### 3a-bis. Further 69 → 84 → 83 panel parity (2026-06-01) — honest 0.1697 SUSPICIOUS

The 0.1279 figure above was still a **subset-panel** number: the detection-cli
`ProbeRegistry` (69 probes after the mount-ns/UDS fix) was missing 15 more
canonical probes that `FullProbeRunnerSpoofTest.allProbes()` (84) carries. Those
15 were registered (`EXPECTED_COUNT` 69 → 84) and the DTO extended to deserialize
their fields; see **`../detection-cli-panel-parity/RESULT.md`** for the full
A/B/C classification.

An intermediate re-measure on the full 84-panel gave 0.1853 — DETECTED, 3
critical. **That 0.1853/DETECTED figure is SUPERSEDED.** An adversarial validator
found that one of its 3 criticals, `integrity.keystore_attestation = 0.70`, is
**absence-noise**: the probe's only snapshot-reachable non-zero branch is a 0.70
penalty for missing `ro.hardware.keystore`/`/dev/keymaster`, fields that real
clean-device fixtures (Pixel7Clean, SamsungS22Clean) also don't record — so it
scores 0.70 **identically** on a clean Pixel (falsely flagging it CRITICAL), a
clean Samsung, and the container. Hardware key attestation needs a **live TEE
challenge-response**, so this probe is genuinely live-only and was **excluded
from the snapshot panel** (`EXPECTED_COUNT` 84 → 83; it remains in the
canonical/live panel). Re-measuring B2 on the **83-probe** snapshot panel gives
**0.1697 — SUSPICIOUS, 2 GENUINE critical** (`root.su_detection=1.0`,
`integrity.play_integrity=0.95`). The aggregate still sits well above the prior
0.1279 subset figure and the rooted container stays clearly detectable on its
REAL signals; only the non-discriminating keystore noise was removed. Several of
the remaining firing probes are amplified by live_matrix capture-gaps (their
inputs aren't read yet); those gaps are tracked as follow-ups in the parity
RESULT and do not affect the genuine root signal.

---

## 3. What still scores (the irreducible residue at 0.1279)

| probe | score | why it remains |
|-------|-------|----------------|
| `root.su_detection` | **1.00** (critical) | **Root is present and NOT durably hideable.** Magisk `su` (`/sbin/su`), `/sbin/.magisk`, and `/data/adb/magisk` are present in the rooted image. Per-app hiding requires fork-time Zygisk injection, which does not work on this system-as-root x86_64 image (`../b2-l4-zygisk/BLOCKER-L4-FRESH-FORK.md`). This is the honest cost of running the **rooted** (B1) image: CLEAN-without-root vs rooted-but-detectable is the real tradeoff. See `CAPTURE-ROOT-HONESTY.md`. |
| `root.magisk_uds` | **0.95** | **Magisk daemon UDS leak.** `/sbin/.magisk/device/socket` appears in `/proc/net/unix` — dispositive Magisk presence, now measured (was conservative null). |
| `root.overlayfs_present` | **0.85** | **overlayfs on `/`.** Container rootfs is Docker overlay2 / system-as-root overlay — the Momo-on-A11+ tell, now measured (was conservative null). |
| `emulator.cpu_abi` | **1.00** | **x86_64 ABI ceiling.** ReDroid runs x86_64; a Pixel 7 is arm64. This is architectural and cannot be spoofed by props — it is the hard ceiling for this image class. |
| `identity.bluetooth_mac` | 0.85 | no Bluetooth adapter in ReDroid (capture reports null MAC) |
| `sensors.{light,magnetometer,proximity}` | 0.85 ea. | MISSING_ON_PHONE — phone-class model with no sensor HAL (see §5 / `BLOCKER-L5.md`) |
| `identity.imei_serial` | **0.50** | **IMEI-null benign path.** The device exposes **no IMEI** (`SCORE_IMEI_NULL_BENIGN`): IMEI is null but the device serial is a valid, non-default Pixel-class form, which is exactly the Android 10+ privacy default a real device produces. This is the honest floor for an image that has no GSMA-issued IMEI — it cannot be driven to 0.00 without fabricating an IMEI, which we do not do. |
| `sensors.accelerometer_gyro` | 0.50 | NO_SIGNAL — accessor reports zero sensors |
| `env.location_mock_rasp`, `identity.wifi_mac` | 0.50 ea. | no GPS/Wi-Fi backing in this image |

The dominant residual is **`root.su_detection = 1.0`** (the single critical), followed by the **architectural ceilings of ReDroid** (`emulator.cpu_abi` + the `sensors.*` family), neither of which is a gap in the L2/L6 work. The `identity.imei_serial = 0.50` residue is the honest cost of NOT inventing an IMEI: a null IMEI paired with a valid serial is the benign, expected reading.

---

## 4. Telephony-aware capture (honest serial/ICCID) and why both `after` snapshots now read 0.1403

**This is a legitimate capture-completeness fix, not probe suppression.** Two separate capture bugs were corrected (both raised honesty, one raised the score):

1. **Telephony blind spot (lowers two telephony probes, honestly):** the old `capture_live_snapshot` hardcoded `telephony: {}`, so `identity.imei_serial`/`identity.sim_iccid` saw an *empty* map and applied a NULL penalty (0.70) to telephony the device genuinely sets. The committed `capture_telephony(container)` helper now reads the live device.
2. **Root blind spot (RAISES `root.su_detection`, the decisive correction):** the old capture probed only `/system/xbin/su` (1 of 16 paths) with `test -x`, missing Magisk's `/sbin/su` + `/data/adb/magisk` → a FALSE `su_detection=0.0`. The committed `capture_root_surface(container)` now probes the full `SuDetectionProbe` set → honest `su_detection=1.0`. **This is why the headline is 0.1403 SUSPICIOUS, not 0.0850 CLEAN** (see `CAPTURE-ROOT-HONESTY.md`).

After fix #2, the regenerated `after` and `after-augmented` reports are **identical (0.1403)** — the root signal dominates and the old telephony-path distinction (after-augmented < after) is moot. The telephony detail below documents fix #1 (still valid):

- Both reports contain the **same 65 probes** — none were dropped, disabled, or hidden. Only **two probe scores** change:
  - `identity.sim_iccid`: 0.70 → **0.00** (the real, valid 89-prefix Luhn-passing ICCID is now visible)
  - `identity.imei_serial`: 0.70 → **0.50** (a real, valid serial is now visible; the IMEI is honestly null → benign `SCORE_IMEI_NULL_BENIGN`, **not** 0.00)

- The fix is now a **real, committed, auditable code path**, not an unaudited side tool. `agents/orchestrator/src/live_matrix.py::capture_live_snapshot` previously hardcoded `telephony: {}` and never read the telephony surface, so the `identity.imei_serial`/`identity.sim_iccid` probes saw an *empty* telephony map and applied a NULL/"missing" penalty (0.70) to telephony the device genuinely sets. The capture now calls a committed `capture_telephony(container)` helper that performs read-only `getprop` / `service call iphonesubinfo` reads of the live device and maps them onto the detector's `TelephonyField` names. It serialises **whatever the device actually returns** and never invents a value.

  `after-snapshot.yml` (old telephony-blind capture):
  ```yaml
  telephony: {}
  ```

  `after-augmented-snapshot.yml` (committed telephony-aware capture, **same device, same boot-class state**):
  ```yaml
  telephony:
    "IMEI": null
    "SERIAL": "2A111FDH2002KQ"
    "OPERATOR_NAME": "AT&T"
    "MCC_MNC": "310410"
    "SIM_SERIAL": "8901410329988776652"
  ```

  The values `SERIAL`, `OPERATOR_NAME`, `MCC_MNC`, `SIM_SERIAL` are the live reads of the `resetprop` set (`ro.serialno = 2A111FDH2002KQ`, AT&T `310410`, `ril.iccid.sim1 = 8901410329988776652`). **`IMEI` is `null` because the device exposes no IMEI** — `service call iphonesubinfo 1` returns a null parcel (`Result: Parcel(00000000 ffffffff '........')`), there is no IMEI property (`getprop | grep -i imei` is empty), and the launcher sets none. **No IMEI is invented.** A null IMEI alongside a valid, non-default serial is precisely the Android 10+ privacy default and scores 0.50 (`SCORE_IMEI_NULL_BENIGN`), not 0.00.

- The 0.70 penalties in `after` were **false NULL positives caused by the capture tool's blind spot** (`telephony: {}` read as "missing serial + missing ICCID"). The committed telephony-aware capture corrects the measurement for the two surfaces the device genuinely sets (serial + ICCID + operator) **while honestly leaving IMEI null** — which is why `imei_serial` lands at 0.50, not 0.00.

Note that `identity.carrier_mccmnc` was already **0.00 in `after`** (it is scored from `gsm.operator.*` props that the standard capture *does* read), confirming the L6 operator spoof was effective independently of the telephony-aware capture.

**Conclusion: the headline is 0.1403 SUSPICIOUS, dominated by `root.su_detection = 1.0`.** The device-side L1/L0b/L2/L6 spoof is real and present (build/identity/radio all clean), and the telephony-aware capture honestly reads serial + ICCID + operator while leaving the genuinely-absent IMEI null. But the **rooted** image carries Magisk `su` that cannot be durably hidden per-app on this stack, so root is detectable — that is the honest residual. The earlier 0.0850-CLEAN figure was an artifact of the root-capture blind spot and is SUPERSEDED.

---

## 5. L5 — genuinely blocked (link)

See **`proof/b2-sensor-lte/BLOCKER-L5.md`** for the full honest write-up. Summary: ReDroid 12 ships framework-side sensor HIDL stubs but **no backing sensor HAL implementation**, so `sensorservice` has nothing to bind to:

```
$ docker exec b2-build-work su -c 'dumpsys sensorservice | head -3'
No Sensors on the device
devInitCheck : -19

$ docker exec b2-build-work su -c 'ls /vendor/lib*/hw/sensors*; ls /vendor/lib*/*android.hardware.sensors*'
ls: /vendor/lib*/hw/sensors*: No such file or directory
ls: /vendor/lib*/*android.hardware.sensors*: No such file or directory

$ docker exec b2-build-work su -c 'service list | grep -i android.hardware.sensors'
no android.hardware.sensors HAL service registered
```

Root + `resetprop` cannot synthesize a sensor — `SensorManager.getSensorList()` is populated by the bound HAL, not a property. Setting a phone-class model (L1) actually *raises* the sensor probes (`MISSING_ON_PHONE`), an honest measured trade-off documented in `BLOCKER-L5.md`. Unblocking L5 requires authoring a custom `sensors.redroid.so` HAL + vendor manifest + SELinux policy on a custom image — a HAL-development project, out of B2's property/bind-mount scope.

---

## 6. Done vs blocked (final)

- **ACHIEVED on-device (surface clean):** L1 build props, L0b verified-boot/debuggable lock, **L2 identity (serial + telephony)**, **L6 LTE/radio operator+SIM**. Aggregate 0.3294 DETECTED → **0.1403 SUSPICIOUS** (CORRECTED). These four surfaces score clean; the residual non-clean score is **root** (`su_detection=1.0`, 1 critical) — see `CAPTURE-ROOT-HONESTY.md`. The earlier "0.0850 CLEAN" was an overclaim from the capture root-honesty bug.
- **GENUINELY BLOCKED:** L5 sensors (no sensor HAL — architectural; see `BLOCKER-L5.md`).
- **ARCHITECTURAL CEILING (cannot be spoofed on ReDroid):** `emulator.cpu_abi` (x86_64), Bluetooth/Wi-Fi/GPS MACs, sensor family.
- **OUT OF B2 SCOPE:** L3 attestation (keybox — `L3-DEFAULT.md`), L4 runtime hiding (Zygisk/Shamiko/HMA).

## Artifact index

| File | Contents |
|------|----------|
| `launch-l2-l6-sensor-lte-spoof.sh` (`agents/stability/stack/`) | the durable B2 launcher (L1/L0b/L2/L6 spoof pass) |
| `before-{snapshot.yml,report.json}` … `after-augmented-{…}` | detector progression (§2) |
| `L2-augmented-{snapshot.yml,report.json}` | telephony-aware capture of the L2 layer (same committed `capture_live_snapshot` path, IMEI honestly null; telephony content identical to `after-augmented`) |
| `BLOCKER-L5.md` | honest L5 sensor-HAL blocker |
