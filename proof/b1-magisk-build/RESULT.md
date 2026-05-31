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
