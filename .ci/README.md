# Power-18 D2 — CI Quality Gates

4 blocking gates per PR:

| Gate | Script | Purpose |
|---|---|---|
| 1 | `.github/workflows/detection-test.yml` (existing) | `:detection:test` + `:detection-cli:test` 0 failures |
| 2 | `.ci/check-weighted-score.sh` | RedroidSpoofed `aggregate.weightedScore == 0.0000` via `detection-cli replay-snapshot` |
| 3 | `.ci/check-panel-consistency.py` | `FullProbeRunnerSpoofTest.allProbes()` ≡ `CoverageMatrixGeneratorTest.allProbes()` (same count + same probe-list) |
| 4 | `.ci/check-namespace-compliance.py` | Evidence-key prefix compliance (cross-cutting #1): two-tier — regression guard against bare `pkg.*` + strict-namespace opt-in for probes that declare a prefix |

## Run all 4 gates locally

```bash
./gradlew :detection:test :detection-cli:test
.ci/check-weighted-score.sh
python3 .ci/check-panel-consistency.py
python3 .ci/check-namespace-compliance.py
```

All gates must exit 0. Any non-zero exit blocks PR merge.

## Source-of-truth references

- Gate 2 invariant: `audit/spoof-stack/power-15-closeout.md §2` (`weightedScore RedroidSpoofed = 0.0000` invariant preserved)
- Gate 3 panel parity: `audit/spoof-stack/power-15-reviewer-signoff.md §1` (panel-sync requirement)
- Gate 4 namespacing: `audit/cross-cutting-followups-2026-05-19.md #1` (fixed 2026-05-20 baseline)
