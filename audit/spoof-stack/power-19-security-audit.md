# Power-19 Phase-E Security Audit

**Date**: 2026-05-21
**Auditor**: claude-sonnet-4-6 (Security Auditor)
**Branch**: report/CLO-143-weekly-W20
**Commit range**: 0b4de25..HEAD (3 commits)
**Verdict**: APPROVE

---

## Commits in Scope

| SHA | Message |
|-----|---------|
| `968b056` | feat(detection): Power-19 E2 — KernelSU (rank 3.6) + APatch (rank 3.85) root probes |
| `f347189` | docs(audit): Power-19 E1 — Magisk-Delta + Kitsune variant inventory |
| `0fa53ed` | test(detection): Power-19 E3 — PlayIntegrityOnlineReplayTest (4 verdict mocks + hard-ceiling) |

---

## Pillar 1 — Credentials Sweep

**Scope**: `git diff 0b4de25..HEAD -- agents/ audit/`

**Result**: PASS — NO BLOCKERS

Full diff of `agents/` and `audit/` directories was scanned for:
- API keys and tokens (sk-, ghp_, glpat-, AWS_SECRET, OPENAI_API)
- Hardcoded passwords, bearer tokens, Authorization headers
- Private key PEM blocks (-----BEGIN RSA/EC/PRIVATE)
- Generic credential patterns (password=, passwd=, secret=, token=)

Zero matches found. All string constants in the new probes are filesystem paths
(`/data/adb/ksu`, `/data/adb/ap/bin/apd`) and system property keys
(`ro.kernelsu.version`, `ro.apatch.kernel_signature`) — none are credentials.

**credentials_blockers**: 0

---

## Pillar 2 — Cross-cutting #1 Namespace Compliance

**Tool**: `python3 .ci/check-namespace-compliance.py`

```
Gate 4 — Evidence-key namespace compliance

Tier-A (bare pkg.* regression guard):     0 violations

Tier-B (strict-namespace opt-in probes):  4 opted-in, 0 violations
  OPT-IN [emulator.third_party_artifacts] declares prefix 'third_party_emulator.*'
  OPT-IN [integrity.install_source] declares prefix 'install_source.*'
  OPT-IN [root.apatch] declares prefix 'apatch.*'
  OPT-IN [root.kernelsu] declares prefix 'ksu.*'

PASS: namespace compliance gate green.
```

**Spot-check — KernelSURootProbe.kt**:

Evidence key constants are `ksu.files_hit`, `ksu.version_property`, `ksu.observation_ok`,
`ksu.pattern` — all prefixed `ksu.*`. No bare keys. Cross-cutting #1 satisfied.

**Spot-check — APatchRootProbe.kt**:

Evidence key constants are `apatch.files_hit`, `apatch.signature_property`,
`apatch.observation_ok`, `apatch.pattern` — all prefixed `apatch.*`. No bare keys.
Cross-cutting #1 satisfied.

**namespace_blockers**: 0

---

## Pillar 3 — Panel Consistency

**Tool**: `python3 .ci/check-panel-consistency.py`

```
Gate 3 — Panel consistency
  FullProbeRunnerSpoofTest.allProbes():    84 probes
  CoverageMatrixGeneratorTest.allProbes(): 84 probes
PASS: panels in sync
```

Both panels report 84 probes. Consistent.

**panel_consistency**: 84 == 84 — PASS

---

## Pillar 4 — WeightedScore Invariant

**Tool**: `bash .ci/check-weighted-score.sh`

```
Gate 2 — WeightedScore invariant (RedroidSpoofed aggregate.weightedScore == 0.0000)

detection-cli exposes 'replay-snapshot'; gate is active.

RedroidSpoofed aggregate.weightedScore = 0.0000 (raw: 0.0)
PASS
```

RedroidSpoofed fixture scores exactly 0.0000. Adding the two new kernel-space
probes (KernelSURootProbe, APatchRootProbe) did not regress the spoofed-clean
baseline because ReDroid has neither KSU nor APatch artifacts.

**weighted_score**: RedroidSpoofed = 0.0000 — PASS

---

## Pillar 5 — HardCeiling Honesty (E3)

**File**: `agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/PlayIntegrityOnlineReplayTest.kt`

### KDoc review

The file-level comment block (lines 6–68) contains explicit, load-bearing
disclaimers:

- "No build-prop mutation can produce a Google-signed JWT — only a real
  TEE-attested Pixel device can." (line 16–17)
- "This test does NOT claim that any spoof-stack fixture produces a real
  STRONG verdict." (lines 22–24)
- Scope is explicitly "verdict CLASSIFICATION (declarative inference)" not
  "verdict GENERATION (live signed JWT)" (lines 60–62).

### 5 test assertions reviewed

| Test | Assertion | Honest? |
|------|-----------|---------|
| Fixture 1 — NO_INTEGRITY | `assertEquals(SCORE_NO_GMS, result.score)` + `assertEquals(VERDICT_NO_VERDICT, verdictOf(result))` | YES — classifies, does not claim generation |
| Fixture 2 — BASIC | `assertEquals(SCORE_UNPROVISIONED_CLIENTID, result.score)` + `assertEquals(VERDICT_BASIC_ONLY, verdictOf(result))` | YES — correct CLASSIFY language |
| Fixture 3 — DEVICE | `assertEquals(SCORE_GMS_DOWNGRADE, result.score)` + `assertEquals(VERDICT_DEVICE_BASIC, verdictOf(result))` | YES — correct CLASSIFY language |
| Fixture 4 — STRONG-surface | `assertEquals(VERDICT_CLEAN, verdictOf(result))` + explicit `assertTrue(verdictOf(result) != VERDICT_STRONG_DEVICE_BASIC, "HARD CEILING...")` | YES — probe correctly emits VERDICT_CLEAN, not STRONG |
| Regression (declarative_only) | `assertEquals(true, declarativeOnly, "Every mock fixture must mark itself as declarative-only...")` | YES — anti-Verarschen marker enforced across all 4 fixtures |

Fixture 4's hard-ceiling test is particularly well-formed: the assertion
message names `un-snapshottable.md §1` and `corpus-index §4 row '2'` as
authoritative references, making the contract traceable and reviewable.

No test assertion promotes a mock surface to a real STRONG verdict. The probe
emits `VERDICT_CLEAN` (absence-of-negative-signals) rather than
`VERDICT_STRONG_DEVICE_BASIC` (presence-of-positive-TEE-evidence). Language
is consistently CLASSIFY-not-GENERATE.

**hard_ceiling_honest**: PASS — all 5 checked assertions are honest

---

## Pillar 6 — License Compliance

### Source citations in changed files

**KernelSURootProbe.kt**: Reference is `https://github.com/tiann/KernelSU`.
KernelSU is published under the GNU General Public License v2 (or later).
No source code from the KernelSU repository was copied into this probe.
The probe checks for runtime artifacts (filesystem paths, system property
key name) that are documented publicly in the KernelSU README. This is
interface-level knowledge — not a derivative work.

**APatchRootProbe.kt**: Reference is `https://github.com/bmax121/APatch`.
APatch is published under the GNU General Public License v3. Same analysis
applies: probe checks runtime artifact paths and a system property key.
No verbatim code was copied.

**power-19-magisk-variants.md**: Cites:
- `https://github.com/topjohnwu/Magisk` — GPLv3
- `https://github.com/HuskyDG/magisk-files` — GPLv3 fork
- `https://github.com/1q23lyc45/KitsuneMagisk` — GPLv3 fork-of-fork (archived 2025-08-24)
- ZygiskNext sourced from Dr-TSNG / 5ec1cff — explicitly noted as GPLv3

The document is an inventory and analysis. No source code was copied verbatim;
the content is original research referencing public GitHub repositories.

### Assessment

All cited sources are GPLv3 (or GPLv2+) FOSS repositories. No copyrighted code
was copied into the production codebase. The probes use public API-level
knowledge (file paths, system property names) from the referenced repositories'
README documentation. License exposure: none.

**license_warnings**: 0

---

## Summary

| Pillar | Result | Detail |
|--------|--------|--------|
| Credentials sweep | PASS | 0 blockers — no keys, tokens, passwords, or PEM blocks found |
| Namespace compliance | PASS | 0 violations — ksu.* and apatch.* prefixes correct; CI gate green |
| Panel consistency | PASS | 84 == 84 — both panels synchronized |
| WeightedScore invariant | PASS | RedroidSpoofed = 0.0000 — no regression |
| HardCeiling honesty (E3) | PASS | 5/5 assertions classify, never generate; VERDICT_CLEAN != VERDICT_STRONG_DEVICE_BASIC enforced |
| License compliance | PASS | 0 warnings — GPLv3 FOSS citations only, no verbatim code copied |

**Overall verdict: APPROVE**

All six pillars pass. No blocking issues found. The P19 Phase-E commits are
safe to merge on branch report/CLO-143-weekly-W20.
