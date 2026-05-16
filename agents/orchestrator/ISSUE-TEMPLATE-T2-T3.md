# Issue Template — Tier-2 / Tier-3

> **Required.** Copy this template when creating any T2 or T3 issue.  
> The acceptance block is machine-parsed by `scripts/lint-issue-acceptance.sh`.  
> A missing or malformed block causes the pre-`done` gate to fail (CLO-109).

## [SHORT_TITLE] — [issue-ref]

**Tier:** T2 (or T3)  
**Layer:** <!-- e.g. L1, L4, cross -->  
**Priority:** <!-- critical | high | medium | low -->

<!-- ONE paragraph: what problem does this issue solve and why now? -->
PROBLEM: …

<!-- Concrete deliverables — keep to 3–5 bullets. -->
DELIVERABLES:
- …
- …

<!-- If T3: add human-signoff gating rationale per CLO-110 §3. -->

## Acceptance block (required for T2/T3)

```yaml acceptance
cmd: "bash scripts/your-check-here.sh"
expect_exit: 0
expect_json_jq: null
runs_on: "ci-linux-x86_64"
timeout_sec: 300
```

<!-- Field reference: docs/acceptance-block-spec.md §3 -->
<!-- runs_on enum: ci-linux-x86_64 | ci-linux-arm64 | dev-host-only | redroid-sandbox | online-net-live -->
<!-- expect_json_jq: jq expression against cmd stdout (set null to skip) -->

## Dependencies / blockers

- [ ] …
