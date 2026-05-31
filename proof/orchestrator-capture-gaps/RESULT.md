# Plan item — close measurement-model capture gaps (locale + input_method) — DONE + E2E

Per `proof/RESIDUAL-CLASSIFICATION.md`, several ≥0.5 probes were measurement gaps (the snapshot didn't
capture the field), not real device tells. Extended `live_matrix.capture_live_snapshot` to read them live:
- `env.language_country` ← `ro.product.locale` (=en-US) → **0.85 → 0.0**
- `ui.input_method` ← settings secure `default_input_method` (=LatinIME) → **0.7 → 0.0**
- also captured net.dns1/2 + private_dns_* (network.dns_server stays 0.5 — net.dns1 empty in container, genuinely no resolver config)

NOT captured (honest): `/proc/self/status` for `runtime.debugger_tracerpid` — under `docker exec cat` it
reflects the `cat` process and the probe scores it WORSE (0.85) than absent (0.5); the host-side snapshot
has no faithful app-tracer capture, so it's left absent (degraded 0.5, not a misleading value).

E2E: spoofed cell weightedScore **0.1394 → 0.1253** (combined with the android_id capture: 0.1594 → 0.1253,
all measurement-completeness, no architectural change). Orchestrator suite 73 green.
