# Phase 4 — L0b Build-out Plan & Feasibility (spoof-delta re-probe)

**Date:** 2026-05-29
**Author:** Researcher teammate (PhantomDroid)
**Status:** PREP/RESEARCH ONLY — no server mutation, no docker run/install, no module flashing performed.
**Baseline under protection:** live container `redroid-test` on PAR822349 (`paris@195.154.209.133`), DETECTED, weightedScore 0.3462, 4 critical failures (`audit/live-booted-sweep-2026-05-29.md:15,44`).

> **Headline verdict: RED — BLOCKED.** Neither `agents/stability/stack/L0b-RUNBOOK.md` nor `agents/stability/stack/L1-MAGISK-RUNBOOK.md` contains a *working* method to give a stock ReDroid 12 image runtime Magisk root. Both runbooks assume `pm install Magisk.apk` + `setup-direct-install` yields root (`L0b-RUNBOOK.md:83-89`, `L1-MAGISK-RUNBOOK.md:138-151`). That sequence patches a boot image's ramdisk on a real device — **ReDroid has no boot.img/ramdisk to patch**, so it does not root the container. The actual mechanism ("install-to-`/system` mode for boot-imageless emulators") is named only once, in passing, in `audit/spoof-stack/power-19-magisk-variants.md:18`, and is **not documented as a procedure anywhere in the repo.**

---

## 1. What each of the 3 in-house modules requires

| Module | Path | Magisk? | Zygisk/ReZygisk? | LSPosed? | init/early injection? | Status in tree |
|---|---|---|---|---|---|---|
| **cpuinfo-overlay** | `agents/stability/stack/modules/cpuinfo-overlay/` | Yes (Magisk module) | No | No | `late_start_service` bind-mount of synthetic `/proc/cpuinfo` | Complete |
| **hide-frida-maps** | `stack/L4/hide-frida-maps/` | Yes (under root mgr) | **Yes — NeoZygisk + Vector v2.0** | Xposed/Vector host needed | runtime `/proc/<pid>/maps` hook | **Skeleton only — NOT functional** (`README.md:8-12`) |
| **spoof-stack-magisk** | `infrastructure/spoof-stack-magisk/` | Yes (`minMagisk=20400`) | No | **Companion LSPosed needed for ~18/104 hooks** | `resetprop` @ post-fs-data + sysfs binds | Complete (86/104 hooks) |

Net dependency stack: Magisk (root + `resetprop`) → ReZygisk/NeoZygisk → LSPosed/Vector = the L0b definition (`layers.md:24-35`).

## 2. The REAL mechanism — and why it is unverified

RUNBOOK documents `pm install Magisk.apk` + `setup-direct-install` (`L1-MAGISK-RUNBOOK.md:138-140`) then expects `magisk --version`=27.2. **Why it won't work:** Direct Install patches the boot image's ramdisk so `magiskinit` runs at boot; **ReDroid boots from a Docker image with no boot.img/ramdisk** — `image-pins.yml:57-63` itself concedes "Magisk is NOT a Docker image; … APK installed inside the guest after boot_completed" → that yields the Manager UI only, not a rooted daemon. Live container today has only stock userdebug `su` at `/system/xbin/su`, no Magisk daemon (`audit/live-recapture-2026-05-29.md:53`). The only fitting mechanism — "install-to-`/system` mode for boot-imageless emulators" — appears once (`power-19-magisk-variants.md:18`), undocumented/unpinned. No `redroid-magisk` image is pinned anywhere.

**Correct mechanism (research, NOT yet in repo):** a community rooted-ReDroid image (Magisk-baked) OR a `docker-redroid` "extra/magisk" overlay placing `magiskinit`/busybox + patching init. Must be sourced, SHA-pinned, validated before Phase 4.

## 3. Experiment design (ready when §2 gap closes)

**Principle:** `redroid-test` stays the untouched L0 baseline. All spoof work in a SECOND container `stability-l0b-redroid` (compose already exists: `L0b.compose.yml` — name `:120`, ADB `127.0.0.1:15556:123`, subnet `172.30.60.0/29:144`, isolated).
- Host pre-flight (read-only SSH): uname 5.4, binder devices, binderfs, `free -g`≥15, `redroid-test` still Up.
- Compose preflight: digest match + 6-check refuse-privileged grep + `container_lifecycle.py preflight`.
- Bring-up: `docker compose -f L0a.yml -f L0b.compose.yml up -d`.
- CONDITION-1 hardening already met by compose (`cap_drop:[ALL]`, no-new-privileges, seccomp).
- **⚠️ seccomp-vs-Zygisk conflict:** `redroid-seccomp.json:3` restricts ptrace→TRACEME and its own comment says it is "INSUFFICIENT for magiskhide's ptrace tracer-pid scrub". cpuinfo-overlay (MS_BIND) is compatible; ptrace-based Zygisk (NeoZygisk) may EPERM → resolve via board-reviewed narrowed seccomp amendment, NOT full `--privileged`.
- Module staging (one delta/step, 30 runs each): L0b root → +cpuinfo-overlay → +spoof-stack-magisk(+LSPosed companion) → +hide-frida-maps (only if wired).

## 4. Measurement & PASS criteria

| Module | Target probes | Baseline | Expected after |
|---|---|---|---|
| spoof-stack-magisk (resetprop) | fingerprint/tags/model/board (#4/7/13/28), bootloader | ~4.0 mass | → ~0 (Pixel-7 props) |
| spoof-stack-magisk (sysfs) | proc_version 0.70, selinux 0.30 | host leaks | → drop |
| cpuinfo-overlay | rank-4 cpuinfo/qemu | 0.0 here | stays ≤0.5 (defends Xeon leak) |
| (root tells) | su_detection 1.0, magisk_uds, mount_ns | su=1.0 | **L0b ADDS root tells — needs L4 Shamiko (not in the 3) to hide** |
| LSPosed companion + L3 | android_id 0.85, play_integrity 0.95, lang 0.85 | ~2.65 | drop only with companion+PIF/TrickyStore |

**PASS (on stability-l0b-redroid, NOT redroid-test):** P1 boots; **P2 root proven (`magisk --version`=27.2) — THE REAL GATE, cannot pass with documented method**; P3 cpuinfo active; P4 props rewritten; **P5 realistic delta = L1/build-prop+sysfs → ~0 (NOT full 0.0000 — that needs L3+L4+LSPosed)**; P6 baseline still Up.

## 5. Risk / rollback
- R1 Zygisk EPERM under hardened seccomp (most likely) → board-reviewed arg-filter, not --privileged.
- R2 binder exhaustion (max 4 containers/device) → verify before bring-up.
- R3 RAM 2×4g vs 15G free → OK.
- R4 Magisk mechanism unsourced → P2 stalls.
- R5 name/port collision → mitigated (distinct name/subnet/port).
- Rollback: module `disable` flags / `magisk --remove-modules` / `compose down --volumes`. Baseline never targeted.

## 6. Feasibility verdict — 🔴 RED / BLOCKED

**Blocked on:** no documented working method to get runtime Magisk root into stock ReDroid 12 (runbooks assume ramdisk patching ReDroid lacks; live container has no Magisk daemon).

**SINGLE HIGHEST-RISK UNKNOWN:** *How does Magisk's daemon get installed and persist in a boot-imageless ReDroid container?* Only in-repo hint: "install-to-/system mode" (`power-19-magisk-variants.md:18`), no procedure/asset/pin.

**RED → YELLOW unblock work:**
1. Source the real mechanism (rooted-ReDroid image OR docker-redroid magisk overlay), validate on a throwaway cell, SHA-pin in `image-pins.yml`.
2. Resolve seccomp-vs-Zygisk-ptrace under board review (or board-approved `[PRIVILEGED-OK]`).
3. Wire hide-frida-maps into a real APK, OR drop it (run 2 of 3 modules).

**YELLOW → GREEN:** above done + host pre-flight + throwaway cell passes P1–P2. Compose/hardening/isolation/measurement/rollback are ALL already in place — only the root mechanism is missing.

> **Score note:** these 3 modules + L0b will NOT reach 0.0000 alone (L0b adds root tells). Set the Phase-4 bar at the L1/build-prop + sysfs delta, not the snapshot's full 0.0000.
