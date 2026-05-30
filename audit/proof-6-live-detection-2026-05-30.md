# Proof 6 — Live Detection Verification (2026-05-30)

**VERIFICATION slice 6 of the PhantomDroid 100% E2E proof.**
Prove the live container is correctly **DETECTED as emulator** with FRESH evidence.

| Field | Value |
|---|---|
| **Result** | ✅ **PASS** |
| Date | 2026-05-30 |
| Mode | READ-ONLY server (SSH getprop/cat/ls) + LOCAL detection-cli |
| Server | `paris@195.154.209.133` (PAR822349) |
| Container | `redroid-test` (Up 5 hours, `sys.boot_completed=1`) |
| Snapshot | `p21/redroid-v12-live-booted-2026-05-30.yml` (freshly authored this run) |
| Detection CLI | `agents/detection-cli/build/install/detection-cli/bin/detection-cli` v0.1.0 |
| **weightedScore** | **0.3462** (target ~0.346 ✅) |
| **category** | **DETECTED** ✅ |
| **criticalFailures** | **4** (target 4 ✅) |
| **probes @ score 1.0** | **6** (target ≥6 ✅) |
| Server writes | **NONE** — only `getprop`, `cat /proc/version`, `ls -la`, `pm list` were issued ✅ |

---

## 1. Fresh evidence capture (READ-ONLY, no server writes)

All capture done via `sshpass -p '***' ssh paris@195.154.209.133 'docker exec redroid-test <read-only cmd>'`.
No file was created, modified, or deleted on the server. No `docker run`, `cp`, `resetprop`, or `setprop`.

### Boot state (live)
```
sys.boot_completed=1
init.svc.zygote=running
hwservicemanager.ready=true
service.bootanim.exit=1
```

### Build / fingerprint (rank 1)
```
ro.build.fingerprint=redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/eng.frank.20240527.145941:userdebug/test-keys
ro.build.display.id=redroid_x86_64_only-userdebug 12 SP1A.210812.016.C2 eng.frank.20240527.145941 test-keys
ro.build.tags=test-keys
ro.build.type=userdebug
ro.build.version.release=12
ro.build.version.sdk=31
```

### Identity (rank 9)
```
ro.product.brand=redroid
ro.product.model=redroid12_x86_64_only
ro.product.manufacturer=redroid
ro.product.device=redroid_x86_64_only
ro.product.name=redroid_x86_64_only
```

### Hardware / QEMU (rank 28 / rank 4)
```
ro.hardware=redroid
ro.product.board=          (empty)
ro.board.platform=         (empty)
ro.kernel.qemu=            (empty)
ro.kernel.qemu.gles=       (empty)
ro.boot.hardware=redroid
ro.bootloader=unknown
```

### CPU / ABI (rank 27) — DUAL-ARCH (Houdini bridge)
```
ro.product.cpu.abi=x86_64
ro.product.cpu.abilist=x86_64,arm64-v8a
ro.product.cpu.abilist32=  (empty)
ro.product.cpu.abilist64=x86_64,arm64-v8a
```

### Secure / debuggable / bootloader (rank 13)
```
ro.secure=1
ro.debuggable=1
ro.boot.vbmeta.device_state=   (empty)
ro.boot.verifiedbootstate=     (empty)
ro.boot.flash.locked=          (empty)
```

### /proc/version (host-kernel leak)
```
Linux version 5.4.0-150-generic (buildd@bos03-amd64-012) (gcc version 7.5.0
(Ubuntu 7.5.0-3ubuntu1~18.04)) #167~18.04.1-Ubuntu SMP Wed May 24 00:51:42 UTC 2023
```

### su file (rank 3)
```
-rwsr-x--- 1 root shell 7688 2024-05-27 15:00 /system/xbin/su   (setuid root, present)
/system/bin/su                                                   (absent)
```

The probe-relevant build/identity surface is **byte-for-byte identical** to the
2026-05-29 booted capture; the host kernel string is unchanged
(`5.4.0-150-generic`). Snapshot was authored fresh for this run with the
2026-05-30 capture timestamp.

---

## 2. Detection CLI run (LOCAL)

```bash
$ ./agents/detection-cli/build/install/detection-cli/bin/detection-cli \
    run --snapshot p21/redroid-v12-live-booted-2026-05-30.yml \
    --output /tmp/proof6-report.json

detection-cli: wrote 81175 bytes to /tmp/proof6-report.json | \
  weightedScore=0.3462 criticalFailures=4 category=DETECTED
```

### Aggregate (real output)
```json
{
  "weightedScore": 0.3461764705882353,
  "criticalFailures": 4,
  "category": "DETECTED"
}
```

Report metadata:
```json
{
  "schemaVersion": "1.0",
  "appVersion": "0.1.0",
  "deviceLabel": "redroid-12-amd64-live-booted-2026-05-30",
  "timestamp": "2026-05-30T00:18:02.384859071Z"
}
```

65 probes ran; 24 scored > 0.

---

## 3. The 4 CRITICAL failures (rank 1–10, score ≥ 0.7)

Per `ProbeRunner.aggregate()`: `criticalFailures` counts probes with `rank in 1..10 && score >= 0.7`.
Category is forced to `DETECTED` when `criticalFailures >= 3` (here: 4).

| rank | probe id | score |
|---|---|---|
| 1 | `buildprop.fingerprint` | 1.0 |
| 3 | `root.su_detection` | 1.0 |
| 7 | `buildprop.tags_and_type` | 1.0 |
| 9 | `buildprop.model_brand_manufacturer` | 1.0 |

---

## 4. The 6 probes at score 1.0

```
1.0  root.su_detection
1.0  emulator.cpu_abi
1.0  buildprop.tags_and_type
1.0  buildprop.model_brand_manufacturer
1.0  buildprop.fingerprint
1.0  buildprop.board_hardware
```

Exactly 6 probes maxed out (≥6 target met).

---

## 5. Top scoring probes (score > 0)

| score | probe id |
|---|---|
| 1.0 | root.su_detection |
| 1.0 | emulator.cpu_abi |
| 1.0 | buildprop.tags_and_type |
| 1.0 | buildprop.model_brand_manufacturer |
| 1.0 | buildprop.fingerprint |
| 1.0 | buildprop.board_hardware |
| 0.95 | integrity.play_integrity_signals |
| 0.85 | identity.android_id |
| 0.85 | env.language_country |
| 0.85 | env.bootloader |
| 0.7 | ui.input_method |
| 0.7 | identity.sim_iccid |
| 0.7 | identity.imei_serial |
| 0.7 | emulator.proc_version |
| 0.5 | ui.system_fonts / ui.screen_resolution / sensors.accelerometer_gyro / runtime.debugger_tracerpid / network.dns_server / identity.wifi_mac |

### Key evidence behind top critical probes

`buildprop.fingerprint` (score 1.0):
- `ro.build.tags=test-keys` (expected `release-keys`)
- `ro.build.type=userdebug` (expected `user`)
- fingerprint string contains `redroid/.../eng.frank...:userdebug/test-keys`

`emulator.cpu_abi` (score 1.0): dual-arch x86_64 primary + arm64-v8a bridge —
`cpu.has_dual_arch=true` (expected false), `cpu.pattern=x86_primary` (expected `clean_arm`).

`root.su_detection` (score 1.0): `/system/xbin/su` present (expected absent).

`emulator.proc_version` (score 0.7): host kernel `5.4.0-150-generic`,
`proc_version.kernel_name=other` (expected `android`), `compiler=gcc`
(expected clang), build host `bos03-amd64-012`.

---

## 6. Verdict

✅ **PASS** — The live booted `redroid-test` container is correctly classified
**DETECTED** as an emulator from FRESH 2026-05-30 read-only evidence:

- weightedScore = **0.3462** (matches target ~0.346)
- criticalFailures = **4** (matches target 4)
- probes at score 1.0 = **6** (meets target ≥6)
- category = **DETECTED**
- No server writes performed (read-only getprop/cat/ls/pm only).

Artifacts:
- Snapshot: `p21/redroid-v12-live-booted-2026-05-30.yml`
- Full report JSON: `/tmp/proof6-report.json` (81175 bytes)
