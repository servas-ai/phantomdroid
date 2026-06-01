# B2 — L2/L6 identity + LTE-radio spoof on the Magisk-rooted ReDroid 12 image (RESULT)

**Status:** L1 / L0b / L2 / L6 spoof layers **ACHIEVED on-device**. L5 (sensors) **GENUINELY BLOCKED** (no sensor HAL). L3 / L4 out of B2 scope.
**Headline detector score (CORRECTED 2026-06-01):** **0.1403 — SUSPICIOUS** (down from 0.3294 DETECTED), with **`root.su_detection = 1.0`** (1 critical failure). The build/identity/radio surface is clean, but **root remains DETECTABLE** (Magisk `su` + `/data/adb/magisk` are present and NOT durably hideable — L4 fresh-fork blocker). The previously-stated **0.0850 CLEAN headline was an OVERCLAIM** caused by a capture bug that probed only `/system/xbin/su` (one of N su paths) and recorded `su_detection=0.0`. See **`CAPTURE-ROOT-HONESTY.md`** for the bug, the fix, and the corrected numbers. The 0.0850-CLEAN artifacts are SUPERSEDED.
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
| `after` (CORRECTED) | **0.1403** | **SUSPICIOUS** | + L2/L6 props on-device; **honest root capture probes all su/Magisk paths → `su_detection=1.0`** |
| `after-augmented` (CORRECTED) | **0.1403** | **SUSPICIOUS** | same device state, telephony-aware + honest root capture (su present, IMEI honestly null) |
| ~~`after-augmented` (old, SUPERSEDED)~~ | ~~0.0850~~ | ~~CLEAN~~ | **OVERCLAIM** — capture under-reported root (`su_detection=0.0`); see `CAPTURE-ROOT-HONESTY.md` |

Net (CORRECTED): **0.3294 DETECTED → 0.1403 SUSPICIOUS** with **1 critical (`root.su_detection`) remaining**. `buildprop.*`, `ui.screen_resolution` cleared; **root NOT cleared** (Magisk `su`/`/data/adb/magisk` present, not durably hideable — see `CAPTURE-ROOT-HONESTY.md` and `../b2-l4-zygisk/BLOCKER-L4-FRESH-FORK.md`).

---

## 3. What still scores (the irreducible residue at 0.1403)

| probe | score | why it remains |
|-------|-------|----------------|
| `root.su_detection` | **1.00** (critical) | **Root is present and NOT durably hideable.** Magisk `su` (`/sbin/su`), `/sbin/.magisk`, and `/data/adb/magisk` are present in the rooted image. Per-app hiding requires fork-time Zygisk injection, which does not work on this system-as-root x86_64 image (`../b2-l4-zygisk/BLOCKER-L4-FRESH-FORK.md`). This is the honest cost of running the **rooted** (B1) image: CLEAN-without-root vs rooted-but-detectable is the real tradeoff. See `CAPTURE-ROOT-HONESTY.md`. |
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
