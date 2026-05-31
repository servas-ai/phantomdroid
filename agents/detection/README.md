# Detection Agent

> **Role:** Measurement oracle. Runs detection probes against an Android target and emits a JSON report.

## What this agent does

1. Loads the 75-probe inventory from `shared/probes/inventory.yml`
2. Runs each probe via `adb` against the target environment
3. Validates results against `shared/probe-schema.md`
4. Emits a single JSON report with per-probe scores (0.0 = real device, 1.0 = obviously container)

## What this agent does NOT do

- Decide which container configuration to test — that's the **Stability Agent**'s job.
- Bring containers up or down — that's the **Orchestrator Agent**'s job.
- Modify probe definitions — they live in `shared/` and are read-only here.

## Inputs

```yaml
target:
  kind: container        # or "device"
  container_id: cph-l3-baseline-001
  # OR for real device:
  # kind: device
  # adb_serial: 12345678
probe_filter:           # optional
  categories: [buildprop, root, integrity]
  # OR ids: [1, 3, 7, 14]
```

## Outputs

`reports/{run_id}.json` — JSON-Schema-validated per the contract in `shared/probe-schema.md`.

## Source layout

```
src/
├── core/                  # Probe contract + runner orchestration
│   ├── Probe.kt           # interface
│   ├── ProbeResult.kt     # data class
│   ├── ProbeContext.kt    # testable abstraction (with ShellProbeContext for allowlist)
│   ├── ProbeRunner.kt     # orchestrator with timeout + failure isolation
│   └── Report.kt          # top-level JSON-Schema binding
└── probes/                # Individual probe implementations (86 probes)
    ├── buildprop/ root/ integrity/ identity/ sensors/ network/
    └── emulator/ ui/ env/ runtime/ kernel/ app/
        # Full inventory (shared/probes/inventory.yml) implemented.
```

## Status

**IMPLEMENTED + CI-gated** (as of 2026-05). The full probe inventory
(`shared/probes/inventory.yml`) is implemented — **86 probes**, **4241 unit
tests green** (`./gradlew :detection:test`), regression-gated in CI at ≥3000
(`.github/workflows/detection-test.yml`). A working Gradle build exists
(`:detection`, `:detection-cli`, `:detector-app`); the `:detector-app` module
runs the probes in-process on a live device. The single-probe `SKELETON.md`
in this directory is a **historical** Round-1 artifact, superseded by the
current tree.

## Probe contract (invariants every probe must honor)

1. Complete in ≤ 5 seconds (hard timeout enforced by `ProbeRunner`).
2. Never makes network requests to live third-party services.
3. Produces a deterministic JSON-Schema-valid `ProbeResult`.
4. Never throws uncaught exceptions — failures map to `ProbeResult.failed()`.
5. Declares its category, severity, and android-layer up-front.
6. Runnable on a Pixel 7 (real device) producing score < 0.05 (true-negative test).
7. Runnable on vanilla ReDroid 12 producing score > 0.85 (true-positive test).
