# B1 — Magisk-rooted ReDroid 12 (hardened NON-privileged) — E2E PROOF

Date: 2026-05-31T12:40:47Z
Branch: session/e2e-2026-05-30
Repo: git@github.com:servas-ai/phantomdroid.git

## Build (B1)
- Tool: ayasa520/redroid-script @ 881f7f00d6a86af4f8e4947af5d587a144a1806c (SHA-pinned public build tool)
- Cmd: python3 redroid.py -a 12.0.0 -m
- Base: redroid/redroid:12.0.0-latest = our pinned digest sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3 (pin-faithful, tagged locally)
- Magisk: v30.6 Kitsune/Delta (io.github.huskydg.magisk fork), apk md5=77ef9f3538c0767ea45ee5c946f84bc6 (matches upstream act_md5)
- Built image: redroid/redroid:12.0.0_magisk  digest sha256:dfed3d9df7bd5ef3b3d61ac975ebaf42dc2e8d6502a6619e00353d8900bc1c29  size 2.23GB (+30.5MB COPY magisk layer)

## Boot (B2 lifecycle — hardened, NON-privileged)
- Launch: build_hardened_run_argv() — cap_drop ALL + 26 bounded caps + device-cgroup-rules + l0b seccomp + apparmor=unconfined + no-new-privileges; NO --privileged
- Privileged flag: false
- boot_completed: 1

## Root verification (LIVE)
```
$ docker exec b2-magisk /sbin/magisk -c
30.6:MAGISK:D (30600)
$ docker exec b2-magisk su -c id
uid=0(root) gid=0(root) groups=0(root)
$ docker exec b2-magisk pm list packages | grep magisk
package:com.topjohnwu.magisk
$ ls /system/etc/init/magisk/
busybox
init-ld
magisk
magisk.apk
magiskboot
magiskinit
magiskpolicy
```

## Significance
Resolves the standing B1 blocker ("needs Magisk binary"). Root (uid=0 via su) is achieved
in a hardened NON-privileged container (Privileged=false), i.e. without the host-root-escape
of --privileged. This is the rooted substrate for B2 L2-L6 sensor/LTE spoof layers.

---

## Adversarial validation (independent sub-agent, 2026-05-31)

**VERDICT: PASS** — reproduced from scratch in a fresh `b1-verify` container (builder's
b2-magisk untouched), then verified the posture is genuinely non-privileged:

- `Privileged` = false; `CapDrop` = ["ALL"]; `CapAdd` = bounded 26-cap set (no CAP_ALL,
  no SYS_RAWIO/MAC_ADMIN); `Devices` = [] (no passthrough); not `--pid=host`; ipc=private.
- `SecurityOpt` seccomp = real l0b JSON with `defaultAction:SCMP_ACT_ERRNO` (enforcing
  allowlist, NOT unconfined) + `no-new-privileges`.
- `su -c id` → uid=0(root); `/sbin/magisk -c` → 30.6:MAGISK:D (30600); root-only write to
  /data/adb → WRITE_OK.
- `git show 5b25c84` → NO SECRETS. Full suite `pytest -q` → 104 passed.

### Honest follow-ups flagged by the validator (do not change PASS, recorded for hardening)
1. **Seccomp is a PROPOSAL artifact.** `agents/stability/stack/seccomp/redroid-seccomp-l0b.json`
   self-labels as "PROPOSAL ARTIFACT, NOT the pinned production profile — board review required
   before promotion." The claim "boots non-privileged with l0b seccomp" is true as reproduced,
   but the profile is not yet board-promoted. FOLLOW-UP: board-review + promote (or pin a
   production profile) before this posture is treated as production-final.
2. **Broad device-cgroup grant.** `DeviceCgroupRules = c *:* rmw / b *:* rmw` grants cgroup-level
   rwm to all device classes. It is NOT --privileged and no devices are mounted (Devices=[]), so
   the non-privileged claim holds, but it is the loosest part of the posture. FOLLOW-UP: narrow to
   the specific device majors ReDroid actually needs (binder/ashmem/etc.).
3. ReDroid's default `docker exec` shell is already uid=0 (adb default), so the load-bearing root
   proof is the functional `su` + live Magisk daemon (30.6) + the /data/adb write — all confirmed.
