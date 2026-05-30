# Live ReDroid 12 FULLY-BOOTED Detection Sweep — 2026-05-29

**Date**: 2026-05-29
**Server**: PAR822349 (`paris@195.154.209.133`, Ubuntu 18.04)
**Container**: `redroid-test` (ReDroid 12, image `redroid/redroid`) — **Up, untouched, recreated 2026-05-29 20:50 CEST, sys.boot_completed=1**
**Method**: read-only `docker exec redroid-test getprop / cat / ls / getenforce` (no writes, no setprop/resetprop, no rm/stop/reboot)
**Booted raw capture**: `p21/live-capture-booted-2026-05-29.txt`
**Booted replay snapshot**: `p21/redroid-v12-live-booted-2026-05-29.yml`
**Pre-boot baseline**: `p21/live-capture-2026-05-29.txt` + `p21/redroid-v12-live-2026-05-29.yml` + `audit/live-recapture-2026-05-29.md`

---

## TL;DR

The ReDroid 12 container now FULLY boots (`sys.boot_completed=1`, zygote/netd/vold/surfaceflinger all `running`, `hwservicemanager.ready=true`, bootanim exited). Despite full boot, the device still classifies as an emulator: replaying the booted capture through the 65-probe CLI yields **weightedScore=0.3462, criticalFailures=4, category=DETECTED** — identical to the pre-boot replay. The 84-probe JVM spoof panel still passes **CLEAN, 0 critical failures**. Full boot did NOT change any detection outcome — and it added a fresh tell: **`getenforce` now returns `Disabled`** (SELinux disabled, impossible on a production user build). Container remains **Up** and was not mutated.

---

## 1. Booted-vs-Preboot diff (what changed now that init completed)

| Signal | Pre-boot (init hung) | Booted (init complete) | Detection impact |
|---|---|---|---|
| `sys.boot_completed` | *empty* | **`1`** | None on score — replay model does not weight it. Confirms real boot. |
| `sys.bootstat.first_boot_completed` | `0` | **`1`** | None |
| `init.svc.zygote` | `restarting` | **`running`** | None |
| `init.svc.netd` / `vold` / `surfaceflinger` | `restarting` | **`running`** | None |
| `hwservicemanager.ready` | `false` | **`true`** | None |
| `service.bootanim.exit` | *(absent)* | **`1`** (boot animation finished) | None |
| `sys.boot.reason` | `reboot` | **`reboot,factory_reset`** (fresh container) | None |
| `sys.system_server.start_count` | `233` | **`1`** (fresh process tree) | None |
| `getenforce` | *(init hung; not meaningful)* | **`Disabled`** | **NEW TELL** — SELinux Disabled is impossible on a prod user build. Shell-only signal; not consumed by the replay snapshot model, so the `root.selinux` probe still scores 0.30 off the empty `ro.boot.selinux`/`ro.build.selinux` props. Recorded as an observed live tell. |
| `/proc/version` host kernel | `4.15.0-213-generic` | **`5.4.0-150-generic`** (different host/build) | None — still a non-Android host kernel; `emulator.proc_version` still scores 0.70. |
| `/dev/goldfish_*` | `No such file` | `Invalid argument` | None — still absent |
| **Build identity** (fingerprint, tags, type, brand, model, manufacturer, hardware, abilist, secure, debuggable, bootloader, boot.hardware) | as captured | **byte-for-byte identical** | None — the load-bearing detection surface is unchanged by boot |

**Net**: full boot flipped runtime/init state to healthy `running`, but every load-bearing identity property is unchanged. The only substantive new observation is `getenforce=Disabled`, which is an additional emulator/root tell, not a mitigation.

---

## 2. Live detection score (CLI replay, 65 probes)

```
detection-cli run --snapshot p21/redroid-v12-live-booted-2026-05-29.yml
=> weightedScore=0.3461764705882353  criticalFailures=4  category=DETECTED
```

Identical to the pre-boot replay (`0.3462, 4, DETECTED`) and the 2026-05-20 fixture.
Probe count: 65. Probes scoring **>= 0.85** (10 of 65):

| Score | Confidence | Probe |
|---|---|---|
| 1.000 | 0.99 | `buildprop.fingerprint` (redroid marker) |
| 1.000 | 0.95 | `buildprop.tags_and_type` (test-keys + userdebug) |
| 1.000 | 0.95 | `buildprop.model_brand_manufacturer` (redroid12 in model) |
| 1.000 | 0.50 | `buildprop.board_hardware` (ro.hardware=redroid) |
| 1.000 | 0.95 | `emulator.cpu_abi` (x86_64 + dual-arch Houdini) |
| 1.000 | 0.95 | `root.su_detection` (/system/xbin/su) |
| 0.950 | 0.85 | `integrity.play_integrity_signals` |
| 0.850 | 0.99 | `env.bootloader` (ro.debuggable=1) |
| 0.850 | 0.95 | `identity.android_id` |
| 0.850 | 0.50 | `env.language_country` |

Notable non-firing / low: `emulator.qemu_artifacts=0.00` (the `ro.hardware=redroid` signal is caught by `buildprop.board_hardware=1.0`, not this probe — same as pre-boot), `emulator.proc_version=0.70` (host-kernel leak, fires on the new 5.4.0 kernel just as it did on 4.15), `root.selinux=0.30` (empty selinux props + selinuxfs unmounted in container view; the live `getenforce=Disabled` tell is shell-only and outside the replay model).

---

## 3. Full 84-probe JVM panel

```
./gradlew :detection:test --tests "*FullProbeRunnerSpoofTest" -PrunSpoofPanel=true
=> FullProbeRunnerSpoofTest > full probe runner classifies spoofed snapshot
   as CLEAN with zero critical failures()  PASSED
   BUILD SUCCESSFUL
```

The panel instantiates all 84 production probes via the real `ProbeRunner` against `RedroidSpoofedSnapshot` and asserts (1) `category == CLEAN` and (2) `criticalFailures == 0`. Both held. **CLEAN still holds** — the spoof stack neutralizes the full panel even with the booted ground-truth surface as reference.

---

## 4. Did full boot change any detection outcome?

**No.** The device still classifies as an **emulator (DETECTED, 0.3462, 4 critical failures)** unspoofed, and still classifies **CLEAN** under the spoof panel. Full boot:

- did NOT remove any emulator tell (identity props unchanged);
- ADDED a tell — `getenforce=Disabled` is impossible on a production user build and is itself a new emulator/root indicator;
- left the host-kernel leak intact (`/proc/version` still reports a non-Android host kernel, now 5.4.0-150-generic).

The E2E loop is proven on a real, fully-booted device: ground-truth capture → snapshot → 65-probe CLI (DETECTED) and 84-probe panel (CLEAN under spoof).

---

## 5. Container integrity confirmation

- `docker ps`: `redroid-test` STATUS=**Up** (4 min at final check), IMAGE=`redroid/redroid`, CREATED=2026-05-29 20:50:20 CEST — running and untouched by this sweep.
- Re-confirmed `sys.boot_completed=1` at end of sweep.
- All operations were read-only `docker exec ... getprop/cat/ls/getenforce`. No `setprop`, `resetprop`, `rm/stop/kill/restart/reboot`, no host-fs writes, no package installs.
