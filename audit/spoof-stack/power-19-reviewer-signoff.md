# Power-19 Phase-E — Reviewer Sign-Off

**Date**: 2026-05-21
**Reviewer**: ralph-reviewer (Team-Lead spawn, P19 endgate)
**Scope**: commits 0b4de25..HEAD — P19-E1 (f347189), P19-E3 (0fa53ed), P19-E2 (968b056)
**Verdict**: **APPROVE-PHASE-E**

---

## §1. Criterion Pass Matrix

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | E1 Magisk-variants HONEST-LIMITED research doc | **PASS** | `power-19-magisk-variants.md` §3 explicit "APK bytecode of Delta/Kitsune NOT disassembled" disclaimer; per-fork HIGH / MODERATE / MODERATE-LIMITED verifiability markers; collision-flag for rank 3.85 documented with owner-decision text |
| 2 | E2 cross-cutting #1 (evidence namespacing) | **PASS** | `KernelSURootProbe.kt` lines 122-125 — all four evidence keys prefixed `ksu.*`; `APatchRootProbe.kt` lines 125-128 — all four evidence keys prefixed `apatch.*` symmetrically |
| 2 | E2 cross-cutting #7 (fractional rank via inventoryRank Double override) | **PASS** | `KernelSURootProbe.kt:79` — `override val inventoryRank = 3.6` alongside `override val rank = 94`; `APatchRootProbe.kt:82` — `override val inventoryRank = 3.85` alongside `override val rank = 95` |
| 3 | E3 hard-ceiling honest-scope | **PASS** | `PlayIntegrityOnlineReplayTest.kt` lines 5-69 — file-level "HARD CEILING DISCLAIMER" references `un-snapshottable.md §1` + `spoof-stack-corpus-index.md §4 row "2"`; "verdict CLASSIFICATION (declarative inference) tested here. Verdict GENERATION (live signed JWT) is mitigation_layer L0 — out-of-scope" |
| 4 | Test count progression 4174 → 4241 (+67) | **PASS** | XML aggregate post-E2: 4241/0 |
| 4 | Probe count 82 → 84 (+2) | **PASS** | inventory.yml entries at lines 537-543 (rank 3.85 root.apatch) and 569-575 (rank 3.6 root.kernelsu) |
| 4 | weightedScore RedroidSpoofed = 0.0000 invariant | **PASS** | Both new probes gate on artifact ABSENCE in RedroidSpoofedSnapshot → SCORE_CLEAN (0.0) |
| 5 | Rank 3.85 collision documented + carry-over | **PASS** | Triple-documented: power-19-magisk-variants.md §2.3 + §4 B3-M2 + git commit 968b056 footer |
| 6 | plan-immutability — no edits to P14-P18 closeouts | **PASS** | Only NEW files written under P19 namespace |

---

## §2. Collision Documentation Verification

Rank-3.85 collision between E1's PROPOSED `root.mount_ns_multipid_scan` and E2's SHIPPED `root.apatch` is documented in **THREE distinct loci**:

| Locus | Source | Treatment |
|---|---|---|
| 1 | E1 research doc §2.3 (recognition-needs table) | Inline "see §4 collision note" pointer |
| 2 | E1 research doc §4 B3-M2 row | Explicit "**~3.85 (collision-flag)**" + owner-decision text proposing re-rank to ~3.87 OR merge |
| 3 | E2 commit message 968b056 footer | "COLLISION NOTE" paragraph naming E1 commit hash |

Owner-decision proposed: re-rank `mount_ns_multipid_scan` to **~3.87** (free slot between 3.85 APatch and 3.9 magisk_module_dir) OR merge SuList topology scan into existing rank-3.8 MountNsMismatchProbe as an extension.

**collision_documented**: **TRUE**

---

## §3. Honest-Limited Disclosure Audit (E1)

E1 satisfies anti-verarschen discipline:

- §3 disclaims APK-bytecode-NOT-disassembled
- §3 per-fork verifiability: Magisk-canonical HIGH, Magisk-Delta MODERATE (XDA-rumor flagged PARTIAL), KitsuneMagisk MODERATE-LIMITED (repo archived 2025-08-24)
- §3 explicitly out-of-scopes private downstream forks (vivo-suu, scorpion-2) at L5
- §4 ships all B3-E4/B3-E5 fork-literal extensions behind PARTIAL marker
- §5 confidence breakdown: "NONE on APK-bytecode-verification (out-of-scope)"

No fabricated source claims.

---

## §4. E3 Hard-Ceiling Audit

PlayIntegrityOnlineReplayTest faithfully implements un-snapshottable.md §1 contract:

- File-level disclaimer is load-bearing — "No build-prop mutation can produce a Google-signed JWT"
- Fixture 4 STRONG-clean deliberately does NOT carry a JWT; probe emits VERDICT_CLEAN
- Regression test codifies "declarative probe MUST NEVER claim STRONG_DEVICE_BASIC without a real Google JWT"
- Separate hard-ceiling test asserts `declarative_only = true` across ALL fixtures

Honest scope contract: verdict CLASSIFICATION tested here; verdict GENERATION is mitigation_layer L0 — out-of-scope for JVM-pure detection module by design.

---

## §5. Blockers

**NONE**.

---

## §6. Carry-Overs to Power-20

| # | Item | Source | Disposition |
|---|---|---|---|
| C20-1 | Rank 3.85 collision — re-rank `root.mount_ns_multipid_scan` to ~3.87 OR merge into rank-3.8 MountNsMismatchProbe as SuList extension | E1 §4 + E2 commit collision-note | Owner-decision required |
| C20-2 | B3-M1 new probe `runtime.zygote_tracer_pid` rank ~3.65 (ZygiskNext TracerPid detection) | E1 §4 | MODERATE effort: new `ProbeContext.queryProcStatus(pid)` accessor + DeviceSnapshot.procStatusByPid field |
| C20-3 | B3-E1..B3-E7 PARTIAL-extensions to rank-3/3.5/3.7/3.8/3.9 probes (Delta/Kitsune package id, /apex su path, fork-literal substrings, kitsune_data paths) | E1 §4 | All TRIVIAL — ship behind PARTIAL marker |
| C20-4 | Power-18 carry-overs C2/C3/C4 still open (cross-cutting #7 Probe.rank Int→Double migration RFC; Tier-B strict-suffix namespace; OB1 PAR822349 reboot) | Power-18 closeout §10 | Carried forward verbatim |

---

## §7. Verdict

**APPROVE-PHASE-E**.

All 9 criteria pass. Rank-3.85 collision is triple-documented with explicit owner-decision text. No edits to P14-P18 closeouts. weightedScore RedroidSpoofed=0.0000 invariant preserved. E3 hard-ceiling contract codified as regression test. E1 honest-limited disclaimer rigorous (APK-bytecode-not-disassembled explicit).

Phase-E is clean to close. Carry-overs C20-1..C20-4 documented for Power-20.
