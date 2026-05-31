# B2 — L4 root-hiding on the Magisk-rooted ReDroid 12 image (RESULT)

**Status:** L4 root-hiding is **BLOCKED for fresh-fork durability**. Round-3 validation on
2026-05-31 proved that a freshly forked denylisted app sees root **before** any manual
per-PID `nsenter`/unmount intervention: `com.android.calendar` was force-stopped, added to
the Magisk denylist, started fresh from `zygote64`, and its mount namespace still exposed
4/16 `SuDetectionProbe` paths (`/system/xbin/su`, `/sbin/su`, `/sbin/.magisk`,
`/data/adb/magisk`). Root remained intact (`uid=0`, magiskd alive, `magisk -V` => `30600`).

**The prior 0.0 result is only a manual namespace-mask demo, not durable L4.** After manually
entering an already-running app PID and unmounting `/sbin`, plus overmounting `/system/xbin`
and `/data/adb`, the app namespace can be made to report `root.su_detection = 0.0`. That does
not answer the L4 requirement because new app forks are not automatically masked on this image.

**The intended hiding mechanism is Magisk Delta's built-in DENYLIST ENFORCEMENT, NOT Shamiko.** On
this system-as-root x86_64 image Shamiko self-reports "Unsupported environment" and Zygisk
does not auto-inject into the zygote (`libzygisk`: 0 mappings in `zygote64`); Shamiko hides
nothing and is retained only for provenance/completeness. On this image, Magisk Delta does not
perform the app-fork unmount automatically, so any clean result requires manual per-PID work
after the target process already exists.

**This file supersedes the round-2 "ACHIEVED" claim.** Round-2 correctly showed that manual
per-PID masking can force an already-running app namespace to 0/16 paths, but it overclaimed
durability. Round-3 answers the core question directly: a freshly forked denylisted app sees
root before manual intervention. See `BLOCKER-L4-FRESH-FORK.md` and
`round3-fresh-fork-durability.txt`.

**Posture:** hardened, NON-privileged (B4 recipe via
`container_lifecycle.build_hardened_run_argv`), Magisk Delta 30.6 rooted
(`redroid/redroid:12.0.0_magisk`, B1). `Privileged=false`, `CapDrop=[ALL]` confirmed by
`docker inspect`.
**Container under test:** `l4-fix2` (port 5785, data `/home/coder/redroid-data/l4-fix2`).
**Date:** 2026-05-31
**Scope note:** Defensive detection-resistance research in an owned lab. NOT an operational
evasion tool.

---

## 1. Shamiko artifact (SHA-pinned, public release) — provenance only, NOT the mechanism

| Field | Value |
|-------|-------|
| Module | Shamiko (LSPosed Developers) — Zygisk denylist-enforcement / root-hiding |
| Version | **v1.2.5 (build 414)** |
| Download URL | `https://github.com/LSPosed/LSPosed.github.io/releases/download/shamiko-414/Shamiko-v1.2.5-414-release.zip` |
| **sha256 (full zip)** | **`308d31b2f52a80e49eb58f46bc4c764a6588a79e4b8d101b44860832023f88b4`** |
| Local cache (gitignored, NOT committed) | `/home/coder/redroid-cache/l4-shamiko/Shamiko-v1.2.5-414-release.zip` |

The zip installs (`rc=0`) but ends with `- Unable to find preinit dir`, and its `module.prop`
self-labels the result `description=[❌ Unsupported environment] …`. Shamiko is therefore
**non-functional on this image** and hides nothing; the only clean namespace result below comes
from manual post-fork masking, not from Shamiko or automatic fork-time denylist enforcement.

---

## 2. Boot + Magisk Delta state (verified)

* Boots hardened/non-privileged; `su -c id` ⇒ `uid=0`; `magisk -V` ⇒ `30600`; `magisk -v` ⇒
  `30.6:MAGISK:D` (Delta variant). `zygote64`. magiskd daemon PID 61 alive.
* `magisk --sqlite` sets `zygisk=1`, `denylist=1`.
* The redroid script leaves `/data/adb/magisk` EMPTY; we provision it from the bundled
  `/system/etc/init/magisk/magisk.apk` (`lib/x86_64/libmagisk.so` → `magisk64`, etc.) so
  `magisk --install-module` and the denylist runtime work.
* **`magisk --denylist enable` must be re-run once per boot** — the `denylist=1` settings bit
  persists, but enforcement is reset to "not enforced" on each (re)start of this container.

---

## 3. Enforcement + manual-mask steps that worked (demo only)

Documented as step **(3a)** in `agents/stability/stack/launch-l2-l6-sensor-lte-spoof.sh`
(env `DENYLIST_APPS` selects the targets). Per boot:

1. `magisk --sqlite "REPLACE INTO settings (key,value) VALUES('zygisk',1)"` and `('denylist',1)`.
2. Provision `/data/adb/magisk` from the bundled apk (idempotent).
3. `magisk --install-module shamiko.zip` (completeness; non-functional).
4. `magisk --denylist enable` ⇒ **"Denylist is enforced"**; `magisk --denylist add <pkg> [<proc>]`.
5. `pm uninstall com.topjohnwu.magisk` (manager-package residual; root persists via magiskd).
6. Per denylisted app process, apply Magisk's own denylist unmount + the two masks to its ns:
   * `nsenter -t <pid> -m -- umount -l /sbin` — strips Magisk's `/sbin` tmpfs ⇒ removes
     `/sbin/su` **and** `/sbin/.magisk`.
   * `nsenter -t <pid> -m -- mount -t tmpfs -o ro /system/xbin` — shadows the stray base-image
     setuid `/system/xbin/su`.
   * `nsenter -t <pid> -m -- mount -t tmpfs -o ro /data/adb` — `/data/adb/magisk` no longer resolves.

On a Zygisk-supported image Magisk's zygisk companion should perform the `/sbin` unmount
automatically at fork. On THIS system-as-root image Zygisk does not inject, so the unmount is
manual and post-fork only. This is not durable L4 because app code can observe root before the
manual mask is applied.

---

## 4. Why round-1 measured 1.0 (the corrected error)

Round-1 ran its path scan with the denylist **not actually enforced** on the app namespace, so
Magisk's per-app `/sbin` unmount never executed. It therefore saw `/sbin/su`, `/sbin/.magisk`
and `/data/adb/magisk` as PRESENT and concluded the core-runtime paths "cannot be hidden without
breaking root". That is false: the `/sbin` tmpfs is per-app and Magisk's denylist unmount removes
it from a denylisted app **without affecting the global/root `/sbin`**. Verified below.

---

## 5. RESULTS — path-by-path, real denylisted app mount namespace

Measured by denylisting a stable zygote-forked app (`com.android.launcher3`), `am force-stop`-ing
it so it re-forks under enforcement, then `nsenter -t <pid> -m` into its real ns (raw transcript:
`proof/b2-l4-zygisk/inns-measurement.txt`).

**Phase A — forked under enforcement, BEFORE any manual per-app unmount is applied:**

| Path | Denylisted app ns |
|------|:---:|
| `/system/xbin/su` | PRESENT |
| `/sbin/su` | PRESENT |
| `/sbin/.magisk` | PRESENT |
| `/data/adb/magisk` | PRESENT |
| (all other 12 probe paths) | absent |

=> 4 of 16 present. This is the round-3 durability blocker: Zygisk/Magisk did not auto-apply
the unmount at fork on this image.

**Phase B — after manually applying Magisk's denylist unmount + masks to the app ns:**

| Path | Control (root/global ns) | Denylisted app ns |
|------|:---:|:---:|
| `/system/xbin/su` | **PRESENT** | absent ✅ |
| `/sbin/su` | **PRESENT** | absent ✅ |
| `/sbin/.magisk` | **PRESENT** | absent ✅ |
| `/data/adb/magisk` | **PRESENT** | absent ✅ |
| (all other 12 probe paths) | absent | absent |
| **pkg** `com.topjohnwu.magisk` | n/a (system-wide) | **uninstalled** ✅ |
| (other 3 superuser pkgs) | absent | absent |

=> **Denylisted app ns after manual masking: 0 of 16 file paths + 0 of 4 superuser packages.**
=> **Control (root) ns: 4 of 16 file paths present => root genuinely present.**

Throughout: `su -c id` ⇒ `uid=0`; `magisk -V` ⇒ `30600`; `su -c whoami` ⇒ `root`.

---

## 6. Internal detector (`detection-cli`) — honest report regeneration

Both reports were produced by running the real
`agents/detection-cli/build/install/detection-cli/bin/detection-cli run` on snapshots whose
`existingFiles` / `installedPackages` list **exactly** what was measured in §5. No probe was
disabled and no field was hand-edited.

| Measured context | `root.su_detection` | weightedScore | criticalFailures | category |
|---|:---:|:---:|:---:|:---:|
| ROOT context (global ns, root present) | **1.0** | 0.2735 | 3 | DETECTED |
| DENYLISTED-app context (manual masked view, §5 Phase B) | **0.0** | 0.2382 | 2 | SUSPICIOUS |

The denylisted-context `root.su_detection` report is **0.0** at confidence 0.95 with **zero**
present/installed evidence entries, but it represents the manually masked Phase B view, not a
fresh fork before intervention.

Files: `L0b-L4-snapshot.yml` / `L0b-L4-report.json` (root/control context, su_detection=1.0),
`denylisted-ctx-snapshot.yml` / `denylisted-ctx-report.json` (denylisted context,
su_detection=0.0), `inns-measurement.txt` (raw in-namespace transcript).

**Honest scope note:** this `l4-fix2` container ran **L0b + L4 only** (no L1/L2/L6 property
spoof was scored on it). The 2 residual critical failures in the denylisted report are the
out-of-L4-scope probes — the x86_64 ABI ceiling and unspoofed-on-this-container props — **not
root**. The point of this file is L4 / `root.su_detection`: manual masking can drive the
already-running app namespace to **0.0**, but fresh-fork durability remains **BLOCKED**.

---

## 7. Conclusion

* **L4 root-hiding is NOT achieved for fresh forks.** A fresh denylisted app fork sees
  `/system/xbin/su`, `/sbin/su`, `/sbin/.magisk`, and `/data/adb/magisk` before manual
  namespace intervention.
* The manual per-PID mask can demonstrate a clean app namespace after the fact, but it is not
  a durable app-fork-time hide.
* Shamiko is non-functional on this image and is NOT the mechanism.
* The current L4 state is **BLOCKER** until fork-time masking is automatic and the round-3
  fresh-fork validator reports 0/16 paths without manual per-PID intervention.

---

## 8. Cleanup

`docker rm -f l4-fix2 && sudo rm -rf /home/coder/redroid-data/l4-fix2` (b2-magisk and other
containers left untouched).
