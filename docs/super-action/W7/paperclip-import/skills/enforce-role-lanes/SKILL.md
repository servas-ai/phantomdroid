---
name: enforce-role-lanes
description: Consult before Orchestrator accepts or checks out a Paperclip issue. Refuses cross-lane assignments, especially probe implementation work that belongs to Detection Oracle.
---

# Enforce Role Lanes

Use this skill before Orchestrator accepts, checks out, or continues an issue when
the issue title or description may require implementation outside the
Orchestrator lane.

## Contract

Orchestrator must refuse an assignment with reason `lane-violation` when either
condition is true:

- The title or description requires probe implementation work, including
  `G-PROBE-*`, `implement ... probe`, or `probe implementation`.
- The title or description names write scope under `agents/<other-agent>/src/`.

Probe implementation issues route to Detection Oracle. Stability source work
routes to Stability. Orchestrator may coordinate child issues, aggregate results,
maintain journals, and edit `agents/orchestrator/src/`.

## Procedure

Run the local pre-checkout guard from the repository root:

```bash
bash scripts/governance/check-role-lane.sh \
  --role orchestrator \
  --title "$ISSUE_TITLE" \
  --description "$ISSUE_DESCRIPTION"
```

If the command exits `78` and prints `lane-violation`, do not start repo work.
Mark the issue blocked or re-route it to the owning lane with a comment that
includes the literal reason `lane-violation`.

If the command exits `0`, continue with the normal Paperclip checkout and
heartbeat procedure.

## Acceptance

The role-lane contract is covered by:

```bash
bash scripts/governance/test-role-lanes.sh
```
