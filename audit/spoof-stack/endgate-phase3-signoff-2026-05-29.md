# Endgate Phase-3 Signoff — 2026-05-29

**Reviewer:** ralph-reviewer (endgate, read-only adversarial). **Filed by:** orchestrator (reviewer had no Write tool).
**Method:** static cross-check of committed source + artifacts; live re-runs (pytest, gradle panel) closed separately by the lead — see footer.

## Verdicts
| Track | Verdict |
|---|---|
| T1 — Orchestrator matrix (local) | **APPROVE-WITH-CORRECTIONS** (2 minor doc/path clarifications) |
| T2 — Live-sweep (booted container) | **APPROVE** |
| T3 — APK-deliver (in-container) | **APPROVE** |
| Cross-track overclaim check | **NONE — all three honestly bounded** |

## T1 — Orchestrator matrix
- report_validator.py present, stdlib-only, rejects bool-as-numeric + missing aggregate (tests confirm). ✅
- `--matrix replay` is **explicitly labeled a data projection** (audit + help text + docstrings: "no docker, no adb"), NOT a real per-cell probe run → no overclaim. ✅
- Heatmap `W15/heatmap/22/heatmap.json`: 5 green (0.0) + 4 amber (0.346) + 0 grey = 9 non-grey. ✅
- runner change additive (replay branch before smoke logic; smoke handler + test untouched). ✅
- **Corrections (non-blocking):** (1) `default_cells_path()` now resolves to W15 (was W14 at render time) — document the Wn→Wn+1 path-drift so the next sweep doesn't silently retarget. (2) "41 tests (was 18)" = full `tests/` dir (39 orchestrator-specific); state precisely.

## T2 — Live-sweep
- Booted yml byte-identical on build/identity props to pre-boot → weightedScore 0.3462 / 4-critical / DETECTED reproduces deterministically. ✅
- `getenforce=Disabled` correctly characterized as a NEW shell-only tell NOT consumed by the replay model (root.selinux scores 0.30 off empty props regardless). ✅
- "byte-identical" claim honestly carves out `/proc/version` (4.15→5.4, still scores 0.70). ✅

## T3 — APK-deliver
- Confirmed: NO DetectorLab APK module (settings.gradle.kts registers only :detection [jvm] + :detection-cli [application/jvm]). Only `com.android.application` is the LSPosed *evasion* app (io.spoofstack.redroid); hide-frida-maps is a library. ✅
- in-container-report-2026-05-29.json: weightedScore 0.33705… (=0.3371) / 4-critical / DETECTED, passes report_validator schema. Delta vs replay (0.3462) explained by live adb-shell inputs (adb_enabled=1, present android_id, in-process probes <unavailable>). ✅
- Remaining gap accurately scoped: IMEI/MAC/PlayIntegrity/TracerPid need a `:detector-app` (com.android.application) with AndroidProbeContext; install/launch/pull path already verified working. ✅

## Live verification footer (lead, 2026-05-29 — closes reviewer LIVE-UNVERIFIED flags)
- **VERIFIED-BY-RUN**: `PYTHONPATH=. python3 -m pytest tests/ -q` → **41 passed in 2.64s**. Heatmap artifact `docs/super-action/W15/heatmap/22/heatmap.json` present (3×3 matrix, 9 cells). The reviewer's LIVE-UNVERIFIED flag on the test count is now closed.
