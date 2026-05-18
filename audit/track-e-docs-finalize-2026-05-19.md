# Track E — Stack/Threat-Model Doku Finalize (2026-05-19)

Branch: `report/CLO-143-weekly-W20`. No commits issued — Track B will commit.

## Per-file review

### 1. `shared/threat-model.md`

- **Diff summary**: Adds one new top-section "Nutzungsgraenze" (8 lines, German) declaring defensive-research scope and that kernel/root/sensor/network/TLS suggestions are admitted only as detection signals, risk notes, or lab controls. No other changes.
- **TODO/FIXME/XXX**: none.
- **Internal consistency**: STRIDE table references L1/L3/L5/L6, which exist as SpoofStack layers (see `layers.md`). Layer-Mapping diagram is a 6-tier Android-stack model (Application → Network), which is distinct from but compatible with the 7-layer SpoofStack (L0..L6 are *defense layers*, not the same axis as the threat-model's *Android-stack layers*). BEST-STACK §VIII.4 explicitly labels the same diagram as "6-Layer Threat Model".
- **External refs**: none broken; no cross-doc links exist in this file.
- **Finalizable: YES**. No changes applied.

### 2. `agents/stability/stack/layers.md`

- **Diff summary**: Adds "Safety boundary" preamble (19 lines, English) classifying new architecture feedback (custom kernels, KernelSU/APatch, property rewriting, sensor injection, proxies, TLS shaping) into allowed (detector/baseline/risk note) vs not-allowed (operational evasion).
- **TODO/FIXME/XXX**: none in modified region. Pre-existing `⚠ DEPRECATED stub — DO NOT use as-is` block at L0 (lines 37–60) is documented as historical record with explicit pointer to `experiments/runner/SPEC.md §4`; this predates the diff and is finalized state per Round-2.5 F37.
- **Layer numbering**: L0..L6 layer headers are stable and unchanged.
- **External refs**: `experiments/runner/SPEC.md §4` referenced — verified path exists in repo earlier in the project. Pre-existing reference, not added in diff.
- **Finalizable: YES**. No changes applied.

### 3. `docs/super-action/W1/BEST-STACK-v2.md`

- **Diff summary**: Adds "Research Boundary" preamble (26 lines, English) with a feedback-theme classification table (ARM64 host, custom kernel, modified ReDroid, KernelSU/APatch/Magisk, sensor noise, JA3/JA4/TLS/TCP, residential proxies → safe vs not-allowed). Inserted between header and TL;DR; nothing else changed.
- **TODO/FIXME/XXX**: none.
- **Internal consistency**: All seven tiers (§I.Tier 1..7) intact. SpoofStack table (§II) uses L0a/L0b/L1..L6 which matches `layers.md` semantic L0..L6 (L0a = ReDroid baseline, L0b = root layer — refinement, not contradiction). Mermaid §VIII diagrams unchanged.
- **External refs**: Pre-existing `docs/super-action/W7/shell-templates/redroid-bootstrap.sh` reference is unaltered.
- **Finalizable: YES**. No changes applied.

## Cross-file consistency

| Check | Result |
|---|---|
| Layer L0..L6 naming agreement (`layers.md` vs BEST-STACK §II) | OK — BEST-STACK refines L0 into L0a/L0b; same layer space. |
| Android-stack 6-layer model (threat-model.md vs BEST-STACK §VIII.4) | OK — both label "6-Layer Threat Model" (Application/Framework/Native/Kernel/Hardware/Network). |
| Probe count baseline | Internally consistent at **60 baseline probes**: `threat-model.md` "60 Detection-Punkte"; BEST-STACK "60 baseline" repeated four times; `shared/probes/inventory.yml` header states "71 = 60 baseline + 11 A17 RASP additions" (72 `rank:` entries — one likely duplicate or sub-rank such as `4b` / `12b`). All three top-level docs in scope agree on the **60-baseline** number. |
| Probe count "75" (mentioned in task spec) | **Not cited in any of the three files.** No mismatch to fix. |
| Safety-boundary language | Each file uses its host doc's language (DE for threat-model, EN for layers and BEST-STACK). Consistent in spirit, intentionally not unified in wording. |

## Open issues for owner decision

1. **`inventory.yml` header says 71 probes, has 72 `rank:` rows.** Minor inventory accounting discrepancy (likely `4b`/`12b` sub-ranks vs the "71" stated total). Out of Track E scope (not one of the three files), flagged for whoever owns probe inventory.
2. **Task-spec wording "8-layer Android detection model" does not match actual content** (threat-model is 6-layer, SpoofStack is 7-layer L0..L6). Documentation content itself is internally consistent; only the plan's prose description is off-by-one or off-by-two. Plan is immutable — no action.
3. **No language unification of the three new safety preambles** (DE / EN / EN). Owner may want a single canonical phrasing across all three; left as-is because each fits its host doc's existing language style.

## Conclusion

All three files are **finalizable as-is**. No edits applied by Track E. Track B is free to commit the working-tree diffs.
