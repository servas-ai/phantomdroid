# Power-19 Closeout — Phase-E: Anti-Bypass (Magisk-Variants + KernelSU/APatch + Play-Integrity Replay)

**Date**: 2026-05-21
**Mission**: Extend root-detection coverage to Magisk-alternatives (KernelSU + APatch) + canonical/Delta/Kitsune Magisk-fork research + Play-Integrity offline-mock replay with hard-ceiling regression-guard.
**Commit range**: `0b4de25..55b1913` (Power-19 Phase-E scope, 5 commits)
**Tag candidate**: `power-19-phase-e-2026-05-21`

---

## §1. Scope Delivered

Phase-E executed 3 tracks plus endgate:

| Track | Deliverable | Commit |
|---|---|---|
| E1 | `power-19-magisk-variants.md` — 3-fork inventory (canonical/Delta/Kitsune); 5-rank signal-surface diff; B3-style backlog (2 new probes + 7 extensions); honest-limited disclaimer (APK-bytecode NOT disassembled) | `f347189` |
| E3 | `PlayIntegrityOnlineReplayTest.kt` — 5 tests (4 verdict mocks + 1 hard-ceiling regression); references un-snapshottable.md §1 + corpus-index §4 | `0fa53ed` |
| E2 | `KernelSURootProbe` (rank 3.6) + `APatchRootProbe` (rank 3.85) + tests + inventory.yml + matrix regen; 82 → 84 probes; cross-cutting #1+#7 compliant | `968b056` |
| Endgate | `power-19-reviewer-signoff.md` — APPROVE 9/9 criteria, 0 blockers, 4 carry-overs | `e31bad6` |
| Endgate | `power-19-security-audit.md` — APPROVE 6 pillars (0/0/PASS/PASS/true/0) | `55b1913` |

5 commits total. Branch: `report/CLO-143-weekly-W20`. No remote push.

---

## §2. Quantitative Progression

| Metric | Power-18 Phase-D | Power-19 Phase-E | Delta |
|---|---|---|---|
| Production probes in inventory | 82 | **84** | **+2** (KernelSU + APatch) |
| Coverage matrix cells | 656 (82 × 8) | **672** (84 × 8) | **+16 cells** |
| `:detection:test` count | 4174 | **4241** | **+67** (E2 probe tests + E3 verdict mocks + matrix-consistency tests) |
| `:detection-cli:test` count | 19 | 19 | 0 |
| **Total test count** | 4193 | **4260** | **+67** |
| weightedScore RedroidSpoofed | 0.0000 | 0.0000 | invariant preserved |
| criticalFailures | 0 | 0 | invariant preserved |

---

## §3. Anti-Verarschen Discipline Audit

### §3.1 E1 Honest-Limited Discipline

freeRASP-Android-style closed-source caveats explicitly inherited for the Magisk-fork ecosystem:
- Canonical Magisk (topjohnwu): HIGH verifiability (GPLv3, build-reproducible)
- Magisk-Delta (HuskyDG): MODERATE — XDA-dev-stopped rumor flagged as PARTIAL (community-rumor, not primary HuskyDG channel)
- KitsuneMagisk (1q23lyc45): MODERATE-LIMITED — repo archived 2025-08-24; downstream forks (vivo-suu, scorpion-2) out-of-scope L5

NO APK bytecode disassembly performed (explicitly disclosed §5). All B3-E4/B3-E5 fork-literal extension proposals ship behind PARTIAL marker (speculative coverage).

### §3.2 E2 Cross-Cutting Closure

- **#1 namespacing**: `KernelSURootProbe` evidence keys ALL prefixed `ksu.*`; `APatchRootProbe` evidence keys ALL prefixed `apatch.*`. Security audit Pillar 2 + CI `.ci/check-namespace-compliance.py` both confirm 0 violations.
- **#7 fractional rank**: `inventoryRank: Double` override (3.6, 3.85) alongside `rank: Int` codeRank (94, 95) — same two-field workaround as DebuggerTracerPidProbe, ScreenLockProbe, LocationMockRaspProbe, IntegrityInstallSourceProbe. Now 6 probes invoke the Int→Double interface migration RFC carry-over.

### §3.3 E3 Hard-Ceiling Regression-Guard

`PlayIntegrityOnlineReplayTest.kt` codifies the un-snapshottable.md §1 contract as test assertions:
- File-level disclaimer: "No build-prop mutation can produce a Google-signed JWT — only a real TEE-attested Pixel device can"
- Fixture 4 STRONG-clean: probe MUST emit `VERDICT_CLEAN` (NOT `VERDICT_STRONG_DEVICE_BASIC`) without a real JWT
- Separate hard-ceiling test asserts `declarative_only = true` across ALL 4 fixtures

Anti-verarschen marker: probe verdict-CLASSIFICATION is tested; verdict-GENERATION is L0 hard-ceiling, out-of-scope by design.

### §3.4 Rank-3.85 Collision Discipline

E1 proposed rank ~3.85 for `root.mount_ns_multipid_scan` (SuList topology detector); E2 occupied rank 3.85 for APatch. Collision honestly documented in **three loci**:
1. E1 doc §2.3 inline pointer to §4
2. E1 doc §4 B3-M2 explicit collision-flag + owner-decision text
3. E2 commit message 968b056 footer "COLLISION NOTE" naming E1 commit hash

Owner-decision proposed: re-rank E1's mount_ns_multipid_scan to ~3.87 OR merge into existing rank-3.8 MountNsMismatchProbe as SuList extension. Both paths mechanically clean.

---

## §4. Endgate Signoffs

| Gate | Verdict | Notes |
|---|---|---|
| Reviewer (ralph-reviewer) | APPROVE-PHASE-E (9/9 criteria PASS) | `e31bad6` — 4 carry-overs to P20 |
| Security (security-auditor) | APPROVE 6 pillars (0 blockers, 0 warnings) | `55b1913` |

Both signoffs committed.

---

## §5. Open Items — Carry-Over to Power-20

| # | Item | Disposition |
|---|---|---|
| **C20-1** | Rank-3.85 collision — re-rank E1 `root.mount_ns_multipid_scan` to ~3.87 OR merge into rank-3.8 MountNsMismatchProbe | Owner-decision required |
| **C20-2** | B3-M1 new probe `runtime.zygote_tracer_pid` rank ~3.65 (ZygiskNext TracerPid detection) | MODERATE effort: new `queryProcStatus(pid)` accessor + DeviceSnapshot field |
| **C20-3** | B3-E1..B3-E7 PARTIAL-extensions to rank-3/3.5/3.7/3.8/3.9 probes (Delta/Kitsune coverage) | TRIVIAL — ship behind PARTIAL marker |
| **C20-4** | Power-18 carry-overs still open (cross-cutting #7 Int→Double RFC; Tier-B strict-suffix; OB1 PAR822349 reboot) | Carried forward verbatim |

---

## §6. Power-N Progression

| Power | Headline claim |
|---|---|
| 8     | weightedScore → 0.0000 |
| 9     | Deployable spoof artifacts |
| 10    | CLI runner + diversity |
| 11    | 62/62 numbered ranks |
| 12    | TRUE 73/73 inventory |
| 13    | Real-world detector parity |
| 14    | APK-vs-source verification (RootBeer AAR) |
| 15-A  | Frida-positive + 3 vendor-emulator fixtures + 648-cell matrix |
| 16-B  | freeRASP source-diff + RootBeer native-disasm + install_source probe |
| 17-C  | Composite OR-union + FP-analysis + recapture-helper + P-12 audit |
| 18-D  | E2E CLI + 3 CI blocking gates + master corpus-index |
| **19-E** | **Magisk-variants research + KernelSU+APatch probes (rank 3.6/3.85) + PlayIntegrity offline-mock replay; both endgates APPROVE; 9/9 reviewer criteria** |

---

## §7. Power-20 Readiness

**P20 mission (per /goal):**
- Final corpus tag `power-20-end-to-end-verified-2026-05-21`
- `power-20-closeout.md` aggregating all 5 phase-closeouts
- Tree clean, 0 failures, all reviewers signed, auditor sign-off

P19 delivers all prerequisites; P20 is a documentation-and-tag synthesis phase. No new code/tests expected unless owner explicitly requests Phase-D Quality-Bar work (6 IMMEDIATE probe-logic fixes + 7 PLANNED fixture extensions, currently Phase-D carry-over).

---

**Status**: COMPLETE within Phase-E scope.
**Tag**: `power-19-phase-e-2026-05-21`
