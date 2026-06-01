---
iso_week: "{{iso_week}}"
cells_run: {{cells_run}}
regressions: {{regressions}}
new_ceilings_hit: {{new_ceilings_hit}}
kpi_table: |
  {{kpi_table}}
---

# Weekly Report — ISO Week {{iso_week}}

**Generated:** {{generated_at}}
**Branch:** heatmap/{{iso_week}}

## Summary

| Metric | Value |
|---|---|
| Cells run | {{cells_run}} |
| Regressions | {{regressions}} |
| New ceilings hit | {{new_ceilings_hit}} |

## KPI Table

{{kpi_table}}

## Hard Ceilings Status

Current status of each of the 9 BEST-STACK hard ceilings
(`BEST-STACK-v2.md §IV`). Statuses: **mitigated** / **partial** / **open**.

| # | Vector | Status | Notes |
|---|---|---|---|
| 1 | Inline-hook trampoline detection (DetectFrida) | {{ceiling_1_status}} | {{ceiling_1_notes}} |
| 2 | Kernel fingerprint — container UTS_RELEASE leak | {{ceiling_2_status}} | {{ceiling_2_notes}} |
| 3 | Play Integrity STRONG_INTEGRITY (HARDENED) | {{ceiling_3_status}} | {{ceiling_3_notes}} |
| 4 | Behavioral telemetry side-effects (GC/heap/timing) | {{ceiling_4_status}} | {{ceiling_4_notes}} |
| 5 | DMI sys_vendor leak (QEMU/x86 host) | {{ceiling_5_status}} | {{ceiling_5_notes}} |
| 6 | Promon SHIELD 7.0 | {{ceiling_6_status}} | {{ceiling_6_notes}} |
| 7 | `/proc/config.gz` kernel-config hash | {{ceiling_7_status}} | {{ceiling_7_notes}} |
| 8 | `/proc/self/status` TracerPid window | {{ceiling_8_status}} | {{ceiling_8_notes}} |
| 9 | `/proc/cpuinfo` BogoMIPS + cpu-implementer (x86 leak) | {{ceiling_9_status}} | {{ceiling_9_notes}} |

## Heatmap Artifact

- **SVG:** `results/artifacts/heatmap.svg`
- **CSV:** `results/aggregate/all-configs.csv`
- **PR:** {{pr_url}}

## Notes

{{notes}}
