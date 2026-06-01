# Probe Inventory Changelog

All notable changes to `shared/probes/inventory.yml` are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.1] - 2026-05-16

### Added

- **rank 9.5 `kernel.cpuinfo_bogomips_implementer`** (CLO-20). Reads `/proc/cpuinfo`
  and cross-references `getauxval(AT_HWCAP)` for arch-trap signal (x86 host
  capability bits leaking through ARM-claimed device). Emits `bogomips`,
  `cpu_implementer`, `hwcap`, `arch`. Baselines pinned for Pixel 8 / 9 / 9 Pro
  in `assets/baselines/cpuinfo/*.json`. Implementation: commit `3f19a9d`
  (`agents/detection/src/probes/kernel/CpuInfoProbe.kt`).

Inventory total now 72 probes (60 baseline + 11 A17 + 1 CLO-20).

## [2.0] - 2026-05-16

### Schema

- **schemaVersion bumped 1.0 -> 2.0** (CLO-22 / META-22).
  Per-probe records may now use decimal rank values (e.g. `8.5`) to denote
  logical priority slots between existing integer ranks. The optional
  `rasp_analog` field is introduced (enum: `none`) to flag probes with no
  analog in any surveyed RASP library (freeRASP, RootBeer, OWASP MASTG,
  Approov). Backwards compatible with v1 readers that ignore unknown fields;
  not backwards compatible with v1 readers that enforce `rank` as integer
  1..60 (see `shared/probe-schema.md`).

### Added

11 new probes mined from production RASP/antifraud OSS via A17
(`docs/validation-round-3/A17-rasp-defensive-probe-mining.md` §5). Inventory
total now 71 probes (60 baseline + 11 A17 additions).

| N# | Rank | id | Severity | Source |
|---|---|---|---|---|
| N1 | 8.5 | `runtime.debugger_tracerpid` | high | freeRASP T2 / MASTG MSTG-RESILIENCE-2 |
| N2 | 9.0 | `runtime.frida_memory_maps` | high | freeRASP T6 / MASTG MSTG-RESILIENCE-4 |
| N3 | 9.7 | `runtime.native_prologue_hash` | critical | DetectFrida / BEST-STACK Hard Ceiling #1 |
| N4 | 33.5 | `env.time_spoofing` | medium | freeRASP T15 |
| N5 | 39.5 | `env.location_mock_rasp` | medium | freeRASP T16 (augments rank #39) |
| N6 | 43.5 | `env.wifi_security_type` | low | freeRASP T14 |
| N7 | 40.5 | `env.screen_lock` | medium | freeRASP D1 |
| N8 | 50.5 | `runtime.multi_instance` | medium | freeRASP T13 |
| N9 | 51.5 | `runtime.automation_tools` | medium | freeRASP T10 |
| N10 | 52.5 | `runtime.screen_recording` | low | freeRASP T11/T12 |
| N11 | 9.8 | `integrity.prologue_got_hooks` | critical | MASTG MSTG-RESILIENCE-6 / BEST-STACK Hard Ceiling #1 |

N5 is appended as `env.location_mock_rasp` (not `env.location_mock`) to avoid
duplicate ids with the existing rank #39 entry. N3 and N11 are tagged
`mitigation_layer: not_spoofable` because BEST-STACK §IV documents them as
uncountered by any FOSS-only defense in 2026.

### Changed (severity reranks per BEST-STACK §V Phase 3)

| Rank | id | Severity (was -> is) | Rationale |
|---|---|---|---|
| #23 | `ui.screen_resolution` | high -> medium | A17 finding: not a Tier-1 RASP signal; common-resolution coherence is informational only |
| #29 | `identity.mediadrm` | medium -> high | MASTG MSTG-RESILIENCE-10 multi-property device binding makes MediaDRM ID central to >=3-property fingerprint |
| #50 | `runtime.services_processes` | low -> medium | freeRASP T10 automation detection elevates running-services scan to standard RASP signal |
| #60 | `integrity.app_signature` | trace -> medium | MASTG MSTG-RESILIENCE-3 file-tamper detection is a baseline check in every surveyed RASP library |

### Tagged (A17 §6 deprioritize candidates; NOT removed)

The following four probes remain in the inventory but are tagged
`rasp_analog: none` and explicitly excluded from the Detection Agent's core
probe battery -- they have no analog in any surveyed RASP/antifraud library.

| Rank | id | Reason |
|---|---|---|
| #45 | `sensors.barometer` | No RASP library checks barometer presence |
| #51 | `ui.system_fonts` | Browser-canvas technique; no native Android RASP analog |
| #54 | `ui.audio_fingerprint` | WebView-only technique; no native RASP analog |
| #55 | `ui.canvas_fingerprint` | WebView-only technique; no native RASP analog |

### Migration notes

- v1 -> v2 readers SHOULD treat `rank` as `number` (float) instead of
  `integer 1..60`. Code paths that bucket by integer rank (see
  `shared/probe-schema.md` "Gewichtung im Aggregate") should use
  `Math.floor(rank)` before bucketing so the 11 new fractional ranks
  inherit the weight of their floor-bucket.
- The `rasp_analog: none` field SHOULD be consumed as an exclusion flag by
  the Detection Agent's core battery selector. Probes with this tag remain
  available for opt-in research-grade fingerprinting runs.
- Decimal-ranked entries (N1-N11) are appended at the end of the file
  rather than inserted in priority order, to preserve diff stability across
  future inventory mutations.

### Provenance

- Issue: CLO-22 (META-22) "Inventory.yml expansion to 71 probes"
- Depends on: CLO-21 (META-21) probe-schema-v2 JSON-Schema validation
- Authors: Emulator Builder (Paperclip agent)
- A17 source agent: open-source RASP/antifraud library analysis, 26 cited sources

## [1.0] - 2025-Q4 (baseline)

- Initial 60-probe inventory from Recherche-Synthese GPT-5.2 / Claude Opus 4.6
  / Grok 4.1. Sections: KRITISCH (1-10), HOCH (11-25), MITTEL (26-40),
  NIEDRIG (41-50), ERGAENZEND (51-60). Schema documented in
  `shared/probe-schema.md` (v1 batch report shape).
