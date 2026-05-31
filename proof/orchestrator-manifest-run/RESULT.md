# Plan item — Orchestrator manifest-driven run (SPEC §8 `runner run --config`) — DONE + E2E

Integrates the orchestrator pieces into the SPEC §8 CLI shape: a validated manifest drives one TRUE
per-cell live run end-to-end.

`live_matrix --config <manifest.yml> --container <booted> [--label ..]`:
1. `config_loader.load_manifest` validates the manifest (refuses unknown keys, F20 image-hash pin).
2. config_id → layer set; fresh live capture from the attached (already-booted) container.
3. `detection-cli run` scores the snapshot.
4. `run_id` derives from the **manifest + its pinned apk/image hash** (SPEC §6 canonical, deterministic).
5. `persistence.persist_report` (atomic) + journal `complete_cell`.

E2E (`manifest-run-output.json`, `persisted-cell.json`): manifest `experiments/manifests/L0a-L1.yml`
+ container `l1-spoof-v3` → config_id=L0a-L1, COMPLETED, SUSPICIOUS 0.1594, deterministic run_id.
3 new tests (`--config` requires `--container`; empty-work error; canonical deterministic run_id).
Container auto-boot from the manifest's compose_file remains owner-gated (B4) — `--container` attaches
to an already-booted container per the proven privileged-self-mount path.
