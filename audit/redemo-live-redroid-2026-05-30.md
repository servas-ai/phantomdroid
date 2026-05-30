# Re-Demonstration: Live ReDroid 12 boot + detection — 2026-05-30

Re-proof of the live functional slices (Slice 5 boot, Slice 6 detection) on FRESH
infrastructure, plus correction of two stale assumptions. Defensive research only.

## Summary

| Target | Kernel | Boot | Detection (detection-cli) | Evidence |
|---|---|---|---|---|
| **Server PAR822349** (`195.154.209.133`) | 5.4.0-150-generic | `sys.boot_completed=1`, zygote running, 96 pkgs | `weightedScore=0.3462`, criticalFailures=4, **DETECTED** | `p21/redroid-v12-live-booted-2026-05-30-server-par822349.yml`, `p21/live-server-par822349-report.json` |
| **Local host** | 6.8.0-117-generic | `sys.boot_completed=1`, container `l0a-diag2` | `weightedScore=0.3379`, criticalFailures=4, **DETECTED** | `p21/redroid-v12-live-booted-2026-05-30-k68.yml`, `p21/live-k68-report.json` |

Both targets DETECTED. The server (5.4) reproduces the **original Slice-6 value 0.3462 exactly**;
the local 6.8 boot differs only by the `/proc/version` probe (0.3379). Identity/build surface
is byte-for-byte identical across both (same pinned image digest `e6f799d5…`).

## Server access (corrects "creds stale")

SSH to PAR822349 was VERIFIED LIVE on 2026-05-30 — the prior "creds stale" memo was wrong:
```
sshpass -p '<reinstall-pw>' ssh paris@195.154.209.133   # paris in sudo group; root SSH disabled
```
`.env`'s `<panel-login-redacted>` is the op-net web-panel login ONLY (not SSH). The OB1 reboot that was
the prior blocker has happened: the host is now on kernel 5.4.0-150 and `redroid-test` is fully booted.

## How the server emulator is launched (`docker inspect redroid-test`)

```
Image:      redroid/redroid@sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3
Privileged: true
CapAdd:     []        Devices: (none)
Binds:      /data/redroid-test:/data
Ports:      127.0.0.1:5555 -> 5555/tcp
Args:       qemu=1 androidboot.hardware=redroid androidboot.redroid_gpu_mode=guest
            androidboot.redroid_google_play_store=0
```

## binderfs self-mount — kernel-version-independent

Both hosts are binderfs-only (binder is a MODULE; `/dev/binder` does not exist):
- Server 5.4: `CONFIG_ANDROID_BINDER_IPC=m`, `CONFIG_ANDROID_BINDERFS=m`, `CONFIG_ANDROID_BINDER_DEVICES=""`, only `/dev/ashmem` present.
- Local 6.8: same module posture, no ashmem (ReDroid 12 uses memfd).

**Working pattern (both):** `privileged: true`, NO `--device` binder mappings, NO cap_add — ReDroid
self-mounts its own binderfs internally. Mapping host binderfs nodes shares the host binder context
and breaks the HIDL stack (`Binder driver could not be opened. Terminating.`). The earlier belief
that "5.4 = host binder device passthrough" was from a different/older host, not PAR822349.

## Honest limitation — hardened L0a posture on binderfs-only kernels

The committed hardened profile (`agents/stability/stack/compose/L0a.yml`: `cap_drop: ALL` + custom
seccomp `redroid-seccomp.json` + `no-new-privileges`, NON-privileged) does **not** boot ReDroid on a
binderfs-only kernel: the custom seccomp blocks a syscall the binderfs self-mount needs (Exited 127),
and AppArmor blocks the mount unless `apparmor=unconfined`. This is a **host-kernel-dependent
limitation, not a defect of the committed work**. Both successful live boots here are PRIVILEGED.
Re-tuning the seccomp profile overlaps the owner-gated `redroid-seccomp-l0b.json` board-review item.

## Capture method (read-only)

Server: `ssh paris@…` then `docker exec redroid-test getprop <key> | cat /proc/version | ls -la /system/xbin/su | pm list packages | wc -l`.
Local: `docker exec l0a-diag2 …` equivalents. Detection: `detection-cli run --snapshot <yml> -o <json>`
(JAVA_HOME=java-17). No spoof/Magisk applied — these are the irreducible unspoofed L0a baseline.

## Owner-gated items still pending (NOT done autonomously)

1. Push branch `session/e2e-2026-05-30`.
2. Credential history purge + rotation (SSH password present in git history blob `896cd71`; HEAD tree clean).
3. Promote / board-review `redroid-seccomp-l0b.json` (overlaps the hardened-on-binderfs limitation above).
