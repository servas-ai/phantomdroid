# Security Review — Power-22 Engine-Team Deliverables

**Date**: 2026-05-26
**Reviewer**: team-lead@phantomdroid-engine (Lead Software Architect, in place of `security2` teammate which went idle without claiming Task #10 — consistent pattern with the prior `security` teammate; lead absorbed audit duty per memory `feedback_ralph-class-routing.md`).
**Scope**: 4 commits landed on `report/CLO-143-weekly-W20`:

| Commit | Phase | Files | LOC |
|---|---|---|---|
| `9409a71` | P22.2 — orchestrator pytest PR gate | `.github/workflows/orchestrator-test.yml` (NEW), `STATUS.md` row flip | +57 |
| `ad7ff18` | P22.4 — dependabot config | `.github/dependabot.yml` (NEW), `STATUS.md` row flip | +44 |
| `b035312` | P22.1 — gradle artifact refresh + test_count marker | `agents/detection/build/test-results/**` (98 XMLs regenerated), `scripts/auto-status-closeout.py` (caveat comment updated), `STATUS.md` line 105 (marker added) | +~12 effective LOC, +98 generated XMLs |
| `ea5f5d4` | P22.3 — L0a-dedicated RUNBOOK | `agents/stability/stack/L0a-RUNBOOK.md` (NEW), `STATUS.md` scoreboard + pillar rows | +~250 docs |

---

## Summary

| Pillar | P22.1 | P22.2 | P22.3 | P22.4 | Overall |
|---|---|---|---|---|---|
| Threat model | PASS | PASS | N/A | PASS | ✅ |
| Code audit (injection/path/eval) | PASS | PASS | N/A (docs) | PASS (YAML schema) | ✅ |
| Secrets scan | PASS | PASS | PASS | PASS | ✅ |
| Dependency CVE | N/A | PASS | N/A | N/A | ✅ |
| Plan-rules (Hard Rules #1–5) | PASS | PASS | PASS | PASS | ✅ |
| Hooks integrity | N/A | PASS | N/A | PASS | ✅ |

**Final verdict: APPROVE**.

---

## Per-deliverable findings

### P22.1 — `b035312` gradle artifact refresh + test_count marker

**Threat model**: pure-local artifact regeneration. `./gradlew :detection:clean :detection:test` regenerated 98 XMLs in `agents/detection/build/test-results/` (sum tests=4,241). Build dir is git-ignored; the commit body includes the script + STATUS.md changes only, not the generated artifacts. No network surface.

**Code audit**: `scripts/auto-status-closeout.py` METRICS dict gained `"test_count": metric_test_count` entry, plus a documentation comment explaining the partial-suite caveat with the operator diagnostic command. Regex `<!--AUTO:([a-z0-9_]+)-->.*?<!--/AUTO-->` unchanged. No new subprocess/eval surface.

**Secrets**: clean grep.

**Plan-rules**: defensive measurement infrastructure refresh; no behavior change. Compliant.

**VERDICT: PASS**.

---

### P22.2 — `9409a71` orchestrator-test.yml

**Threat model**: triggers = `pull_request` + `push: branches: [main]`, both `paths`-filtered to orchestrator/test paths. `pull_request` (not `pull_request_target`) — safe default for untrusted PR contributions (runs in fork-context with no secret access). No `workflow_run` or `repository_dispatch` from external actors. `workflow_dispatch` not declared, so no manual trigger surface.

**Code audit**:
- `permissions: contents: read` — minimum privilege. No `pull-requests: write`, no `id-token: write`.
- Step `set -euo pipefail` in bash blocks; `pytest tests/test_orchestrator_*.py -v --junitxml=...` uses pytest's safe glob expansion via the shell, paths are repo-controlled.
- Actions pinned to versions: `actions/checkout@v4`, `actions/setup-python@v5`, `actions/upload-artifact@v4` (verified in second half of the file). Major-version pinning is the accepted GitHub Actions convention; for stricter supply-chain hygiene, switch to SHA pinning in a future hardening pass.
- 10-min timeout caps resource use.
- Artifact retention: 14 days. Sensible.

**Secrets**: clean. Only `secrets.GITHUB_TOKEN` is implicitly available (read-only per `permissions`), no other secret references.

**Dependency CVE**: only `pytest` installed via `pip install --upgrade pip pytest`. No CVEs as of audit date. The upgrade pattern (`pip install --upgrade pip`) is intentional — pulls the latest patched pip before installing pytest. Acceptable.

**Plan-rules**: defensive CI hook; gates the orchestrator code at PR boundary. Compliant.

**Hooks integrity**: standard GH Actions schema; no novel hook types.

**VERDICT: PASS**.

Optional hardening for follow-up: pin actions to commit SHAs instead of major version tags. Not a current vulnerability.

---

### P22.3 — `ea5f5d4` L0a-RUNBOOK.md

**Threat model**: pure documentation, no executable content.

**Code audit**: N/A. Markdown only. Reviewed for prose containing exploit-language; none present — sticks to the repo's defensive-research tone. Code blocks are illustrative provisioning commands (modprobe, docker pull/run) matching `audit/E2E-validation-2026-05-20.md` exactly; no novel attack surface introduced.

**Secrets**: clean grep. No URLs containing credentials; the pinned ReDroid SHA is a public docker image digest.

**Plan-rules**: ReDroid baseline RUNBOOK is squarely defensive-research lab measurement (Hard Rule #4). No production-bypass instructions, no service-evasion guidance. Compliant.

**VERDICT: PASS**.

---

### P22.4 — `ad7ff18` dependabot.yml

**Threat model**: dependabot runs in GitHub's managed context. The config declares which ecosystems get scanned (github-actions, pip, gradle), not what dependabot can DO with the results. Dependabot's actual permissions are controlled by the repo's `Settings → Code security and analysis` panel, not by this YAML.

**Code audit**: schema v2, 3 entries each with `interval: weekly`, `day: monday`, `time: "08:00"`, `timezone: "UTC"`. No `target-branch` override (defaults to default branch, which is `main` — correct). No `pull-request-branch-name.separator` or `commit-message.include` extras that could be abused. No `registries:` override → only public ecosystems (Maven Central for gradle, PyPI for pip, GitHub's own action registry). No `allow:` or `ignore:` carve-outs that could permit malicious dep updates to slip through.

**Secrets**: clean.

**Plan-rules**: supply-chain hygiene; closes the explicit ❌ MISSING loop in STATUS.md. Compliant.

**Hooks integrity**: dependabot is a GitHub-native feature with its own audit trail. No custom hook surface added.

**VERDICT: PASS**.

---

## Cross-cutting observations

1. **STATUS.md edits across 3 commits stayed non-overlapping**: P22.1 owned test_count + e2e_loops_automated markers; P22.2 owned "Python orchestrator pytest" inventory row; P22.3 owned runbook_count marker + "SpoofStack layers with RUNBOOK" scoreboard row + pillar Stability row; P22.4 owned "Branch triage / auto-merge / dependabot" inventory row. No edit collisions, no race conditions.
2. **No `workflow_dispatch` added in P22.2** — orchestrator pytest is PR-only by design. The matrix-smoke-nightly.yml still has manual `workflow_dispatch` (intentional, for ad-hoc smoke runs). Pattern is consistent: each workflow's trigger surface matches its purpose.
3. **Pinned action versions** — all GH Actions references use major-version tags (`@v4`, `@v5`), not floating `@latest`. Industry-standard practice. SHA-pinning would be the next hardening step but is out of Power-22 scope.
4. **No new secrets touched** — secrets-grep across all 4 deliverable files exit=1 (zero matches).
5. **Ralph-security routing pattern reconfirmed**: 2 consecutive cycles (Phase 5 + Power-22) saw the ralph-security teammate go idle without claiming the audit task. Memory `feedback_ralph-class-routing.md` should be promoted to a stronger rule: "Always route security audits to team-lead by default; spawn ralph-security only for review of EXISTING audit docs, not for production of new ones."

---

## CWE references

None triggered. Reference-list for next cycle:
- CWE-78 (OS Command Injection) — orchestrator-test.yml bash uses `set -euo pipefail`; safe.
- CWE-829 (Inclusion of Functionality from Untrusted Control Sphere) — actions pinned to versioned tags; partial mitigation, SHA-pinning is the strict form.
- CWE-1059 (Insufficient Technical Documentation) — L0a-RUNBOOK closes one observed gap (the L0a scoreboard 🟡); not a vulnerability, but a hygiene win.

---

## Final verdict

**APPROVE** — all 4 Power-22 deliverables ship safe. Power-23 cycle cleared to plan.
