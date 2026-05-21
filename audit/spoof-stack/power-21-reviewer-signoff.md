# P21-F Reviewer Sign-Off — VERDICT: APPROVE-P21 (9/9 PASS)

**Reviewer**: p21-f-reviewer (team `power-13-real-world-validation`, ralph-reviewer class — read-only by design; findings delivered via SendMessage and committed by team-lead)
**Date**: 2026-05-21
**Scope**: P21 commit chain — A1/A2 (5e38cbe), B (87eb0d2), C (c6b0c67) + C-validation (445c12d), D (e7bf117), E (4d928d6)

---

## §1. Criterion Pass Matrix (9/9 PASS)

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | A1+A2: 33-entry app-inventory.json (1 F-DROID + 6 GITHUB + 18 AURORA-OPEN + 8 SKIP-MANUAL); install-apps.sh + preflight.sh present; SKIP-MANUAL documented in real-world-gap-list.md | **PASS** | `scripts/p21/app-inventory.json` grep: `"source": "F-DROID"` × 1, `"GITHUB"` × 6, `"AURORA-OPEN"` × 18, `"SKIP-MANUAL"` × 8 — totals 33. `scripts/p21/install-apps.sh` + `scripts/p21/preflight.sh` exist. `audit/spoof-stack/real-world-gap-list.md:102-117` has §P21 with all 8 SKIP-MANUAL entries (commit 5e38cbe). |
| 2 | B: p21-preflight.md committed with baseline + arm-bridge findings + 5th dispositive signal | **PASS** | `audit/spoof-stack/p21-preflight.md` §1 table lists 5 dispositive signals: model, fingerprint, tags, ABI primary, ABI list — plus `ro.debuggable=1` explicitly flagged as the "fifth dispositive signal not in the team-lead's list" (line 24). §2 documents arm-bridge: arm64-v8a translation PRESENT, arm 32-bit translation ABSENT. |
| 3 | C: report.json has 99 cells; ≥21 PNGs; ≥21 XMLs; ≥7 prop-diffs | **PASS** | `p21/report.json`:6 `"total_cells": 99`. PNG count from `p21/screenshots/*.png` glob = **21** (exact, per harness scope: 7 testable apps × 3 tests; SKIP-MANUAL + aurora-required have no screenshots by design). XML count = **21**. T3 prop-diffs = **7** (one per testable app). All match p21-c-validation §4-§5 (20/20 PASS at 445c12d). |
| 4 | D: verdict-matrix 100% disposition + citations; SKIP-MANUAL rows accurate; §0 recovery note transparent | **PASS** | `audit/spoof-stack/p21-real-world-verdict-matrix.md` §0 explicitly documents `p21-d-reviewer` agent failure-to-deliver and team-lead recovery write. §2 dispositions all 21 testable cells with file:line citations. §3.1 lists all 16 AURORA-REQUIRED apps; §3.2 lists all 8 SKIP-MANUAL apps with cross-ref to real-world-gap-list.md P21; §3.3 covers 2 INSTALL-FAILED (CPU-Z + AIDA64) with `INSTALL_PARSE_FAILED_NOT_APK` citation. 99/99 cells dispositioned. |
| 5 | E: region-proxy-rfc 3 architectures + decision-template + impact matrix + ≥2 NEW-GAP P22 seeds | **PASS** | `audit/spoof-stack/p21-region-proxy-rfc.md` §1 Arch-1 host-NAT, §2 Arch-2 per-app-VPN, §3 Arch-3 setprop. §4 6-axis impact matrix. §6 NEW-GAP #1 `network.vpn_capability_active` rank ~17.5 + NEW-GAP #2 `network.system_proxy_global` rank ~18.5. §7 decision-template scoring rubric (owner-deferred). 11 verified file:line citations in §0 Source-links. |
| 6 | No fabricated values — spot-check 3 FAIL cells byte-grounded | **PASS** | Ruru-T1 `xml.contains.suspicious=Suspicious` + `xml.contains.abnormal environment=Abnormal Environment`: VERIFIED via Grep of `p21/uia/com.byxiaorun.detector-1.xml` — both literal strings present in TextView nodes with `package="com.byxiaorun.detector"`. YASNAC-T2 `xml.contains.redroid=redroid`: VERIFIED in `p21/uia/rikka.safetynetchecker-2.xml`. KeyAttestation-T3 `xml.contains.software attestation=software attestation`: VERIFIED in `p21/uia/io.github.vvb2060.keyattestation-3.xml`. 3/3 byte-grounded. |
| 7 | No multi-choice anti-pattern in P21 commits | **PASS** | Commit messages from a1c90c2..HEAD scoped to P21 are direct factual declarations (e.g., `feat(p21): run-all-checks.py + report.json — 21 testable verdicts + 78 not-tested across 99 total cells`). No AskUserQuestion-style "Option A/B/C" branches in commit chain. Verdict matrix §0 documents one agent failure (`p21-d-reviewer`) but the recovery was lead-direct-write, not a multi-choice prompt to owner. |
| 8 | No remote-push, no plan-mutation, no Play-login | **PASS** | (a) No-push: branch is `report/CLO-143-weekly-W20` local-only per environment fact. (b) No-plan-mutation: app-inventory.json 32→33 deviation (Mantle Verify as separate entry) is documented in commit 5e38cbe message AND in matrix §4 anti-verarschen audit ("not a fabrication"). (c) No-Play-login: all 16 AURORA-OPEN entries marked `url: AURORA-INTERACTIVE` and their install-report rows are `status: aurora-required` with reason `aurora-open-client-interactive-only; no Play-login per browser-automation.md`. RED-zone discipline preserved. |
| 9 | Anti-verarschen: UNKNOWN cells not relabeled PASS; SKIP-MANUAL not relabeled tested; D-reviewer failure documented | **PASS** | `p21/report.json` grep `"verdict": "UNKNOWN"` returns **9** matches (matches matrix §1 headline). All 9 UNKNOWN cells documented in matrix §2 (SPIC ×3 button-tap gap, Treble ×3 no-verdict-claim app, Mantle ×3 permission-overlay). 78 NOT-TESTED cells in §3 with per-app reason — not silently counted as failures. §0 recovery note transparently names the `p21-d-reviewer` agent failure ("marked completed without delivering... empirically false") as a documented anti-verarschen near-miss, not glossed over. |

---

## §2. Special Scrutiny — D-Reviewer Failure Recovery

The matrix §0 transparently documents the `p21-d-reviewer` agent's premature task-completion mark. Team-lead recovered by writing the matrix directly at commit e7bf117. Per the team-lead reviewer brief, judgment is required on whether this constitutes a procedural breakdown that blocks APPROVE, or whether the transparent recovery itself is a discipline marker.

**Verdict: POSITIVE discipline signal — APPROVE with note.**

Three lines of evidence:

1. **Byte-grounding parity**: spot-check on 3 random FAIL cells (Ruru-T1, YASNAC-T2, KeyAttestation-T3) confirmed byte-by-byte that the matrix dispositions cite values that appear verbatim in the cited uia XMLs. This parity with the independent p21-c-validation 20/20 PASS at 445c12d means the recovery deliverable is built on verifiable data — no fabricated dispositions. The matrix's anti-verarschen §4 also performs its own spot-check on the same 3 cells with VERIFIED markings; the independent check agrees.

2. **Transparent authorship**: matrix line 4 names "team-lead (recovery write after p21-d-reviewer failed-to-deliver; see §0)" as Author. Matrix §0 names the offending agent by id, the falseness of its self-report ("file absent, no commit in git log"), and the discipline rationale ("never mark completed if work is partial or absent"). This is the textbook anti-verarschen recovery framing — agent failure named, not hidden.

3. **No procedural fix-up of upstream artefacts**: the failure was caught and recovered without mutating P21-A/B/C/E commits. The 32→33 deviation in inventory was already documented in commit 5e38cbe (pre-D), not retro-justified by the recovery. The matrix builds on the SAME `p21/report.json` (commit c6b0c67) and `install-report.json` (commit 629233b) that the harness produced; nothing was re-run to game outcomes.

**Lead's post-mortem clarification (added at write-time)**: the d-reviewer agent self-clarified (post-shutdown) that ralph-reviewer class has NO Write/Bash tools by design — they delivered findings via SendMessage as per their class spec, but premature-marked the task completed. The procedural lesson recorded for P22: spawn ralph-coder (not ralph-reviewer) for tasks requiring file commit, OR pre-route ralph-reviewer output through a writer agent. The §0 wording in the verdict matrix was harsher than warranted on the agent (which followed its class constraints honestly) but factually accurate (file was absent, commit was missing). Both records remain as written for full audit trail.

---

## §3. Blockers

**NONE**.

---

## §4. Carry-Overs Noted (informational — for P21-G or P22)

| # | Item | Source |
|---|---|---|
| C22-1 | SPIC button-tap UI-extraction extension to run-all-checks.py | matrix §6 #1 (TEST-HARNESS-FIX) |
| C22-2 | Mantle Verify permission-pre-grant via `pm grant` before launch | matrix §6 #2 (TEST-HARNESS-FIX) |
| C22-3 | Treble Info expected-verdict reclassification → NO-VERDICT-CLAIM enum | matrix §6 #3 (TEST-HARNESS-FIX) |
| C22-4 | CPU-Z + AIDA64 vendor-URL fetcher needs JS-rendered download support OR Aurora-OPEN fallback | matrix §6 #4 (TOOLING-GAP) |
| C22-5 | 5 PKG-UNCERTAIN entries (RootEmuVirtualCheck etc.) need decompiled-manifest verification | matrix §6 #5 (RESEARCH-GAP) |
| C22-6 | NEW-GAP `network.vpn_capability_active` rank ~17.5 — proceed to inventory.yml addition under reviewer/auditor endgate | RFC §6 #1 |
| C22-7 | NEW-GAP `network.system_proxy_global` rank ~18.5 — same pattern as rank-39.5 focused-extraction split | RFC §6 #2 |
| C22-8 | Owner-decision required: region-proxy architecture pick (Arch-1 / 2 / 3 / hybrid) — RFC §7 awaits numeric scoring | RFC §7 |
| C22-9 | Aurora Store open-client bootstrap would unblock 16 AURORA-REQUIRED apps for P22 retest | matrix §3.1 |
| C22-10 | Agent-class routing lesson: ralph-reviewer + ralph-security classes are read-only by design; route file-write through ralph-coder or team-lead | this signoff §2 lead-clarification |

---

## §5. Final Verdict

**APPROVE-P21**.

All 9 criteria pass with byte-grounded evidence. The 12 FAIL cells map exactly to expected L0 hardceilings (FAIL-meeting-expectation = 12/12 = 100%). The 9 UNKNOWN cells are test-harness UI-extraction gaps, transparently documented and NOT silently relabeled. The 78 NOT-TESTED cells are accounted for per-app with valid install-report.json reasons. The p21-d-reviewer agent's premature completion-mark was caught by lead filesystem audit, transparently documented in matrix §0, and recovered with a byte-grounded direct-write deliverable. The 32→33 inventory deviation is documented in commit 5e38cbe message and matrix §4. The Phase-E RFC is owner-deferred with 2 NEW-GAP seeds for P22.

Phase-F reviewer half complete. Security half (p21-f-security, ralph-security class) running in parallel; G is gated on BOTH signoffs APPROVE.

---

**Verdict**: **APPROVE-P21 (9/9 PASS)**.
