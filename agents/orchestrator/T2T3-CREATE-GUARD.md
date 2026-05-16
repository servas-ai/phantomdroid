# Orchestrator Guard — T2/T3 Issue-Create Policy (CLO-109 v1)

**Status:** Active (v1 — lint-gate). v2 (create-hook) tracked separately.

## Rule

The Orchestrator MUST NOT call `paperclipai issue create` for a T2 or T3 issue
unless the description contains a well-formed acceptance block conforming to
`docs/acceptance-block-spec.md §3`.

## v1 Enforcement (pre-`done` gate)

Before setting any T2/T3 issue to `done`, run:

```bash
bash scripts/lint-issue-acceptance.sh --issue <IDENTIFIER>
```

If this exits non-zero, **do not transition to `done`**. File a tier-1 child
to add the acceptance block, then re-run after the child closes.

## v1 Create-time check (manual)

When creating a new T2/T3 issue, the Orchestrator MUST:

1. Draft the description using `agents/orchestrator/ISSUE-TEMPLATE-T2-T3.md`.
2. Run `bash scripts/lint-issue-acceptance.sh --issue <NEW_ID>` immediately after
   creation (within the same heartbeat).
3. If the lint fails, update the description to add the missing block before
   posting any downstream work.

## Anti-rationalization

| Excuse | Rebuttal |
|---|---|
| "I'll add the block in the next heartbeat." | No. The block is the spec. Author it before the issue is visible to teammates. |
| "This T2 is trivial — the acceptance is obvious." | Obvious to you, not to the CI runner. Write it down. |
| "The block would just be `cmd: "true"`." | That is still a valid block. Write it. |

## v2 roadmap

When `paperclipai issue create` gains a `--pre-create-hook` surface, wrap all
orchestrator create calls via a thin script that invokes
`lint-issue-acceptance.sh --issue <draft_id>` before the POST reaches the API.

Owner: Orchestrator. Filed as follow-up child of CLO-109 after board confirms v1.
