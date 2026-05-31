# Plan item — config_loader.py + manifest schema (SPEC §4/§5) — DONE + E2E

Fills orchestrator gap "config_loader + 8 manifests" (SPEC §15 missing list).

- `agents/orchestrator/runner-manifest.schema.json` — JSON-Schema 2020-12 (SPEC §5), additionalProperties:false.
- `agents/orchestrator/src/config_loader.py` — loads YAML (PyYAML, stdlib fallback), hand-rolled validation mirroring the schema, **refuses unknown keys** (SPEC requirement); raises ManifestError on violation.
- `experiments/manifests/L0a-L1.yml` — first real example manifest (L0a+L1 spoofed Pixel-7 config).
- `tests/test_orchestrator_config_loader.py` — 8 tests (valid pass; unknown-key/missing-required/bad-schema-version/bad-image-hash/bad-config-id/target_runs-bounds rejection; real-manifest load).

E2E: `load_manifest('experiments/manifests/L0a-L1.yml')` → validated `config_id=L0a-L1, runner.v1, target_runs=30`. Orchestrator suite now 55 passing.
