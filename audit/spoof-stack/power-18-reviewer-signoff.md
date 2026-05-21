# Power-18 Reviewer Sign-Off — Phase-D Endgate

**Date**: 2026-05-21
**Reviewer**: ralph-reviewer (REUSE team `power-13-real-world-validation`, Power-18 Task #56)
**Scope**: Phase-D commits since `power-17-phase-c-2026-05-21` tag (725770c..HEAD) — `4c45716`, `5b9ae72`, `8c53fd3`
**Verdict**: **APPROVE-PHASE-D** — 7/7 criteria pass, 0 blockers, carry-overs forwarded to P19+

---

## §1 — Criteria Matrix

| # | Criterion | Result | Anchor |
|---|---|:---:|---|
| 1 | D1 CLI semantics: `anyDetected` = composite OR-union (not weightedScore) | **PASS** | `ReplaySnapshotCommand.kt:141` + `CompositeDetector.kt:48-55` |
| 2 | Honest-amendment KDoc: aggregate-vs-composite divergence documented inline | **PASS** | `ReplaySnapshotCommand.kt:46-63`, `:135-141` |
| 3 | Test-fixture leak guard: `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` token | **PASS** | `SnapshotRegistry.kt:122-127` + `ReplaySnapshotCliTest.kt:78-94` |
| 4 | 3 CI gates pass locally (82==82 / 0 violations / 0.0000) | **PASS** | `.ci/check-{panel-consistency,namespace-compliance}.py`, `.ci/check-weighted-score.sh` |
| 5 | D3 corpus-index anchored — 3 test-count claims verified | **PASS** | `spoof-stack-corpus-index.md §1` |
| 6 | Test totals: :detection 4174 + :detection-cli 19 = 4193 / 0 failures (XML SoT) | **PASS** | `build/test-results/test/*.xml` aggregate |
| 7 | plan-immutability: no edits to P14/P15/P16/P17 closeouts in Phase-D commits | **PASS** | `git diff 725770c..HEAD audit/spoof-stack/power-14..17*` empty |

---

## §2 — Detailed Findings

### §2.1 — D1 CLI semantics (Criterion 1)

`runReplaySnapshot()` in `ReplaySnapshotCommand.kt:116-157` wires `anyDetected = CompositeDetector.anyDetectorFires(ctx)` (line 141), and `exitCode = if (anyDetected) 1 else 0` (line 148). The composite at `CompositeDetector.kt:48-55` is the verbatim 6-family OR-union:

  `rootBeerIsRooted() || momoIsRooted() || fridaDetected() || playIntegrityFails() || emulatorDetectorFires() || freeRaspT5InstallSourceFires()`

It is NOT a `weightedScore >= THRESHOLD` predicate. The aggregate `weightedScore` is emitted in the JSON aggregate block for diagnostic continuity (and for Gate 2's RedroidSpoofed=0.0000 assertion) but is deliberately decoupled from the exit-code gate.

**Internal consistency check**: each of the 6 helpers in `CompositeDetector.kt` mirrors the matching `MasterCompositeDetectorReplayTest` helper bit-for-bit. The duplication is the documented integration pattern per the source-of-truth's own honesty disclaimer (verbatim from `MasterCompositeDetectorReplayTest.kt`, commit `c202ee8`).

### §2.2 — Honest-amendment KDoc (Criterion 2)

The semantic divergence between aggregate-weighted (centroid statistic, undershoots on third-party emulators) and composite-OR-union (dispositive device-attestation) is documented IN-LINE at three locations:

- `ReplaySnapshotCommand.kt:46-55` — explicit enumeration of the 6 detector-family decision rules
- `ReplaySnapshotCommand.kt:47-56` — explanation of why `anyDetected` does NOT use weightedScore ("undershoots on third-party emulators sitting at weightedScore≈0.14-0.18, below the 0.40 DETECTED band")
- `ReplaySnapshotCommand.kt:135-141` — repeated at the call-site with a pointer to the corpus-index discipline

The CRITICAL invariants block (`:57-63`) calls out the two separable contracts: composite→exit-code gate vs aggregate→numeric invariant. No reader can confuse them.

### §2.3 — Test-fixture leak guard (Criterion 3)

`SnapshotRegistry.kt:116-127` defines `SnapshotNotFound.TestFixtureLeakGuard(name)` with the exact `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` token in the message body. Grep-stable, loud, unambiguous.

`resolveSnapshot()` triggers the guard ONLY when the lowercase name matches `TEST_SOURCE_SET_SNAPSHOT_NAMES = {fridainjectedredroid, nox, bluestacks, genymotion}` AND the active registry doesn't expose that name. Only `MainSnapshotRegistry` triggers the leak guard.

Tests assert all 4 test-set names raise `TestFixtureLeakGuard` against `MainSnapshotRegistry`, and that an unrelated unknown name raises `UnknownName` instead. Two error paths mutually exclusive.

### §2.4 — Three CI gates (Criterion 4)

| Gate | Script | Expected output | Local result |
|---|---|---|---|
| **Gate 3 — Panel consistency** | `.ci/check-panel-consistency.py` | `82 == 82` | **PASS** |
| **Gate 4 — Namespace compliance** | `.ci/check-namespace-compliance.py` | `0 violations` (Tier-A + Tier-B opt-in) | **PASS** |
| **Gate 2 — WeightedScore invariant** | `.ci/check-weighted-score.sh` | `RedroidSpoofed = 0.0000` | **PASS** (live, not skip) |

All 3 scripts have stable exit codes, deterministic outputs, and grep-stable failure tokens.

### §2.5 — Corpus-index anchoring spot-check (Criterion 5)

Three test-count claims spot-checked against the actual closeout source-of-truth:

| Power | Index claim | Closeout anchor | Verdict |
|---|---:|---|:---:|
| P13 | 4145 | `power-13-closeout.md:27` | ✓ match |
| P16-B | 4165 | `power-16-closeout.md:32` | ✓ match |
| P17-C | 4174 | `power-17-closeout.md:34` | ✓ match |

No fabricated commit-shas. Index honestly marks P1-P7 as `UNVERIFIED-pre-baseline`, P9/P10 as approximations, BlueStacks as `Minimal-encoded`.

### §2.6 — Test totals (Criterion 6)

XML aggregate (source-of-truth):
- `:detection` — 90 `TEST-*.xml` files all show `failures="0" errors="0"`. Aggregate = **4174 tests**.
- `:detection-cli` — `ReplaySnapshotCliTest` (15) + `CliIntegrationTest` (4) = **19 tests**.

**Total**: 4174 + 19 = **4193 / 0 failures**.

Gradle daemon false-positive on rerun is a pre-existing build-infra weakness; XML aggregate is source-of-truth. Carried to P19+ build-infra workstream (NOT a Phase-D blocker).

### §2.7 — plan-immutability (Criterion 7)

Phase-D commits introduce only NEW files. No diff against P14/P15/P16/P17 closeouts or their `-security-audit` / `-reviewer-signoff` / `-canonical-sources` / `-freerasp-source-diff` / `-native-disasm` / `-pre-audit` / `-production-hooks-audit` siblings.

---

## §3 — Gate-2 Live Reality Check

The `GATE-2-SKIP:D1-PENDING` skip-path was a defensive scaffold authored before D1 landed. Post-D1, `detection-cli --help` exposes `replay-snapshot`, so the gate is LIVE. Skip-path remains as defense for PRs branched from `main` before D1 merged. **gate_2_works = true (live, not skip)**.

---

## §4 — Carry-Overs for P19+

| # | Item | Disposition |
|---|---|---|
| **C1** | gradle-daemon rerun false-positive | Build-infra workstream — investigate daemon-cache interaction; XML SoT retained as workaround |
| **C2** | Cross-cutting #7 — `Probe.rank Int` vs `inventoryRank Double` migration | RFC for `Probe.rank: Double` interface migration (4 probes diverging) |
| **C3** | Tier-B strict-suffix namespace rule (361 keys / 84 probes) | Cross-rank evidence-key refactor RFC — out of scope for Power-18 D2 by design |
| **C4** | OB1 PAR822349 reboot (gates OB2-OB5) | Owner physical/IPMI access prerequisite |
| **C5** | P-12 spec disposition (Option A frozen vs Option B v2 spec) | Owner-approval gate; plan-immutability forbids unilateral choice |
| **C6** | IMMEDIATE FP fixes — 6 probes / 11 cells | Quality-bar workstream queued for P19 |
| **C7** | PLANNED fixture extensions — 7 probes / 12 cells | Same workstream as C6; fixture-side |
| **C8** | 5 missing-view ranks | P19+ anti-bypass schema-RFC |
| **C9** | T8 self-obfuscation + T7 device-binding-anchor schema | P19+ anti-bypass schema-RFC |
| **C10** | T6 Substrate/Shadow framework tokens | P19+ anti-bypass |
| **C11** | T4 native-code-section CRC + resources.arsc CRC | P19+ anti-bypass |
| **C12** | Live deployment validation (PAR822349 + real APKs) | P20 final-tag — gated on OB1 |

No carry-over is a Phase-D blocker. All items gated on owner-action (C4, C5, C12), planned for P19+ (C2, C3, C6-C11), or build-infra (C1).

---

## §5 — Verdict

**APPROVE-PHASE-D**.

7/7 criteria pass. 0 blockers. Phase-D delivers:
1. `detection-cli replay-snapshot <name>` subcommand with 6-family composite-OR-union `anyDetected`, deterministic JSON output, three-state exit-code contract (0/1/2)
2. Test-fixture leak guard with grep-stable `PRODUCTION_BINARY_CANNOT_ACCESS_TEST_FIXTURES` marker
3. Three CI blocking gates — all live, deterministic, with documented scope-decisions
4. Master corpus-index — 9 sections, 26 cross-referenced docs, every claim anchored or explicitly marked unverifiable

Phase-D is closeable; Team-Lead may proceed to commit this sign-off, tag `power-18-phase-d-2026-05-21`, and author the Power-18 closeout.

---

**End of Power-18 Reviewer Sign-Off.**
