# Vector → SpoofStack Map for the 14 Testable Detector Apps

**Date**: 2026-05-30
**Scope**: DEFENSIVE detection-resistance research. Maps each *software-spoofable*
detector app (installed live on the Redroid12 x86 test device) to the signal(s) it
reads, the internal `detection-cli` probe that mirrors that signal, and the
SpoofStack layer (L1–L6) that defeats it. Out of scope by owner mandate:
SafetyNet / PlayIntegrity / KeyAttestation (TEE / API-EOL ceilings).

**Anti-fabrication discipline**: every row is tagged
- **[REPO]** = verified from a file in this repo (probe source, service.d script, audit doc, uia/screenshot evidence), with the path.
- **[KNOW]** = from general knowledge of how the named app works (RootBeer/Ruru/applistdetector internals), where the repo does not contain the app's bytecode.

**Primary repo sources used**:
- Probe corpus `agents/detection/src/probes/**` (read directly — see per-app citations).
- `docs/SPOOFSTACK-LIBRARIES.md` (layer → module → status table).
- `audit/spoof-stack/p21-real-world-verdict-matrix.md` (byte-grounded live verdicts).
- `audit/spoof-stack/p21-app-coverage-skill.md` (per-app install + detection status).
- `audit/spoof-stack/power-19-magisk-variants.md` (su-path / Manager-pkg surfaces).
- `agents/stability/stack/layers.md`, `infrastructure/spoof-stack-magisk/service.d/00-spoof.sh`,
  `agents/stability/stack/modules/cpuinfo-overlay/README.md`.
- `audit/proof-7-spoof-delta-2026-05-30.md` (the 5 NEW sensor/BT residual tells).

---

## 0. Critical context: nothing is actively spoofed on the live x86 device yet

Per `p21-app-coverage-skill.md` §5 disclaimer **[REPO]**: the live Redroid12 under
test (`ro.product.cpu.abi=x86_64`, `ro.build.tags=test-keys`, `ro.debuggable=1`,
model `redroid12_x86_64_only`, no TEE) has the SpoofStack **not deployed**. The
"detected" verdicts below are a measure of how visible a *bare* Redroid is, not of
the stack's strength. Only **3 of ~20 specced modules are actually implemented in
repo** (`cpuinfo-overlay`, `hide-frida-maps`, `spoof-stack-magisk`), per
`SPOOFSTACK-LIBRARIES.md` §9 **[REPO]**. So "EASY / defeated by Lx" below means
*the layer is specced and (for L1/L4 build-prop) the in-repo Magisk module already
demonstrates it on snapshots* — not that the live container currently passes.

Second hard constraint **[REPO]** (`proof-7-spoof-delta` §5): the base image is
x86. `emulator.cpu_abi` is kept at `x86_64` on purpose because forcing arm64
crashes zygote64 on the x86 ReDroid base. So every "x86-ABI" axis is only truly
fixed by moving to an **arm64 Redroid host** (Apple Silicon / Graviton), not by a
property hook.

---

## 1. Master table — App → Vector → Probe → Layer → Feasibility

Feasibility legend:
- **EASY** = props / resetprop / Magisk-hide (L1 / L4).
- **MEDIUM** = file or mount overlay / bind-mount (L1 file side / L4).
- **HARD** = sensors / timing / radio hardware synthesis (L5).
- **IMPOSSIBLE** = needs real hardware / TEE (L0 ceiling, out of scope here but noted).

| App (pkg) | Detection vector(s) | detection-cli probe | SpoofStack layer | Feasibility |
|---|---|---|---|---|
| **RootBeer Sample** `com.scottyab.rootbeer.sample` | su binary paths (`/system/xbin/su` etc.); Magisk artifacts (`/sbin/.magisk`, `/data/adb/magisk`); superuser pkg `com.topjohnwu.magisk`; `test-keys` in build tags; dangerous-app / busybox; r/w system mount; BusyBox | `root.su_detection` **[REPO]** (`root/SuDetectionProbe.kt`: 12 su paths + 4 magisk paths + `SUPERUSER_PACKAGES`); `buildprop.tags_and_type`; `runtime.installed_apps` **[REPO]**; `root.system_rw_mount`/`root.overlayfs_present` | L4 Shamiko (DenyList) + HideMyAppList for pkg-list; L1 for `test-keys`→`release-keys`; su-binary hiding needs L4 (Magisk-only modules do NOT hide su — proof-7 §5) | **EASY** (test-keys, pkg list) / **MEDIUM** (su binary + mount → needs Shamiko/Zygisk DenyList, which is currently seccomp-blocked per proof-7 §5) |
| **Root Checker** `com.joeykrim.rootcheck` | su binary in `$PATH`; `which su`; superuser pkg; `test-keys` build tag | `root.su_detection` **[REPO]**; `buildprop.tags_and_type` **[REPO]** | L4 (su hiding via DenyList) + L1 (`release-keys`) | **EASY** (tags) / **MEDIUM** (su binary hide) |
| **Ruru (Detector)** `com.byxiaorun.detector` | x86_64 ABI + dual-arch `libnb` bridge; `test-keys`; `ro.debuggable=1`; build-prop fingerprint/model = `redroid`; PM-scan for root/Xposed apps; method/native detection | `emulator.cpu_abi` **[REPO]** (`emulator/CpuAbiProbe.kt`: x86 primary OR ARM+x86 abilist = 1.0); `buildprop.tags_and_type`, `buildprop.model_brand_manufacturer`, `buildprop.fingerprint` **[REPO]**; `env.developer_options`; `runtime.xposed_lsposed` | L1 build-prop (fingerprint/model/tags/debuggable — done by `00-spoof.sh` **[REPO]**); **x86-ABI axis requires arm64 host** (L0 substrate change); L4 for Xposed/PM-scan hiding | **EASY** for prop/fingerprint/debuggable axes; **IMPOSSIBLE on x86 base** for the ABI axis (only arm64 Redroid clears it — `p21-app-coverage-skill` §3.2 **[REPO]**) |
| **ApplistDetector** `icu.nullptr.applistdetector` | installed-package enumeration for root/hook/cloaker pkgs (Magisk, LSPosed, Shamiko, hide-tools); `/data/adb` visibility; same heuristic class as Ruru | `runtime.installed_apps` **[REPO]** (`runtime/InstalledAppsProbe.kt`: Magisk family, KernelSU, APatch, Xposed managers, root-cloakers); `root.magisk_module_dir` **[REPO]** | L4 HideMyAppList (package-list filtering — probes #10/#19/#50 per `layers.md` **[REPO]**) + Shamiko | **EASY-MEDIUM** (pkg-list filter is L4 HideMyAppList; needs the module deployed, currently SPEC-only per `SPOOFSTACK-LIBRARIES` §5) |
| **Mantle Verify** `com.mantle.verify` | reads device IDs / build props; displays, does not hard pass/fail (no-verdict app on this device per repo). Launch blocked by FINE_LOCATION permission overlay (test-harness gap) | `buildprop.*`, `identity.android_id` **[REPO]** (`identity/AndroidIdProbe.kt`) | L1 (build-prop) + L2 Android Faker (Android ID) | **EASY** (no verdict to flip; props + Android ID are L1/L2) |
| **Treble Info** `tk.hack5.treblecheck` | Treble / GSI status, system image name (`system-x86_64-ab.img.xz`), VNDK; **no-verdict-claim** info app | `emulator.cpu_abi` (x86 image-name leak) **[REPO]**; `buildprop.board_hardware` **[REPO]** | L1 board/hardware props; x86 image name = arm64-host issue | **EASY** for prop side; **HARD/IMPOSSIBLE** to hide x86 GSI image name without arm64 base (but it makes no verdict — low value) |
| **Device Info HW** `ru.andr7e.deviceinfohw` | displays `ro.product.model`, build tags, CPU — no verdict; harness regex false-FAIL on `redroid`/`test-keys` strings | `buildprop.model_brand_manufacturer`, `buildprop.tags_and_type` **[REPO]**; `kernel.cpuinfo_bogomips_implementer` **[REPO]** | L1 build-prop + `cpuinfo-overlay` module **[REPO]** | **EASY** (props) + **MEDIUM** (cpuinfo bind-mount, already implemented as `cpuinfo-overlay` **[REPO]**) |
| **Sensor Box** `imoblife.androidsensorbox` | enumerates physical sensors (accel/gyro/mag/light/proximity/baro); a bare VM has **no sensor HAL** → empty/absent list | `sensors.proximity`, `sensors.light`, `sensors.magnetometer`, `sensors.barometer`, `sensors.accelerometer_gyro` **[REPO]** (`probes/sensors/*`) | L5 VirtualSensor + Trace-Player (sensor injection — `layers.md` §L5 **[REPO]**) | **HARD** (needs synthetic sensor HAL injection; L5 is SPEC-only, 0 modules implemented per `SPOOFSTACK-LIBRARIES` §6) |
| **DevCheck** `flar2.devcheck` | full hardware dashboard: CPU/GPU/sensors/thermal/battery; on x86 it **crashes on launch** (arm-only native libs) | `emulator.gpu_renderer` **[REPO]**; `sensors.*` **[REPO]**; `env.battery_*` | L1 (gpu props) + L5 (sensors) — but blocked first by arch crash | **HARD** (sensor/thermal synthesis) + needs arm64 to even launch (`p21-app-coverage-skill` §3.1 **[REPO]**) |
| **DRM Info** `com.androidfung.drminfo` | Widevine UUID support + security level (`L1` TEE vs `L3` software); on x86 **crashes on launch** (XAPK arm-only DRM libs) | `identity.mediadrm` **[REPO]** (`identity/MediaDrmProbe.kt`: Widevine UUID, L1/L3 level, 32-byte device ID) | L3-ish: Widevine L1 needs TEE; software L3 is all a VM has | **IMPOSSIBLE** (Widevine **L1** is hardware/TEE-rooted; a container can only ever show L3, which is itself a tell) + arch-crash |
| **Device ID (Evozi)** `com.evozi.deviceid` | displays Android ID, GAID, IMEI, serial, MAC — no verdict | `identity.android_id`, `identity.gaid`, `identity.imei_serial`, `identity.wifi_mac` **[REPO]** (`probes/identity/*`) | L2 Android Faker (IMEI/AndroidID/MAC/serial — `layers.md` §L2 **[REPO]**) | **EASY** (no verdict; identity values are L2 — SPEC-only module) |
| **Device Id: Phone Info** `com.akademiteknoloji.androidallid` | same identity-display class as Evozi (Android ID, GSF ID, GAID, IMEI) | `identity.android_id`, `identity.gsf_id`, `identity.gaid`, `identity.imei_serial` **[REPO]** | L2 Android Faker | **EASY** (no verdict) |
| **Device ID (Wenxiang)** `tw.reh.deviceid` | same identity-display class | `identity.android_id`, `identity.imei_serial` **[REPO]** | L2 Android Faker | **EASY** (no verdict) |
| **AIDA64** `com.finalwire.aida64` | exhaustive hardware/CPU/sensor inventory; **no verdict** info app | `kernel.cpuinfo_bogomips_implementer`, `emulator.cpu_abi`, `emulator.gpu_renderer`, `sensors.*` **[REPO]** | L1 props + `cpuinfo-overlay` **[REPO]** + L5 sensors | **EASY** (props/cpuinfo) but **HARD** to make the sensor/thermal pages internally consistent; no verdict so low priority |

---

## 2. The "flip count" — how many testable apps each spoof action clears

Only **4 of the 14 apps actually emit a pass/fail verdict** on this device that we
can flip. The other 10 are *no-verdict info apps* (Device-ID trio, Treble, AIDA64,
Sensor Box, Device Info HW, Mantle) — they merely *display* values, so they cannot
"DETECT" in the binary sense; they only matter if a human reads inconsistent fields
(`p21-app-coverage-skill` §2 **[REPO]**: 10 "no-claim", 4 "DETECTED", 5 "silent
button-tap" which are mostly attestation/out-of-scope).

The 4 verdict-firing in-scope apps (`p21-real-world-verdict-matrix` §2 **[REPO]**):
**Ruru**, **ApplistDetector** (both FAIL = L0-x86 + props), plus **RootBeer** and
**Root Checker** (currently "silent" only because of a button-tap harness gap, but
they *will* fire on su/test-keys).

| Spoof action | Layer | Apps it flips (verdict apps in **bold**) | # verdict apps cleared |
|---|---|---|---|
| **Build-prop rewrite** (fingerprint/model/brand/tags→release-keys, `ro.debuggable=0`, bootloader green) | L1 (`spoof-stack-magisk` `00-spoof.sh` **[REPO]**, IMPLEMENTED) | **Ruru**, **ApplistDetector**, **RootBeer**, **Root Checker** + cleans display in Device Info HW, Treble, Mantle, AIDA64 | **4** (the single highest-leverage action) |
| **Package-list hiding** (HideMyAppList: drop Magisk/LSPosed/cloaker pkgs) | L4 (SPEC) | **ApplistDetector**, **RootBeer** (pkg axis) | 2 |
| **su-binary + mount hiding** (Shamiko DenyList) | L4 (SPEC, seccomp-blocked today) | **RootBeer**, **Root Checker** | 2 |
| **arm64 Redroid host** (clears x86 ABI + libnb bridge) | L0 substrate | **Ruru**, **ApplistDetector** (ABI axis) | 2 |
| **cpuinfo bind-mount** (Cortex-A78/Tensor-G2) | L1 (`cpuinfo-overlay` **[REPO]**, IMPLEMENTED) | Device Info HW, AIDA64 (display consistency) | 0 verdict (info-only) |
| **Identity faking** (IMEI/AndroidID/MAC/serial) | L2 Android Faker (SPEC) | Device-ID trio, Mantle (display) | 0 verdict (info-only) |
| **Sensor HAL injection** | L5 VirtualSensor (SPEC) | Sensor Box, DevCheck, AIDA64 (display) | 0 verdict (info-only) — but see §3, sensor absence is what makes a *spoofed* device contradictory |

**Headline**: a single L1 build-prop rewrite (already implemented and proven on
snapshots) is the highest-leverage move — it flips **all 4** verdict-firing
in-scope apps' property/fingerprint axes. The remaining axes (su binary, pkg list,
x86 ABI) are L4/L0 and are the long pole.

---

## 3. Honest residual tells the prior spoof delta introduced

From `audit/proof-7-spoof-delta-2026-05-30.md` §5 **[REPO]** — applying the L1
build-prop spoof made the device *claim to be a Pixel 7*, which **un-masked 5 new
tells** that did not fire before (because the build-prop noise previously dominated).
Once the device claims phone-class hardware, the absence of that hardware becomes a
contradiction:

| Rank | Probe | Unspoof → Spoof | What leaks (verbatim repo evidence) |
|---|---|---|---|
| 42 | `sensors.proximity` | 0.00 → **0.85** | `proximity.present=false` — a Pixel 7 must have it |
| 43 | `sensors.light` | 0.00 → **0.85** | `light.present=false`, `light.vendor_name=<unavailable>`, `light.sample_summary=<no samples>` |
| 44 | `sensors.magnetometer` | 0.00 → **0.85** | sensor absent on a device claiming phone-class |
| 45 | `sensors.barometer` | 0.00 → **0.50** | same class, lower weight |
| 31 | `identity.bluetooth_mac` | 0.00 → **0.85** | `bluetooth_mac.adapter=null`, `bluetooth_mac.sysfs=<unreadable>`, no IEEE OUI MAC |

**Which testable apps would catch these residual tells:**
- **Sensor Box** (`imoblife.androidsensorbox`) — directly enumerates sensors; would
  show an empty/4-sensor-short list against a Pixel 7's full suite. **Catches all 4
  sensor tells.**
- **DevCheck** (`flar2.devcheck`) — sensor + thermal dashboard would show the gaps
  (once running on arm64).
- **AIDA64** (`com.finalwire.aida64`) — its Sensors page would be empty/inconsistent
  with the claimed Pixel 7.
- **Bluetooth-MAC tell**: none of the 14 testable apps makes a *verdict* from a null
  BT adapter, but the Device-ID apps (Evozi / akademiteknoloji / reh) would *display*
  a blank/absent BT MAC, a human-visible inconsistency. Ruru/applistdetector do not
  currently key on BT adapter null in our evidence.

These residuals are only defeatable by **L5 sensor injection** (VirtualSensor +
Trace-Player) and **L2/identity BT-adapter synthesis** — both **SPEC-only, 0
modules implemented** (`SPOOFSTACK-LIBRARIES` §5/§6 **[REPO]**). They are the honest
price of the L1 spoof: it fixes strings but cannot synthesize a sensor HAL or radio.

---

## 4. HARD / IMPOSSIBLE apps (cannot be flipped in software on this substrate)

| App | Why HARD / IMPOSSIBLE |
|---|---|
| **DRM Info** (`com.androidfung.drminfo`) | **IMPOSSIBLE** — Widevine **L1** is TEE-rooted; a container can only present software **L3**, which is itself the tell. Also arch-crashes on x86. (`identity.mediadrm` **[REPO]**) |
| **Sensor Box** (`imoblife.androidsensorbox`) | **HARD** — needs L5 synthetic sensor HAL (SPEC-only). Bare VM has no sensors. |
| **DevCheck** (`flar2.devcheck`) | **HARD** + arch — sensor/thermal synthesis (L5) and must run on arm64 to launch. |
| **Ruru / ApplistDetector** (ABI axis only) | **IMPOSSIBLE on x86 base** — `emulator.cpu_abi` x86_64 cannot be propped away (forcing arm64 crashes zygote64, proof-7 §5). Their *other* axes (props, test-keys, pkg-list) are EASY/MEDIUM. Clearing the ABI axis requires an **arm64 Redroid host** (L0 substrate swap). |
| **AIDA64** (sensor/thermal pages) | **HARD** — full hardware-page consistency needs L5; but no verdict, so low priority. |

Out-of-scope ceilings (excluded by owner mandate, noted for completeness):
SafetyNet/PlayIntegrity (`com.henrikherzig.playintegritychecker`,
`krypton.tbsafetychecker`, `com.scottyab.safetynet.sample`) and KeyAttestation
(`io.github.vvb2060.keyattestation`) are all **L0-attestation IMPOSSIBLE** — they
need a real provisioned TEE (`SPOOFSTACK-LIBRARIES` §10 **[REPO]**).

---

## 5. Provenance audit

- **[REPO]-verified**: all probe behaviours (su paths, abilist logic, qemu nodes,
  cpuinfo, sensor presence, MediaDrm levels, Android ID), the L1 `00-spoof.sh`
  resetprop set, the `cpuinfo-overlay` bind-mount, the layer→module table, and the
  4 live FAIL verdicts (Ruru/ApplistDetector byte-grounded in
  `p21/uia/*.xml`), plus the 5 residual tells with verbatim evidence keys.
- **[KNOW]-sourced**: the *internal* multi-axis logic of RootBeer (9-axis roundup),
  Ruru ("Abnormal Environment" heuristics), and applistdetector (PM-scan classes) —
  the repo contains our *mirror* probes but not these apps' decompiled bytecode
  (`p21-app-coverage-skill` §5 #4 confirms no bytecode disassembly of these).
- **Deployment honesty**: "defeated by Lx" reflects spec + the 3 implemented modules
  proven on JVM snapshots; it is **not** a claim that the live container currently
  passes (`SPOOFSTACK-LIBRARIES` §9 metric note; nothing actively spoofed on live
  device per `p21-app-coverage-skill` §5 #1).
