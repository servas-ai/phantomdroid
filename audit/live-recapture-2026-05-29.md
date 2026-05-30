# Live ReDroid 12 Recapture — Drift Note vs 2026-05-20

**Date**: 2026-05-29
**Server**: PAR822349 (`paris@195.154.209.133`, Ubuntu 18.04, kernel 4.15.0-213-generic)
**Container**: `redroid-test` (ReDroid 12, image `redroid/redroid`) — **Up 2 days, untouched**
**Method**: read-only `docker exec redroid-test getprop / cat / ls` (no writes, no resetprop/setprop, no container mutation)
**Raw capture**: `p21/live-capture-2026-05-29.txt`
**Replay snapshot**: `p21/redroid-v12-live-2026-05-29.yml`

---

## TL;DR

The live container is byte-for-byte the same probe surface as the 2026-05-20
baseline. Replaying the fresh 2026-05-29 capture through the 65-probe CLI yields
**weightedScore=0.3462, criticalFailures=4, category=DETECTED** — identical to the
2026-05-20 fixture, per-probe scores match exactly. Container is still **Up** and
was not mutated. **One cosmetic drift found** (su path), no impact on detection.

---

## Key probe-relevant props (live 2026-05-29)

| Property | Live value |
|---|---|
| `ro.build.fingerprint` | `redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/eng.frank.20240527.145941:userdebug/test-keys` |
| `ro.build.tags` | `test-keys` |
| `ro.build.type` | `userdebug` |
| `ro.product.brand` | `redroid` |
| `ro.product.model` | `redroid12_x86_64_only` |
| `ro.product.manufacturer` | `redroid` |
| `ro.product.device` | `redroid_x86_64_only` |
| `ro.product.name` | `redroid_x86_64_only` |
| `ro.hardware` | `redroid` |
| `ro.boot.hardware` | `redroid` |
| `ro.product.cpu.abi` | `x86_64` |
| `ro.product.cpu.abilist` | `x86_64,arm64-v8a` (dual-arch / Houdini bridge) |
| `ro.secure` | `1` |
| `ro.debuggable` | `1` (VIOLATION — production must be 0) |
| `ro.bootloader` | `unknown` |
| `ro.kernel.qemu` / `ro.kernel.qemu.gles` | empty |
| `ro.boot.vbmeta.device_state` / `verifiedbootstate` / `flash.locked` | absent (no AVB data) |
| `/proc/version` | `Linux version 4.15.0-213-generic (buildd@lcy02-amd64-079) (gcc 7.5.0 Ubuntu 7.5.0-3ubuntu1~18.04) #224-Ubuntu SMP Mon Jun 19 13:30:12 UTC 2023` |
| `sys.boot_completed` | empty (init still hung — expected, binderfs gap) |
| `init.svc.zygote` | `restarting` (expected) |

---

## Drift vs 2026-05-20

| Field | 2026-05-20 doc | 2026-05-29 live | Impact |
|---|---|---|---|
| **su binary path** | reported `/system/bin/su` present (with "Invalid argument on ls") | `/system/bin/su` **ABSENT**, `/system/xbin/su` **PRESENT** | None on detection — su still present, `root.su_detection` still scores 1.0. Likely a 2026-05-20 path-attribution imprecision: ReDroid ships su at `/system/xbin/su`. |
| fingerprint / tags / type / brand / model / manufacturer / hardware / abilist / secure / debuggable / proc_version | as documented | **identical** | None |
| `/sys/fs/selinux/enforce` | "not readable without privilege" | `No such file or directory` (selinuxfs not mounted in this container view) | None — rank-14 selinux stays confidence-degraded as documented |
| qemu/goldfish device nodes | absent | absent (`/dev/qemu_*` Invalid argument, `/dev/goldfish_*` No such file) | None |
| container uptime | "running, will be stopped after run" | **still Up 2 days** (was NOT stopped) | n/a |

No drift in any load-bearing detection property. The su-path correction is the
only substantive observation and does not change any probe score.

---

## Live detection score (CLI replay, 65 probes)

```
detection-cli run --snapshot p21/redroid-v12-live-2026-05-29.yml
=> weightedScore=0.3462  criticalFailures=4  category=DETECTED
```

Identical to the 2026-05-20 fixture replay (`weightedScore=0.3462, criticalFailures=4, DETECTED`).
Per-probe score diff baseline→live: **NONE (identical)**.

Top-scoring probes (live):

| Score | Probe |
|---|---|
| 1.000 | `root.su_detection` |
| 1.000 | `emulator.cpu_abi` (x86_64 + dual-arch Houdini) |
| 1.000 | `buildprop.tags_and_type` (test-keys + userdebug) |
| 1.000 | `buildprop.model_brand_manufacturer` (redroid12 in model) |
| 1.000 | `buildprop.fingerprint` (redroid marker) |
| 1.000 | `buildprop.board_hardware` (ro.hardware=redroid) |
| 0.950 | `integrity.play_integrity_signals` |
| 0.850 | `env.bootloader` (ro.debuggable=1) |
| 0.850 | `identity.android_id` |
| 0.700 | `emulator.proc_version` (host-kernel leak) |

10 of 65 probes score >= 0.85. Note: `emulator.qemu_artifacts` scores **0.0** —
the `ro.hardware=redroid` signal the 2026-05-20 doc predicted at 1.0 is actually
caught by `buildprop.board_hardware` (1.0), not the qemu-artifacts probe. This is
a doc-prediction imprecision, not a regression; the composite still classifies
DETECTED off 6 probes at 1.0.

---

## Container integrity confirmation

- `docker ps`: `redroid-test` STATUS=**Up 2 days**, IMAGE=`redroid/redroid` — running and untouched.
- All operations were read-only `docker exec ... getprop/cat/ls`. No `setprop`,
  `resetprop`, `rm/stop/kill/restart/run`, no host-fs writes, no package installs.
- Init still hung (`sys.boot_completed` empty, `init.svc.zygote=restarting`) —
  expected per the kernel-4.15 / binderfs gap documented 2026-05-20.
