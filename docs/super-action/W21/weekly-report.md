---
iso_week: "2026-W20"
cells_run: 0
regressions: 0
new_ceilings_hit: 0
kpi_table: |
  _(no CSV found — run runner aggregate --all first)_
---

# Weekly Report — ISO Week 2026-W20

**Generated:** 2026-05-16T09:35:48Z
**Branch:** heatmap/2026-W20

## Summary

| Metric | Value |
|---|---|
| Cells run | 0 |
| Regressions | 0 |
| New ceilings hit | 0 |

## KPI Table

_(no CSV found — run runner aggregate --all first)_

## Hard Ceilings Status

Current status of each of the 9 BEST-STACK hard ceilings
(`BEST-STACK-v2.md §IV`). Statuses: **mitigated** / **partial** / **open**.

| # | Vector | Status | Notes |
|---|---|---|---|
| 1 | Inline-hook trampoline detection (DetectFrida) | open |  |
| 2 | Kernel fingerprint — container UTS_RELEASE leak | open |  |
| 3 | Play Integrity STRONG_INTEGRITY (HARDENED) | open |  |
| 4 | Behavioral telemetry side-effects (GC/heap/timing) | open |  |
| 5 | DMI sys_vendor leak (QEMU/x86 host) | open |  |
| 6 | Promon SHIELD 7.0 | open |  |
| 7 | `/proc/config.gz` kernel-config hash | open |  |
| 8 | `/proc/self/status` TracerPid window | open |  |
| 9 | `/proc/cpuinfo` BogoMIPS + cpu-implementer (x86 leak) | open |  |

## Heatmap Artifact

- **SVG:** `results/artifacts/heatmap.svg`
- **CSV:** `results/aggregate/all-configs.csv`
- **PR:** https://github.com/placeholder/pr/1

## Notes


