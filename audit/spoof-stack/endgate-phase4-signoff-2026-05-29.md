# Endgate Signoff — Phase 4 Live Spoof Delta (2026-05-29)

**Reviewer:** ralph-reviewer (endgate, adversarial). **Filed by:** orchestrator (reviewer had no Write/Bash tool — lead closed the BY-RUN gap).

## VERDICT: APPROVE-WITH-CORRECTIONS
The delta is real, honestly reported, and reproduces exactly. Two minor per-probe grouping clarifications (non-blocking).

## BY-RUN confirmation (lead, detection-cli re-run 2026-05-29)
`detection-cli run --snapshot p21/l0b-probe-{unspoofed,spoofed}-2026-05-29.yml`:
- **unspoofed: weightedScore=0.3815, criticalFailures=5, category=DETECTED** ✅
- **spoofed: weightedScore=0.2344, criticalFailures=2, category=SUSPICIOUS** ✅
- Delta **−0.1471 (−38.6%)**, critical **5→2**, category **DETECTED→SUSPICIOUS** — matches the report exactly.

## Per-claim (reviewer, verified analytically against probe source + lead BY-RUN)
| Claim | Result |
|---|---|
| Root proven (Magisk Delta v30.6, su 0 id → uid=0) | PASS (report evidence; bootanim.rc+setup-sbin mechanism empirically confirmed) |
| Delta 0.3815→0.2344, critical 5→2, DETECTED→SUSPICIOUS | PASS — reproduced BY-RUN |
| 8 probes neutralized (fingerprint/model/brand/tags+type −1.0 each; bootloader −0.85; selinux; proc_version; play_integrity_signals −0.95) | PASS — per-probe mechanics verified against scoring engine |
| Honest residuals: root.su_detection + emulator.cpu_abi stay high (ABI props disabled for boot) | PASS — no fake 0.0 |
| NEW tells introduced (sensors.light/magnetometer/proximity/barometer, identity.bluetooth_mac) by partial Pixel-7 claim w/ empty HAL | PASS — honestly disclosed; partly offsets gains, which is why net is −38.6% not larger |
| Baseline redroid-test untouched | PASS — Up 3h, confirmed; l0b-probe is the only mutated container |

## Reviewer corrections (non-blocking)
1. `tags_and_type` (rank 7): unspoofed = SCORE_BOTH_VIOLATIONS 1.0 → spoofed CLEAN 0.0, Δ=−1.0. Report's "(each −1.0)" grouping is correct.
2. `env.bootloader` (rank 13): unspoofed only `roDebuggable=="1"` fires → SCORE_DEV_BUILD_LEAK 0.85 → spoofed 0. Δ=−0.85. Report's −0.85 is correct.
3. `play_integrity_signals` (rank 71) is weight 0.5 / non-critical — low impact; grouping it under "neutralized" is accurate but it is not a critical-tier drop.

## Honest caveat
`magisk --setup-sbin` vs `redroid-seccomp.json` survival was NOT tested — l0b-probe ran `--privileged` with seccomp disabled. This is the key open item before any hardened L0b promotion (see security signoff).

**Net:** APPROVE-WITH-CORRECTIONS. The live spoof-vs-detection loop is genuinely demonstrated: rooted ReDroid 12 + 2 modules drive the live detector from DETECTED (0.3815) to SUSPICIOUS (0.2344), with fully honest residuals and no overclaim.
