# Library Overview Reviewer Sign-Off — VERDICT: APPROVE-WITH-CORRECTIONS

**Reviewer**: ralph-reviewer (REVIEWER endgate, read-only by design)
**Date**: 2026-05-29
**Document under review**: `docs/SPOOFSTACK-LIBRARIES.md`
**Filed by**: orchestrating agent (reviewer environment had Read/Grep/Glob only; signoff content delivered verbatim, committed here).
**Sources of truth checked**: `agents/stability/stack/layers.md`, `agents/stability/stack/compose/*.yml`, `audit/spoof-stack/spoof-stack-corpus-index.md`, `STATUS.md`, `agents/detection/src/probes/**`, `agents/detection/src/test/**`, `agents/detection-cli/**`, `agents/stability/stack/modules/cpuinfo-overlay/**`, `stack/L4/hide-frida-maps/**`, `infrastructure/spoof-stack-magisk/**`

**Headline**: The document is substantially accurate. Every layer→library→probe mapping matches `layers.md` verbatim, the ReDroid digest is correctly pinned, the 3 hard ceilings match the corpus index, and the probe/test counts match STATUS.md. Six corrections were required (applied 2026-05-29): one wrong tag suffix, one unsupported Magisk reference count (116→24), two understated/mischaracterized "scaffold" labels for modules that are in fact substantial, one scorecard undercount (2→3 in-house modules), and one uncorroborated/mis-metric'd score figure in §9 (independently confirmed by the tester).

---

## §1. Claim Verification Matrix

| # | Doc location | Claim | Status | Evidence / Exact fix |
|---|---|---|---|---|
| 1 | §1 L0 | ReDroid digest `sha256:e6f799d5…ef55d3`, digest-pinned in all compose files | **PASS** | Full digest present in all 8 service-compose files. `L1-props.yml` is a module-registration file, not a service compose. |
| 2 | §1 L0 | ReDroid version `12.0.0_64only` | **CORRECTED** | Canonical tag per `layers.md:26` / `L1.compose.yml:64` is `12.0.0_64only-latest`. |
| 3 | §1 L0 | Magisk "referenced 116× in compose" | **CORRECTED** | Actual Magisk references across `compose/*.yml` = **24** (7 files). 116 was a repo-wide grep (incl. docs/), not compose-only. |
| 4 | §1 L0 | ReDroid "booted on PAR822349 2026-05-20" | **PASS** | Corroborated by `audit/E2E-validation-2026-05-20.md` + `L0a-RUNBOOK.md:9`. |
| 5 | §1 L0 | anbox-modules ✅ IMPL (binderfs/HWE reboot limit = OB1) | **PASS** | Consistent with L0a-RUNBOOK binder-passthrough + documented host-kernel limit. |
| 6 | §2 L1 | cpuinfo-overlay ✅ IMPL (module.prop + service.sh + spoofed cpuinfo + profile-check test) | **PASS** | All four artifacts exist with real implementation. Not a skeleton. |
| 7 | §5 L4 | hide-frida-maps source path `stack/L4/hide-frida-maps/` | **PASS** | Repo-root path correct (NOT `agents/stability/stack/L4/`). |
| 8 | §5 L4 | hide-frida-maps "✅ IMPL (… skeleton)" | **CORRECTED** | `HideFridaMapsHook.kt` = full 192-line impl + complete `RedactionPatterns.kt`. "skeleton" understated. |
| 9 | §5 L4 | spoof-stack-magisk "✅ IMPL (module.prop scaffold)" | **CORRECTED** | Substantial functional Magisk module: post-fs-data + service.d resetprop/settings + sysfs-binds + system/ magic-mount tree. Not a scaffold. |
| 10 | §8 | 86 probes (target 72; +19%) | **PASS (with nuance)** | Exactly 86 probe files. Runnable registries smaller: FullProbeRunnerSpoofTest=84, detection-cli ProbeRegistry=65. |
| 11 | §8 | 4,241 unit tests green; CI floor ≥3,000 | **PASS** | Matches STATUS.md:105 exactly. |
| 12 | §8 | detection-cli path | **PASS** | All 7 CLI source files present. |
| 13 | §8 | FullProbeRunnerSpoofTest 84-probe opt-in → CLEAN | **PASS** | `assertEquals(84, …)`, `@EnabledIfSystemProperty(named="runSpoofPanel")`, asserts CLEAN. |
| 14 | §8 | 8 snapshot fixtures | **PASS** | All present under `src/test/.../replay/` + `src/core/replay/`. |
| 15 | §9 | "2 fully functional Magisk/Xposed modules" | **CORRECTED** | **3** in-house modules: cpuinfo-overlay, hide-frida-maps, spoof-stack-magisk. |
| 16 | §9 headline | "65-probe E2E panel mean score 0.263 → 0.000" | **CORRECTED** | (a) No 65-probe E2E panel: E2E test=8-probe, spoof panel=84-probe, 65=CLI registry. (b) 0.263 = per-probe-mean; canonical weightedScore = 0.3462 (committed) / 0.3697 (fresh CLI). |
| 17 | §10 | 3 hard ceilings (9.7, 9.8, 6 STRONG) | **PASS** | Matches `spoof-stack-corpus-index.md §4` exactly. |
| 18 | §2–§7 | All layer→library→probe mappings | **PASS** | Verbatim match to `layers.md`. No mapping errors. |

---

## §2. Required Corrections (applied to `docs/SPOOFSTACK-LIBRARIES.md` 2026-05-29)

1. L0 ReDroid version: `12.0.0_64only` → `12.0.0_64only-latest`.
2. L0 Magisk reference count: `116×` → `24× across compose files`.
3. L4 hide-frida-maps: drop "skeleton" → Java-layer hooks complete; native shadowhook .so referenced but not vendored.
4. L4 spoof-stack-magisk: drop "module.prop scaffold" → functional Magisk module (post-fs-data + service.d + sysfs-binds + magic-mount); companion LSPosed module still SPEC.
5. §9 scorecard: "2 fully functional" → 3 in-house modules; remove "scaffold."
6. §9 headline score line: use canonical weightedScore 0.346 → ~0.000; disambiguate panel attribution (E2E=8, spoof=84, CLI=65); do not present per-probe-mean as canonical.

## §3. Optional (non-blocking)

- §8: add footnote distinguishing 86 probe files / 84 runnable / 65 CLI to prevent downstream conflation.

---

**Disposition**: APPROVE-WITH-CORRECTIONS. None of the corrections touch a layer→library→probe mapping or the hard-ceiling accounting (all PASS). The §9 score correction was the most material — the document's only quantitative headline claim, both wrong-metric and uncited; the tester's independent finding (weightedScore 0.346 vs per-probe-mean 0.263) confirms it.
