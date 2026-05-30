# SpoofStack — Library & Component Overview

**Date**: 2026-05-29
**Scope**: Single consolidated overview of every external library / module the
SpoofStack (L0–L6) depends on, plus what is actually implemented in this repo
versus what is still spec-only.
**Sources of truth**: `agents/stability/stack/layers.md`,
`agents/stability/stack/compose/*.yml`,
`audit/spoof-stack/spoof-stack-corpus-index.md`,
`audit/spoof-stack/production-hooks-spec.md`, `STATUS.md`.

> **Research boundary**: This is a defensive detection-resistance lab. Libraries
> below are catalogued to *measure* and *harden against* fingerprint leaks in
> owned test containers — not as an operational bypass runbook for third-party
> anti-abuse, attestation, or fraud controls. See `README.md` → Hard rules.

---

## 0. Status legend

| Symbol | Meaning |
|---|---|
| ✅ IMPL | Implemented in this repo (code/module present, exercised by tests) |
| 🟡 SPEC | Specified in `layers.md` / compose, **no working module yet** |
| 🧱 CEILING | Known un-spoofable in FOSS-2026 (tracked, not promised) |
| ⬇️ EXT | External upstream dependency, pulled at deploy time (not vendored) |

---

## 1. L0 — Container baseline & root substrate

| Library | Version (pinned) | Source | Role | Repo status |
|---|---|---|---|---|
| **ReDroid** | `12.0.0_64only-latest` @ `sha256:e6f799d5…ef55d3` | docker.io/redroid/redroid | Android-in-container runtime | ✅ IMPL (image digest-pinned in all compose files; booted on PAR822349 2026-05-20) |
| **Magisk** | v27.2 | github.com/topjohnwu/Magisk | Root + module loader | ⬇️ EXT / 🟡 SPEC (referenced 24× across compose files; not vendored) |
| **ReZygisk** | v1.3.4+ | gitlab.com/PerformanC/ReZygisk | Zygisk reimplementation (replaces built-in Zygisk) | ⬇️ EXT / 🟡 SPEC |
| **LSPosed (JingMatrix fork)** | v1.10.1 | github.com/JingMatrix/LSPosed | Xposed framework for runtime hooks | ⬇️ EXT / 🟡 SPEC |
| anbox-modules (binder/ashmem DKMS) | from source | github.com/anbox/anbox-modules | Host kernel modules for ReDroid on non-binderfs kernels | ✅ IMPL (built on PAR822349; **known limit: Android-12 HIDL needs binderfs → host kernel HWE 5.4 reboot pending = OB1**) |

**Hardening note**: the original `privileged: true` L0 stub is **DEPRECATED** (host-root-escape finding F37). `container_lifecycle.py` preflight hard-blocks it and substitutes `cap_drop:[ALL]` + narrowed `cap_add:[SYS_ADMIN]` + `redroid-seccomp.json` + `no-new-privileges:true`.

---

## 2. L1 — Build properties

| Library / Module | Source | Probes addressed | Repo status |
|---|---|---|---|
| **DeviceSpoofLab-Magisk** (build.prop FS patches) | in-house | #4, #7, #13, #28 | 🟡 SPEC (target profile Pixel 7 `panther`, Android 14, patch 2024-08-05) |
| **DeviceSpoofLab-Hooks** (API-level property hooks, 126+ props) | in-house Xposed | #1, #9, #27 | 🟡 SPEC |
| **cpuinfo-overlay** (Magisk module) | in-house — `agents/stability/stack/modules/cpuinfo-overlay/` | rank-27 CpuAbi / cpuinfo leak | ✅ IMPL (module.prop + service.sh + spoofed cpuinfo + profile-check test) |

---

## 3. L2 — Identity spoofing

| Library | Source | Probes addressed | Repo status |
|---|---|---|---|
| **Android Faker** | external Xposed/LSPosed module | #11,#12,#15,#16,#17,#21,#22,#29,#31,#32 (IMEI, Android ID, MAC, BT-MAC, SSID, MediaDRM, SIM, operator) | ⬇️ EXT / 🟡 SPEC (per-app-profile unique IDs, persistent store — not implemented) |

---

## 4. L3 — Integrity & attestation

| Library | Version | Source | Probes | Repo status |
|---|---|---|---|---|
| **PlayIntegrityFork (PIF)** | v15 | github (PlayIntegrityFork) | #2 (BASIC + DEVICE verdict) | ⬇️ EXT / 🟡 SPEC — flash order: **PIF first, then TrickyStore** |
| **TrickyStore** | v1.3.0 | external | #2, #6 (STRONG verdict via keybox) | ⬇️ EXT / 🟡 SPEC — keybox at `/data/adb/tricky_store/keybox.xml` |
| └ STRONG keystore attestation | — | — | #6 STRONG | 🧱 CEILING — live X.509 chain signed by device-TEE key; only ephemeral (4–8 wk keybox windows) |
| └ Play Integrity STRONG (#2) | — | Google TEE | #2 | 🧱 CEILING — `MEETS_STRONG_INTEGRITY` needs real provisioned Pixel TEE; DEVICE-verdict only, ephemeral |

---

## 5. L4 — Runtime hiding

| Library / Module | Source | Probes | Repo status |
|---|---|---|---|
| **Shamiko** | external (LSPosed team) | #3, #8, #14 (Zygisk + Magisk + module hiding) | ⬇️ EXT / 🟡 SPEC (DenyList whitelist-mode) |
| **HideMyAppList** | external | #10, #19, #50 (package-list filtering) | ⬇️ EXT / 🟡 SPEC |
| **hide-frida-maps** (Xposed module) | in-house — `stack/L4/hide-frida-maps/` | rank-9.0 `runtime.frida_memory_maps` | ✅ IMPL (Kotlin `HideFridaMapsHook.kt` 192-LOC + `RedactionPatterns.kt` — Java-layer hooks complete; native shadowhook .so referenced, not vendored) |
| **spoof-stack-magisk** | in-house — `infrastructure/spoof-stack-magisk/` | build-prop/CPU/verified-boot/SELinux/DNS/locale/serial | ✅ IMPL (functional Magisk module — post-fs-data + service.d resetprop/settings, sysfs-binds, system/ magic-mount tree; companion LSPosed module still SPEC) |
| └ FridaKill (P-12.1) | in-house spec | rank-9.0 prod runtime | 🟡 SPEC (deployable today per production-hooks-spec §P-12) |
| └ native prologue hash (#9.7) | — | rank-9.7 `runtime.native_prologue_hash` | 🧱 CEILING — UNCOUNTERED in FOSS 2026 |
| └ GOT-hook scan (#9.8) | — | rank-9.8 `integrity.prologue_got_hooks` | 🧱 CEILING — UNCOUNTERED in FOSS 2026 |

---

## 6. L5 — Sensor emulation

| Library | Source | Probes | Repo status |
|---|---|---|---|
| **VirtualSensor (modified)** | external, modified | #24, #42–45 (sensor data injection) | ⬇️ EXT / 🟡 SPEC |
| **Trace-Player** | in-house | #24 (replay real Pixel-7 sensor CSV traces) | 🟡 SPEC (trace source = 10-min Pixel-7 recording; not captured) |

---

## 7. L6 — Network egress

| Component | Purpose | Probes | Repo status |
|---|---|---|---|
| Lab LTE modem | mobile-carrier IP | #5, #25 | 🟡 SPEC (hardware-blocked) |
| iptables-NAT gateway | container egress → LTE | #18, #38 | 🟡 SPEC |
| Local DNS (1.1.1.1) | realistic DNS | #37 | 🟡 SPEC |
| └ `network.ip_asn` (#5) | — | #5 | 🧱 CEILING (mitigable via L6 only — residential/4G; hardware-blocked) |

---

## 8. Detection side (the "prober") — fully implemented

The detection harness that *tests* the spoof stack is the most mature pillar.

| Component | Location | Status |
|---|---|---|
| Probe contract + runner | `agents/detection/src/core/` | ✅ IMPL |
| **86 probes** | `agents/detection/src/probes/` | ✅ IMPL (target was 72; +19% over inventory) |
| **4,241 unit tests** | `agents/detection/build/test-results/` | ✅ green; CI floor ≥3,000 |
| `detection-cli` (replay snapshots → JSON + score) | `agents/detection-cli/src/main/kotlin/com/detectorlab/cli/` | ✅ IMPL |
| Spoof-replay panel (`FullProbeRunnerSpoofTest`, 84-probe) | `agents/detection/src/test/.../replay/` | ✅ IMPL (opt-in `-PrunSpoofPanel=true` → CLEAN, 0 critical failures) |
| Composite OR-union detector (RootBeer/Momo/Frida/PlayIntegrity/EmulatorDetector/freeRASP) | `MasterCompositeDetectorReplayTest.kt` | ✅ IMPL |
| Snapshot fixtures | Pixel7Clean, SamsungS22Clean, RedroidV12, RedroidSpoofed, FridaInjectedRedroid, Nox, Genymotion, BlueStacks | ✅ IMPL (synthesized from canonical public sources; live-capture pending = OB2) |

---

## 9. Implementation scorecard (libraries/modules)

| Layer | Libraries specced | Actually implemented in-repo | Gap |
|---|---:|---:|---|
| L0 | 5 | 2 (ReDroid pin, anbox DKMS) | Magisk/ReZygisk/LSPosed not vendored (deploy-time) |
| L1 | 3 | 1 (cpuinfo-overlay) | DeviceSpoofLab Magisk + Hooks |
| L2 | 1 | 0 | Android Faker |
| L3 | 2 | 0 | PIF + TrickyStore (2 hard ceilings) |
| L4 | 4 | 2 (hide-frida-maps, spoof-stack-magisk) | Shamiko, HideMyAppList (2 hard ceilings) |
| L5 | 2 | 0 | VirtualSensor + Trace-Player |
| L6 | 3 | 0 | LTE/NAT/DNS (hardware-blocked) |
| **Total** | **20** | **~8 (3 functional in-house modules: cpuinfo-overlay, hide-frida-maps, spoof-stack-magisk)** | **12 spec-only + 5 hard ceilings** |

**Headline**: the *measurement* side (probes, CLI, replay panel) is production-grade; the *spoof* side is **snapshot-level proven** — the synthesized RedroidSpoofed snapshot drives the E2E panel from **weightedScore ≈ 0.35 (DETECTED, 4 critical)** down to **0.0000 (CLEAN, 0/65 probes hit)**. But **only ~3 of 7 real Magisk/Xposed modules exist** — the remaining stack is upstream libraries (Magisk, ReZygisk, LSPosed, TrickyStore, Shamiko, Android Faker, VirtualSensor) that have never been flashed into a live container.

> **Panel-size disambiguation**: three different probe counts appear in this repo — **86** probe *files* in `src/probes/`, **84** runnable in `FullProbeRunnerSpoofTest`, **65** in the `detection-cli` `ProbeRegistry`, and **8** in the ground-truth `SnapshotReplayE2ETest`. The "0/65 hit" figure above is the CLI registry. Do not conflate these downstream.

> **Metric note (verified 2026-05-29, tester endgate)**: the canonical aggregate is `weightedScore`. Unspoofed = **0.3462** (committed `results/e2e-report-unspoofed.json`) / **0.3697** (fresh CLI re-run); spoofed = **0.0000** everywhere (byte-stable). An earlier draft cited "0.263" — that was the *mean of per-probe scores*, a different metric, now corrected. The directional delta (DETECTED → CLEAN) reproduces on every fresh run. Scope caveat: this is a **JVM-snapshot replay** result, not a live-container attestation — per `audit/spoof-stack/detection-resistance-report.md`, 0.0000 does not imply a real ReDroid container passes every detector in the wild.

---

## 10. The 3 load-bearing hard ceilings (do not promise coverage)

| Rank | Surface | Why uncounterable in FOSS 2026 |
|---|---|---|
| **9.7** | `runtime.native_prologue_hash` | in-memory byte hash of `libc`/`libart` prologues vs disk — inline-hook trampolines always diverge |
| **9.8** | `integrity.prologue_got_hooks` | GOT-region scan detects overwritten entries |
| **6 STRONG** | `integrity.keystore_attestation` STRONG | live TEE-signed X.509 chain; only a real provisioned device produces it |

These three are why "100% bypass" is not a real target. Everything else (props, identity, BASIC/DEVICE integrity, runtime hiding, sensors) is mitigable; these three are not.
