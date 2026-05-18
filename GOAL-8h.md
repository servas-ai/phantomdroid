# GOAL — 8h Autonomous Run

**Start**: 2026-05-19 01:30 CEST
**End**: 2026-05-19 09:30 CEST
**Owner**: Martin (a@servas.ai)
**Working branch**: `report/CLO-143-weekly-W20`

---

## The Goal (one sentence)

> **In 8 hours, bring `report/CLO-143-weekly-W20` to a shippable, tag-ready state — green tests, clean tree, triaged branches, documented server-recovery outcome — without dependency on the broken external server PAR822349.**

---

## Why this goal

The repo is mid-flight on the CLO-W20 weekly report. Dirty working tree (8 modified + 9 untracked audit files), 12 open `feat/CLO-*` branches with mixed maturity, server PAR822349 unreachable but reinstall owner-approved. A coherent shippable snapshot is the highest-leverage 8h outcome — every track below feeds into it.

---

## Acceptance criteria (binary, observable)

| # | Criterion | How to verify |
|---|---|---|
| 1 | Working tree clean on `report/CLO-143-weekly-W20` | `git status --short` returns empty |
| 2 | All 9 audit MDs from 2026-05-17/18 consolidated into one canonical artifact | File `audit/recovery-2026-05-19-FINAL.md` exists + obsolete MDs archived under `audit/archive/2026-05-18/` |
| 3 | All 12 `feat/CLO-*` branches have a triage status | New file `docs/branch-triage-2026-05-19.md` lists each branch with one of: `merged`, `wip-keep`, `archived` |
| 4 | `agents/detection` gradle test suite passes | `./gradlew :agents:detection:test` exits 0 (or documented blocker if gradle env unavailable) |
| 5 | CLO-19 TikTokArgusSigningProbe test relocation is consistent | Old test file deletion + new test file commit are in same commit; class path matches |
| 6 | README current goal block matches actual state at T+8h | Manual visual check; status updated at end |
| 7 | Server reinstall is triggered OR failure is documented in ticket #94047858 | Either: panel shows reinstall in progress/done, OR a new ticket reply with reinstall failure logs exists |
| 8 | 8h status report written | File `audit/8h-status-2026-05-19.md` documents: completed, partial, blocked, next |
| 9 | Tag created if all 1–8 green | Tag `weekly-W20-2026-05-19` on HEAD of `report/CLO-143-weekly-W20` |

---

## Non-goals (explicit out-of-scope)

- Fixing the HP P410 RAID controller (hardware, provider-side)
- Re-architecture of the agent system
- Adding new CLO probes that don't already have an open branch
- Implementing the 74 remaining TODO probes from `agents/detection/README.md:49`
- Touching `main` branch (working only on `report/CLO-143-weekly-W20`)
- Express/VIP escalation click (voucher risk; needs separate go)
- Server cancellation, BIOS/RAID-level change, additional IP purchase
- Paid external API calls (Tavily, Firecrawl, Replicate)

---

## Plan reference

Tactical 8h plan with track-by-track steps: [`audit/8h-autonomous-plan-2026-05-19.md`](audit/8h-autonomous-plan-2026-05-19.md)

Tracks A–F map to acceptance criteria:
- A (Reinstall) → #7
- B (Repo hygiene) → #1, #3
- C (Audit consolidation) → #2, #6
- D (Probes/tests) → #4, #5
- E (Stack/threat-model doku) → #1
- F (Wrap-up) → #8, #9

---

## Stop conditions

- All 9 acceptance criteria green → tag + close out early
- Hard blocker that needs owner input (e.g. credentials, destructive action approval) → write blocker doc, pause that track, continue others
- 8h elapsed → write status report regardless

---

## Owner inputs needed up-front

None blocking — defaults are:
- Reinstall OS: **Ubuntu 22.04 LTS amd64**
- Reinstall partitioning: **default (provider standard)**
- Reinstall root password: **provider-generated, read via panel "Mostrar"**

If owner wants different OS / partitioning / pw scheme, override here before T+0:30.
