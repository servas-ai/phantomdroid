# Phase 4 — The REAL Magisk-in-ReDroid Root Method (L0b unblock research)

**Date:** 2026-05-29
**Author:** Researcher teammate (PhantomDroid)
**Status:** RESEARCH ONLY — no server mutation performed.
**Baseline under protection:** live `redroid-test` on PAR822349 — untouched. All work targets a NEW throwaway container `l0b-probe`.

> **HEADLINE VERDICT: 🟡 YELLOW — owner sign-off required before autonomous execution.**
> The method is real, documented, reproducible. NOT GREEN because (a) it pulls third-party code (a community build script + a third-party-built Magisk APK fork) = supply-chain decision, and (b) the upstream recipe assumes `--privileged`, conflicting with the repo's hardened posture (`cap_drop:[ALL]` + `redroid-seccomp.json`). Both resolvable; both need explicit owner approval.

## 1. The canonical method
The repo runbooks failed because they assumed `pm install + Direct Install` (patches a boot.img ramdisk ReDroid lacks → Manager UI only, no daemon). The REAL boot-imageless method: **`bootanim.rc` init-service hijack + `magisk --setup-sbin`, baked into a locally-built image**:
1. At image-BUILD time, inject Magisk binaries under `/system/etc/init/magisk/` and rewrite `/system/etc/init/bootanim.rc`.
2. Rewritten bootanim.rc runs (under `u:r:su:s0`): `magisk --auto-selinux --setup-sbin …` + post-fs-data/service/boot-complete hooks + `pm install` of the APK + populates `/data/adb/magisk`.
3. `magisk --setup-sbin` mounts a tmpfs at `/sbin` and lays the Magisk overlay — the daemon bootstrap replacing ramdisk magiskinit.
4. Committed as a NEW local image `redroid/redroid:12.0.0-magisk`. This is the "install-to-/system mode" hinted at `power-19-magisk-variants.md:18`.

### Source tiers
| Source | Tier | Notes |
|---|---|---|
| `remote-android/redroid-doc` issue #207 "Magisk support" | (a) maintainer-acknowledged | Official project points to community script; ships no rooted image itself |
| `ayasa520/redroid-script` (+ `abing7k/redroid-script`) | (b) well-known community tool | De-facto standard; builds rooted image LOCALLY from official base; open-source, inspectable; supports 12.0.0_64only. Mechanism verified from `stuff/magisk.py`+`redroid.py` |
| Magisk APK it downloads = `ayasa520/Magisk v30.6 app-debug` (Magisk Delta / HuskyDG lineage) | (b/c) third-party-built binary | **The supply-chain risk.** NOT topjohnwu official. MD5-pinned (integrity, not provenance). Owner decides: accept fork OR repoint to official topjohnwu |
| Prebuilt Docker Hub rooted images (`fahaddz/redroid`, etc.) | (c) untrusted | **DO NOT PULL.** Opaque rootfs as Android system on our host. Build-it-yourself avoids this entirely — strongly preferred |

Cited: github.com/remote-android/redroid-doc (#207, #177); github.com/ayasa520/redroid-script (+ issue #28); github.com/abing7k/redroid-script; github.com/topjohnwu/Magisk (recommended provenance).

## 2. Build command (LOCAL, no untrusted prebuilt pull) — targets new `l0b-probe`
```bash
git clone https://github.com/ayasa520/redroid-script.git && cd redroid-script
git checkout <REVIEWED_SHA>          # owner reviews diff + pins
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
python3 redroid.py -a 12.0.0 -m      # downloads 3rd-party Magisk APK (§1 risk), emits local redroid/redroid:12.0.0-magisk
```

## 3. Load chain + seccomp survival
- Boot rooted image → hijacked bootanim runs `magisk --setup-sbin` → **magiskd starts (the P2 root gate the old runbook could never pass)**.
- Zygisk OFF by default in Delta → enable via `magisk --sqlite "REPLACE INTO settings(key,value) VALUES('zygisk',1)"` + reboot → then LSPosed flashes as a Zygisk module.

| Component | Seccomp survival (`redroid-seccomp.json`) |
|---|---|
| `magisk --setup-sbin` tmpfs mount | **⚠️ AT RISK** — profile allows `mount` only with MS_BIND; tmpfs is non-bind → may EPERM. **MUST test on l0b-probe first** |
| cpuinfo-overlay (bind mount) | ✅ allowed |
| spoof-stack-magisk (resetprop, sysfs binds) | ✅ compatible |
| Magisk DenyList + standard Zygisk (topjohnwu line) + LSPosed | ✅ compatible |
| **NeoZygisk/ReZygisk (ptrace-init)** | **❌ BLOCKED** — profile restricts ptrace to PTRACE_TRACEME; ATTACH/PEEK/POKE EPERM. So **hide-frida-maps stays blocked** → ship 2 of 3 modules |

## 4. Verdict: 🟡 YELLOW — owner approval required
Not GREEN because: (1) pulls third-party code (redroid-script + Magisk Delta APK fork, not official topjohnwu); (2) upstream uses `--privileged` vs repo `cap_drop:[ALL]`+seccomp; (3) unproven `--setup-sbin` tmpfs-vs-seccomp survival. Not RED because the mechanism is real, reputable, open-source, reproducible without any untrusted prebuilt image.

**YELLOW → GREEN:** owner pins a reviewed redroid-script SHA + rules on Magisk APK provenance (accept Delta fork OR repoint to topjohnwu) + rules on `--privileged` vs scoped-caps for l0b-probe; then l0b-probe boots and proves P2 (`magisk --version` returns a daemon version).

## 5. Executor sequence (AFTER owner sign-off; targets l0b-probe; baseline untouched)
Build (§2) → `docker run -itd --name l0b-probe [--security-opt seccomp=… no-new-privileges] OR [--privileged if A fails] -p 127.0.0.1:15556:5555 <rooted-img>` → `adb connect 127.0.0.1:15556` → `magisk --version` (P2 gate) → enable zygisk + reboot. Rollback: `docker rm -f l0b-probe && rm -rf /tmp/l0b-probe-data`.

## 6. Honest caveats
- bootanim.rc + `--setup-sbin` mechanism verified from primary source (redroid-script `magisk.py`/`redroid.py`).
- `--setup-sbin` tmpfs-vs-seccomp survival is an INFERENCE — must be empirically tested on l0b-probe before any GREEN claim.
- NeoZygisk ptrace incompatibility documented by the seccomp profile's own comments — high confidence.
- The Magisk APK is a third-party Delta fork, not topjohnwu official — the load-bearing fact behind YELLOW.
