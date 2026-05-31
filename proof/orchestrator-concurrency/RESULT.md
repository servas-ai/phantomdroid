# Plan item — Orchestrator concurrency pool (SPEC §10) — DONE + tested

Fills the last non-owner-gated orchestrator sub-item ("concurrency pool, SPEC §15 missing").

`agents/orchestrator/src/concurrency.py`:
- `PortPool` — allocates unique ADB ports from [5555..5755] step 2 (odd-only), acquire/release/recycle, raises `PortExhausted` when drained.
- `run_pool(cells, worker, max_concurrent=4)` — asyncio bounded-semaphore executor; hard cap 4 simultaneous containers per binder device (layers.md L0); each task holds a unique port for its lifetime; worker errors captured (don't crash the pool); results in input order.
- `compose_project_name(config_id, run_id)` — unique per-cell compose project `${config_id}-${run_id[:8]}`.

6 unit tests (`test_orchestrator_concurrency.py`), incl. a concurrency-cap test asserting observed peak in-flight ≤ 4 over 12 cells, port uniqueness/recycle, exhaustion, and error capture. Orchestrator suite now **62 passing** (was 39 at session start).

Remaining orchestrator scope is the hardened auto-boot `container_lifecycle` — owner-gated (B4 in BLOCKERS-owner-gated.md).
