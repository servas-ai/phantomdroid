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
