# PhantomDroid — Plan coverage map (2026-05-31)

Every plan point from the repo plan sources (PRD.md backlog, audit/E2E-autonomous-plan, STATUS.md
pillars+gaps, SpoofStack layers, compose TODOs) mapped to status. Branch `session/e2e-2026-05-30`
(pushed to origin). Done = implemented + E2E-proven + committed + pushed, with proof under `proof/`
or `audit/anti-spoof-80/`. Owner-gated = skipped + documented in `proof/BLOCKERS-owner-gated.md`.

## DONE + E2E + pushed

| Plan source | Point | Proof |
|---|---|---|
| PRD story-01..05 | All 5 backlog stories (`passes: true`) | git push verified; STATUS addendum |
| E2E-autonomous Phase 1-2 | kernel-5.4 reboot + live full boot | `audit/redemo-live-redroid-2026-05-30.md`, `proof/slice5-live-boot*` |
| E2E-autonomous Phase 3 | APK-in-container (detector-app) + live sweep + true matrix | `proof/detector-app-live/`, `proof/orchestrator-true-matrix/` |
| E2E-autonomous Phase 4 | live spoof re-probe delta | `audit/anti-spoof-80/`, `p21/live-spoofed-v2-report.json` |
| STATUS Detection pillar | full 84-id inventory implemented (86 probes), 4241 tests | `proof/slice1-2-fresh-build.log` |
| STATUS detector-app pillar | in-process attestation on live container | `proof/detector-app-live/RESULT.md` |
| STATUS Orchestrator pillar | run_id + persistence + config_loader + live_matrix + --resume + concurrency + --config | `proof/orchestrator-*/` (65 pytest) |
| STATUS Live ReDroid pillar | full boot + DETECTED 0.3462 + live spoof SUSPICIOUS 0.1594 | `proof/`, `audit/anti-spoof-80/` |
| STATUS SpoofStack L1 | build-properties spoof proven live (5/5 detectors clean) | `audit/anti-spoof-80/RESULTS-live-spoof-2026-05-30.md` |
| STATUS CI pillar | added detector-app build+test gate | `proof/ci-detector-app/` |
| STATUS P21 pillar | corpus extended to 22 distinct detectors live on spoofed container | `proof/p21-extension/` |
| Anti-spoof goal (≥80%) | 5/5 verdict detectors CLEAN, 0 active detections; +RAM/storage/IP fixes | `audit/anti-spoof-80/PROOF-GALLERY.md` (113 PNGs) |
| Doc accuracy | corrected stale detection README/SKELETON ("1 probe/74 TODO" → 86 implemented) | this commit |

## SKIPPED + documented (owner-gated — `proof/BLOCKERS-owner-gated.md`)

| ID | Point | Blocker |
|---|---|---|
| B1 | L0b Magisk root stack | Supply-chain: source+SHA-pin a rooted-ReDroid image (stock has no boot.img) |
| B2 | L2–L6 module stack (compose `TODO(L2..L6)`: TEESimulator, TrickyStore keybox, Shamiko, HideMyAppList, VirtualSensor, identity-spoof, LTE-gateway) | Depends on B1 + third-party module supply-chain sourcing + lab LTE infra |
| B3 | Play Integrity / hardware attestation pass | Architectural: needs a real TEE (impossible in a software container) |
| B4 | Hardened (non-privileged) container auto-boot | Posture decision: hardened cap_drop+seccomp can't boot on binderfs-only kernels |
| B5 | Credential purge + rotation | Credential + destructive history rewrite on origin/main |

## Out-of-scope / not a concrete plan point (per GOAL-8h.md exclusions)
- "74 remaining TODO probes" and "adding new CLO probes" — explicitly excluded in `GOAL-8h.md`; the
  defined inventory (`shared/probes/inventory.yml`) is fully implemented. Inventing undefined probes
  would be speculative.
- P21 ≥30 distinct apps — bounded by x86 corpus ceiling (ARM-only + Play-login apps); 22 achieved,
  remainder needs an arm64 host or owner/policy-gated APK sourcing (documented in `proof/p21-extension/`).

**Net:** every well-defined, non-owner-gated plan point is DONE + E2E + pushed. The remainder is
owner-gated (B1–B5, documented) or out-of-scope/speculative.

---

## Update 2026-05-31 (continued autonomous run — major additions)

Since the initial coverage map, the following were implemented + E2E-proven + pushed (each with proof/):

| Plan point | Status | Proof |
|---|---|---|
| **B4 hardened NON-privileged boot** | ✅ SOLVED (was mis-filed as posture-blocked) — device-cgroup-rule + l0b seccomp, codified in `container_lifecycle.build_hardened_run_argv()` | proof/orchestrator-hardened-nonpriv/ |
| Internal detector vs live spoof | ✅ DETECTED 0.3462 → **CLEAN 0.09** (capture gaps closed + real resolution/timezone/DNS fixes) | proof/orchestrator-{capture-gaps,spoof-consistency,internal-clean,fonts}/, RESIDUAL-CLASSIFICATION.md |
| Durable one-command spoof launch | ✅ launch-l1-spoof.sh → CLEAN reproducibly | proof/durable-spoof-launch/ |
| Capstone: hardened NON-priv + spoofed | ✅ launch-l0b-hardened-spoof.sh → Privileged=false + CLEAN | proof/capstone-hardened-spoofed/ |
| TRUE 4-cell matrix across postures | ✅ unspoofed/hardened/spoofed/hardened+spoofed | proof/true-full-matrix/ |
| **P21 verdict substring-overlap BUG** | ✅ FIXED ('rooted' ⊂ 'not rooted' neutralised clean verdicts) + 14 tests | proof/p21-verdict-fix/ |
| Host-side Python tooling test coverage | ✅ probe_emit(6) + p21-verdict(14) + render-heatmap(3) + auto-status(4); full suite 104 | proof/{detector-lab-probe-emit,p21-verdict-fix,render-heatmap-tests,auto-status-tests}/ |
| CI: full Python suite gated | ✅ python-tools-test.yml (104 tests, ≥90 regression guard) | proof/ci-python-tools/ |
| Orchestrator container_lifecycle (last SPEC module) | ✅ preflight refuses privileged (exit 78) + hardening | proof/orchestrator-container-lifecycle/ |

**Orchestrator pillar: 100% of SPEC modules implemented + tested (75 orchestrator tests).** Detection: full
inventory + 4241 tests. Anti-spoof: internal CLEAN + 5/5 real verdict apps CLEAN. CI: detection +
orchestrator + detector-app + python-tools-full workflows.

**Genuinely external-blocked (unchanged, live-evidence in BLOCKERS-owner-gated.md + OWNER-ACTION-KIT.md):**
B1 (Magisk binary — supply-chain), B2-L3/L5/L6 (third-party modules / sensor-HAL image / LTE infra),
B3 (hardware TEE — physical), B5 (destructive history rewrite — owner executes the ready script).
These cannot be resolved autonomously; everything around them is done.

---

## Update 2026-05-31 (Martin unblocked B1 → built + rooted)

Martin explicitly authorised the Magisk image build ("Freigegeben (Martin: weiter): bau das
Magisk-roote ReDroid-12-Image"). With that supply-chain decision made, B1 is now SOLVED E2E:

| Plan point | Status | Proof |
|---|---|---|
| **B1 — L0b Magisk root stack** | ✅ SOLVED — built `redroid/redroid:12.0.0_magisk` (digest `sha256:dfed3d9d…`) via ayasa520/redroid-script @881f7f00 (`python3 redroid.py -a 12.0.0 -m`); Magisk 30.6 Kitsune; LIVE `su -c id` → uid=0(root) in a **Privileged=false** hardened container; `com.topjohnwu.magisk` auto-installed | proof/b1-magisk-build/RESULT.md; pin in image-pins.yml (`redroid_12_magisk_rooted`) |
| **B2 — L2–L6 sensor/LTE layers** | 🔄 in progress on the rooted substrate (builder sub-agent) | proof/b2-sensor-lte/ |

B1 was the keystone external blocker for the whole L0b/L2–L6 chain; with Martin's go-ahead and the
hardened non-privileged boot (B4) already solved, the rooted substrate is real and reproducible.
B2 layers (telephony/LTE props via root resetprop are achievable; sensor-HAL .so injection may remain
genuinely blocked — being verified live, documented honestly either way).
