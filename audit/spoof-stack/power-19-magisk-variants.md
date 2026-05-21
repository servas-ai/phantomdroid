# Power-19 E1 — Magisk-Fork Variant Inventory (Magisk-Delta + Kitsune)

**Date**: 2026-05-21
**Researcher**: ralph-researcher (Task #58)
**Mission**: Map signal-surface delta between current rank-3/3.5/3.7/3.8/3.9 probes vs Magisk-Delta + Kitsune Magisk forks; identify B3-style backlog gaps.
**Anti-verarschen discipline**: GAP-marked items are explicit; nothing fake-claimed.

---

## §1. Variant Inventory

Three Magisk lineages tracked. URLs and version data are public sources only.

| Fork | URL | Latest version (as of 2026-05) | Distinction vs canonical |
|---|---|---|---|
| **Canonical Magisk** (topjohnwu) | https://github.com/topjohnwu/Magisk | **v30.7** (2026-02-23; Rust port >40%, Android 16 QPR2 + Android XR + Nubia sepolicy support; Zygisk extended) | Baseline. Randomized UDS socket name from v25+; Rust-rewritten native code; ptrace-zygisk NOT used (still uses traditional zygote-hook); MagiskHide removed from v24 onwards (DenyList only) |
| **Magisk Delta** (HuskyDG) | https://github.com/HuskyDG/magisk-files | **v27.0 / v27.001 Canary** (Kitsune-line variant tagged `R65A24840-kitsune`, build `26404`); active dev reportedly **stopped May 2026** per XDA thread (corroborating only — not verified primary) | • SuList (inverse of DenyList — Magisk-invisible by default, only whitelisted PIDs see root)  • early-mount.d (pre-init mount hook)  • init.rc injection (no initrd rebuild)  • Systemless file removal  • Bootloop Protection (core-only fallback)  • APK signature pinned in daemon — daemon force-uninstalls non-matching Manager APKs  • Monet/Material-You theme  • Package id: `io.github.huskydg.magisk` |
| **Kitsune Mask / KitsuneMagisk** (1q23lyc45 fork of HuskyDG's Delta) | https://github.com/1q23lyc45/KitsuneMagisk (`kitsune` branch) — **repo archived 2025-08-24**, also Dec-2025 vivo-suu fork archived | **v26.4-kitsune** stable (final upstream release before archive); pre-release builds tagged `R65xxxxxx-kitsune` series | • Inherits all Delta features (SuList, MagiskHide, etc.)  • **ZygiskNext ptrace-init** replaces traditional Zygisk loader (sourced from Dr-TSNG / 5ec1cff ZygiskNext GPLv3)  • Su binary relocated `/system/bin` → `/apex/com.android.runtime/bin` (reduces bind-mount count, attacks rank-3 path enumeration)  • `/debug_ramdisk/su` alternative invocation path  • Install-to-`/system` mode for boot-imageless emulators  • GrapheneOS A14 support  • No-SELinux kernel support restored  • Fix WiFi MAC randomization on A11 (in 1q23lyc45 fork specifically)  • Kitsune Lite variant exists (SuList only, no Zygisk) |

---

## §2. Signal-Surface Diff vs Current Probes

Current probe-rank coverage:

| Rank | Probe | File | Mitigation layer |
|---|---|---|---|
| 3 | `root.su_detection` | `agents/detection/src/probes/root/SuDetectionProbe.kt` | L4 |
| 3.5 | `root.magisk_uds` | `agents/detection/src/probes/root/MagiskUdsProbe.kt` | L4 |
| 3.7 | `runtime.init_svc_enumeration` | `agents/detection/src/probes/runtime/InitSvcEnumerationProbe.kt` | L4 |
| 3.8 | `root.mount_ns_mismatch` | `agents/detection/src/probes/root/MountNsMismatchProbe.kt` | L4 |
| 3.9 | `root.magisk_module_dir` | `agents/detection/src/probes/root/MagiskModuleDirProbe.kt` | L4 |

### §2.1 What each probe currently covers

- **rank 3** — `SU_BINARY_PATHS` (12), `MAGISK_ARTIFACT_PATHS` (4), `SUPERUSER_PACKAGES` (4 incl. `com.topjohnwu.magisk`).
- **rank 3.5** — `/proc/net/unix` substring scan for `@magisk`, `magisk`, `/.magisk`, `/sbin/.magisk`, `/dev/.magisk`.
- **rank 3.7** — `init.svc.*` enum + AOSP_KNOWN_SERVICES (50+) + KNOWN_OEM_PREFIXES (12) + literal-`magisk` + hex-only random-shape heuristic.
- **rank 3.8** — `/proc/self/mountinfo` vs `/proc/1/mountinfo` digest diff + substring scan for `magisk`/`magisk_tmp`/`sbin .magisk bind`.
- **rank 3.9** — `/data/adb/modules/` directory enumeration.

### §2.2 What's new in Delta / Kitsune

#### Delta-specific new signals
1. **Delta Manager package id**: `io.github.huskydg.magisk` — **NOT in rank-3** (only canonical `com.topjohnwu.magisk`). **GAP**.
2. **SuList runtime topology**: inverse of DenyList — rank 3.8 produces false-negative; mount diff appears only when comparing PID-1 vs SuList-whitelisted PID. **GAP**.
3. **early-mount.d / init.rc injection**: Delta may inject services with custom non-`magisk` names (`huskyd`, `delta_*`) escaping both literal and hex filters. **PARTIAL GAP**.
4. **Embedded-cert daemon signature check**: documentation-only.
5. **`/data/adb/magisk` path**: same as canonical (caught).

#### Kitsune-specific new signals
1. **`io.github.huskydg.magisk`** (inherited from Delta).
2. **ptrace-zygisk init pattern (CRITICAL NEW SURFACE)**: ZygiskNext attaches `PTRACE_ATTACH` to PID-1 then injects post-fork. Leaves `/proc/<zygote-pid>/status TracerPid != 0`. **NO current probe checks TracerPid for zygote/init**. Bypasses rank 3.5 + 3.7. **GAP — net new probe**.
3. **Su path relocation `/apex/com.android.runtime/bin/su`**: **NOT in rank-3 SU_BINARY_PATHS**. **GAP**.
4. **`/debug_ramdisk/su`** alternative: **GAP**.
5. **No `ro.dalvik.vm.native.bridge` property change**: would silently bypass any probe checking this. (Not in current 5 ranks.)
6. **Pre-init module mount + `/system` install**: partially caught via rank 14.7 overlayfs.
7. **SELinux-disabled-kernel support**: documentation only.

### §2.3 Recognition needs (advisory mapping)

| Recognition need | Surface | Probe affected |
|---|---|---|
| Detect Delta/Kitsune Manager APK | `io.github.huskydg.magisk` in installed-packages | rank 3 (extend `SUPERUSER_PACKAGES`) |
| Detect Kitsune-relocated su path | `/apex/com.android.runtime/bin/su`, `/debug_ramdisk/su` | rank 3 (extend `SU_BINARY_PATHS`) |
| Detect ZygiskNext ptracer | `TracerPid` field in `/proc/<zygote-pid>/status` | **NEW probe** — proposed rank 3.65 |
| Detect SuList-mode mount topology | Multi-PID mountinfo sample (not just PID-1 + self) | rank 3.8 (extend) OR new probe (proposed rank ~3.85 — see §4 collision note) |
| Detect Delta-named init services | Add `huskyd`, `delta`, `kitsune` substrings | rank 3.7 (extend `MAGISK_LITERAL_SUBSTRING`) |
| Detect Kitsune-relocated mount target | `/apex/com.android.runtime/bin` | rank 3.8 (extend substring list) |

---

## §3. Honest-Limited Disclaimer per Fork

### Magisk Canonical (topjohnwu)
- **Status**: FOSS — GPLv3 at https://github.com/topjohnwu/Magisk
- **Verifiability**: HIGH — release tags, signed APKs, build-reproducible.
- **Caveat**: v30.7's randomized UDS socket name (added v25+) is non-deterministic per boot.

### Magisk Delta (HuskyDG)
- **Status**: FOSS — public source GPLv3 (fork of canonical Magisk).
- **Verifiability**: MODERATE — repository active, releases tagged. XDA report of dev-stopped May 2026 **NOT independently verified by primary HuskyDG channel** (community-rumor; PARTIAL).
- **Caveat**: APK-signature pinning in daemon prevents repackaging-based detection testing.

### KitsuneMagisk (1q23lyc45 fork)
- **Status**: FOSS — GPLv3 (fork-of-fork). **Repo archived 2025-08-24 (read-only)**. Final stable: `v26.4-kitsune`. Community archive mirror at `Scratch2033Alt/KitsuneMagisk`.
- **Verifiability**: MODERATE-LIMITED — source frozen; downstream re-forks (`4accccc/vivo-KitsuneMagisk-suu`, `hamjin/kitsune-magisk-files`) are private-evolution.
- **Caveat**: ZygiskNext-ptrace mechanism documented separately in Dr-TSNG / 5ec1cff ZygiskNext FOSS repo.

### General Honest-Limited Disclaimer
- **NIE fake-claim**: APK bytecode of Delta/Kitsune NOT disassembled. Source claims taken from public README, XDA forum discussions, and HuskyDG blog.
- Where source claim conflicts with our probe behaviour → marked **GAP**.
- **Private downstream forks** (vivo-suu, scorpion-2) out-of-scope L5.

---

## §4. B3-Style Backlog — New Probe-Ranks / PARTIAL-Extensions

### NEW probe ranks proposed

| # | Proposed rank | Probe id | File path proposal | Effort | Why |
|---|---|---|---|---|---|
| B3-M1 | **~3.65** | `runtime.zygote_tracer_pid` | `agents/detection/src/probes/runtime/ZygoteTracerPidProbe.kt` | MODERATE | **CRITICAL**: `TracerPid != 0` for zygote is dispositive ZygiskNext signature. Requires new `queryProcStatus(pid)` accessor + snapshot field. |
| B3-M2 | **~3.85 (collision-flag)** | `root.mount_ns_multipid_scan` | `agents/detection/src/probes/root/MountNsMultipidScanProbe.kt` | MODERATE | **MEDIUM**: SuList-mode evades rank 3.8. Multi-PID mountinfo sample. Requires `queryMountNsDigest(pid)` accessor extension. **NOTE**: rank 3.85 may collide with P19-E2 APatch probe (`root.apatch`). Owner-decision: re-rank E1 proposal to ~3.87 or merge with APatch probe. |

### PARTIAL-extensions to existing probes

| # | Existing rank | Extension | Effort |
|---|---|---|---|
| B3-E1 | rank 3 | Add `io.github.huskydg.magisk` to `SUPERUSER_PACKAGES` | TRIVIAL |
| B3-E2 | rank 3 | Add `/apex/com.android.runtime/bin/su`, `/debug_ramdisk/su` to `SU_BINARY_PATHS` | TRIVIAL |
| B3-E3 | rank 3 | Add `/data/adb/post-fs-data.d` to `MAGISK_ARTIFACT_PATHS` (Delta extended; `magisk_simple` claim VERIFY-ON-IMPLEMENT) | TRIVIAL |
| B3-E4 | rank 3.5 | Add `huskyd`, `delta`, `kitsune` fork-name substrings (PARTIAL — speculative coverage) | TRIVIAL |
| B3-E5 | rank 3.7 | Add fork-brand literal substrings to `MAGISK_LITERAL_SUBSTRING` | TRIVIAL |
| B3-E6 | rank 3.8 | Add `/apex/com.android.runtime` substring to mount-fingerprint check | TRIVIAL |
| B3-E7 | rank 3.9 | Add `/data/adb/kitsune_data`, `/data/adb/sulist` to enumerated paths (VERIFY-ON-IMPLEMENT) | TRIVIAL |

### Quality-gate notes

- B3-M1 (`zygote_tracer_pid`) needs new `ProbeContext.queryProcStatus(pid)` accessor + matching `DeviceSnapshot.procStatusByPid` field.
- B3-M2 (`mount_ns_multipid_scan`) reuses existing `queryMountNsDigest(pid)` if accepts arbitrary PID; else accessor extension.
- All B3-E4 / B3-E5 fork-literal extensions ship behind **PARTIAL** marker (speculative coverage, near-zero FP).

---

## §5. Report-Back Numbers

- **forks_inventoried**: 3 (canonical Magisk + Magisk Delta + Kitsune)
- **signal_surfaces_diffed**: 5 current ranks (3, 3.5, 3.7, 3.8, 3.9)
- **delta_specific_new_signals**: 3 (Manager pkg id, SuList topology, custom init-svc names)
- **kitsune_specific_new_signals**: 4 (ZygiskNext ptracer, /apex su path, /debug_ramdisk/su, no-native-bridge-prop)
- **new_probes_proposed**: 2 (rank 3.65 zygote_tracer_pid, rank ~3.85/3.87 mount_ns_multipid_scan — collision-flag with E2 APatch)
- **extensions_proposed**: 7 (B3-E1 through B3-E7)
- **confidence_breakdown**: HIGH on fork-inventory facts, MODERATE on Delta-dev-status (XDA-rumor), HIGH on Kitsune-archived-status (GitHub-visible), MODERATE-HIGH on signal-surface diff, NONE on APK-bytecode-verification (out-of-scope)
