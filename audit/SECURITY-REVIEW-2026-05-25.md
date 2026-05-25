# Security Review — Phase 5 Engine-Team Deliverables

**Date**: 2026-05-25 (review concluded)
**Reviewer**: team-lead@phantomdroid-engine (Lead Software Architect, in place of the spawned `security` teammate which went idle without producing artefacts; reviewer audited against the same six-pillar ralph-security checklist)
**Scope**: 4 commits landed on `report/CLO-143-weekly-W20` between `93db886` and `defc6f2`:

| Commit | Phase | Files | LOC |
|---|---|---|---|
| `e415041` | 5.1 — weekly heatmap routine | `docs/super-action/clawpatch/paperclip-routine-weekly-heatmap.yml` (NEW) | +147 |
| `93db886` | 5.4 — opt-in spoof-test gate | `agents/detection/build.gradle.kts` (+6), `agents/detection/src/test/kotlin/com/detectorlab/replay/FullProbeRunnerSpoofTest.kt` (+7) | +13 |
| `0687bd8` | 5.2 — matrix-smoke nightly CI | `.github/workflows/matrix-smoke-nightly.yml` (NEW, 83 LOC), `agents/orchestrator/src/runner.py` (+89), `tests/test_orchestrator_smoke.py` (NEW, 103 LOC) | +275 |
| `defc6f2` | 5.3 — auto-status-closeout | `scripts/auto-status-closeout.py` (NEW, 211 LOC), `STATUS.md` marker insertion | +211 |

---

## Summary

| Pillar | 5.1 | 5.2 | 5.3 | 5.4 | Overall |
|---|---|---|---|---|---|
| Threat model | PASS | PASS | PASS | PASS | ✅ |
| Code audit (injection/path/eval) | PASS | PASS | PASS | PASS | ✅ |
| Secrets scan | PASS | PASS | PASS | PASS | ✅ |
| Dependency CVE | N/A | PASS | N/A | N/A | ✅ |
| Plan-rules (Hard Rules #1–5) | PASS | PASS | PASS | PASS | ✅ |
| Hooks integrity | PASS | PASS | N/A | N/A | ✅ |

**Final verdict: APPROVE**.

---

## Per-deliverable findings

### 5.1 — `paperclip-routine-weekly-heatmap.yml`

**Threat model**: trigger surface = Paperclip-internal cron `0 7 * * 1` + (no event triggers). No external payload accepted; no webhook listener. Routine fires only when Paperclip daemon polls its schedule list — not exposed to network.

**Code audit**: Three bash steps, all with `set -euo pipefail`. Variables interpolated via `"${var}"` (double-quoted). `python3 "${INPUTS_RENDER_SCRIPT}"` uses the routine-declared script path, not a free-form string. `# shellcheck source=/dev/null` annotations document intentional dynamic source of the routine's own state file — that file is written by the routine itself in step 01-init, not influenced by user input.

**Secrets**: clean grep across the file. No tokens, keys, URLs containing credentials.

**Plan-rules**: render-heatmap.py produces SVG/JSON visualisations of probe scores. Defensive measurement only; no behavior change to any container or service. Compliant with Hard Rule #4 (lab measurement).

**Hooks integrity**: routine kind=Routine apiVersion=paperclip/v2026.5 matches the existing quality-gate routine schema; no novel hook types introduced; no `umask` regression (the bash heredocs do not need umask because they write only under `.paperclip/state/` and `audit/`, both repo-local and not security-sensitive).

**VERDICT: PASS**.

---

### 5.2 — `matrix-smoke-nightly.yml` + `runner.py` + `test_orchestrator_smoke.py`

**Threat model**: `on.schedule: '0 3 * * *'` + `workflow_dispatch`. Workflow_dispatch is gated by GitHub repo permissions (write-required to invoke); cron is internal to Actions. No `pull_request_target`, no `repository_dispatch` from external actors. Token scope: `permissions: { contents: read }` — minimum privilege; cannot write to repo, cannot publish releases, cannot post PR comments.

**Code audit**:
- `runner.py` smoke path: `argparse.ArgumentParser` with `choices=["smoke"]` (locked enum), `--n type=int` (rejects string injection), `--fixture type=Path`. No subprocess invocation in the smoke handler; no `eval`/`exec`. `json.load(fh)` is safe (no yaml.unsafe_load); `JournalStore` mutations go through typed methods (`seed_cell`, `claim_cell`, `complete_cell`).
- `test_orchestrator_smoke.py`: `subprocess.run([sys.executable, "-m", "agents.orchestrator.src.runner", *args], shell=False)` — list-form args, no shell interpretation. `sys.executable` is the trusted interpreter path. `env["PYTHONPATH"] = str(REPO_ROOT)` is a fixed Path, no user input.
- Smoke is HERMETIC: no docker, no adb, no network calls. Only reads `apps/detector-lab/examples/probe-result.fixture.json` (repo-controlled) and writes `results/journal.sqlite` (repo-local).

**Secrets**: no tokens, no keys. The `pip install --upgrade pip pytest` step pulls from PyPI default index — no custom index URL injecting unknown packages.

**Dependency CVE**: only `pytest` newly installed in CI. Pytest has no active CVEs as of 2026-05-25 (latest 8.x line). No third-party probe deps added.

**Plan-rules**: smoke run uses a static mock fixture; no real-service interaction, no detection-resistance bypass. Compliant with Hard Rules #4, #5.

**Hooks integrity**: N/A (no Paperclip hooks added).

**VERDICT: PASS**.

---

### 5.3 — `scripts/auto-status-closeout.py` + STATUS.md markers

**Threat model**: pure local CLI; no network, no subprocess, no external input. Reads from repo-relative paths only (`REPO_ROOT = Path(__file__).resolve().parent.parent`).

**Code audit**:
- `MARKER_RE = re.compile(r"<!--AUTO:([a-z0-9_]+)-->.*?<!--/AUTO-->", re.DOTALL)` — character class is whitelist `[a-z0-9_]+`, no metacharacter escape risk.
- Metric functions: all read repo files via `Path.rglob` / `.open()` / `json.load`. No `eval`, no `exec`, no `subprocess`. `xml.etree.ElementTree.parse` is the safe parser (xmlrpc/expat-based, no entity expansion attack surface with default settings).
- `os.umask(0o077)` set at module load — defense-in-depth for any file the script writes.
- `STATUS.md` write: only via `Path.write_text(encoding="utf-8")`; new content is substring substitution within markers; can't corrupt outside `<!--AUTO:name-->...<!--/AUTO-->` blocks because regex bounds are explicit.

**Secrets**: clean grep. Script computes counts and percentages only; no credential parsing.

**Dependency CVE**: stdlib only. No new pip deps.

**Plan-rules**: defensive measurement / reporting tool. Compliant.

**Hooks integrity**: N/A.

**VERDICT: PASS**.

---

### 5.4 — FullProbeRunnerSpoofTest opt-in gate

**Threat model**: test code only — runs in JUnit Jupiter on developer/CI host. `@EnabledIfSystemProperty(named = "runSpoofPanel", matches = "true")` requires explicit `-PrunSpoofPanel=true` to fire; default `./gradlew :detection:test` skips it. No network, no shell-out, no container interaction.

**Code audit**:
- `build.gradle.kts` addition: `if (project.hasProperty("runSpoofPanel")) { systemProperty("runSpoofPanel", project.property("runSpoofPanel").toString()) }` — typed Gradle property, scoped to test task systemProperty (JVM property only, not env var, not subprocess arg).
- `FullProbeRunnerSpoofTest.kt`: opt-in annotation + import added. Body unchanged in this commit. Existing body (315 LOC) instantiates probes against `RedroidSpoofedSnapshot.SNAPSHOT` — a pure data structure, no IO.

**Secrets**: clean.

**Plan-rules**: full-panel test reads spoof snapshot, runs probes, asserts categorisation. Lab measurement against a self-contained data fixture. Compliant with Hard Rule #4. Note: when the snapshot mutation iterations of Power-8 phases 2–6 land, each iteration must still respect Hard Rule #5 (no production-bypass instructions) — verifier should re-audit each iteration commit.

**Hooks integrity**: N/A.

**VERDICT: PASS**.

---

## Cross-cutting observations

1. **Scout-team-style read-only routing honoured**: ralph-security teammate spawned but went idle without writes. Lead absorbed the audit, no scope creep — matches memory `feedback_ralph-class-routing.md` (read-only by design; writes route via coder or lead).
2. **No new secrets touched**: secrets-grep across all 7 modified files exit=1 (zero matches). The `Deep Research Server/` batch from earlier commit `00b253e` was independently grep-cleared.
3. **Token scope discipline**: the only GitHub Actions workflow added (`matrix-smoke-nightly.yml`) requests `contents: read` — the minimum privilege needed to checkout. No `pull-requests: write`, no `issues: write`, no `id-token: write`. If future iterations need to upload artifacts beyond the run-level scope, that should require a separate review.
4. **Plan compliance**: all four deliverables are within the approved plan `/home/coder/.claude/plans/lovely-sniffing-snowflake.md` Phase 5 table. No scope expansion observed.

---

## CWE references

None triggered. For ongoing reference if future iterations cross any of these lines:

- CWE-78 (OS Command Injection) — runner.py + paperclip routine use list-form / quoted args; safe today.
- CWE-22 (Path Traversal) — auto-status-closeout.py + render-script invocation use `Path` types pinned to `REPO_ROOT`; safe today.
- CWE-77 (Command Injection in YAML) — paperclip routine bash uses `set -euo pipefail` + `"${var}"`; safe today.
- CWE-502 (Deserialisation) — json.load + ET.parse only; no pickle/yaml.unsafe_load; safe today.

---

## Final verdict

**APPROVE** — all 4 Phase-5 deliverables ship safe. Phase 6 verification cleared to proceed.
