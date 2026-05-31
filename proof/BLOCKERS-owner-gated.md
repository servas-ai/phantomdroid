# Owner-gated plan points — SKIPPED + documented (per goal rule)

These plan points need Martin's decision/credentials/supply-chain approval and CANNOT be done
autonomously. Per the standing goal they are skipped, documented here, and the plan continues. Each
will complete the moment the owner unblocks it; everything around them is already implemented + proven.

| # | Plan point | Blocker class | What's needed from owner | Everything-else-done? |
|---|---|---|---|---|
| B1 | **L0b Magisk root stack** (Magisk + ReZygisk + LSPosed) | Supply-chain decision | Source + SHA-pin + approve a community rooted-ReDroid image (stock ReDroid has no boot.img/ramdisk, so `pm install Magisk.apk` cannot root it — `audit/phase4-l0b-buildout-plan-2026-05-29.md`). | Yes — the lightweight non-Magisk live spoof (props + bind-mount overlays) is implemented and proven (5/5 detectors clean). The Magisk-module variants (cpuinfo-overlay, spoof-stack-magisk 86/104 hooks) are written and staged; they only need a rooted host to load. |
| B2 | **L1–L6 full module stack execution** | Depends on B1 | Same as B1 (the stack needs the Magisk daemon). | L1 (build properties) is proven live without Magisk; L2–L6 module trees exist, gated on B1. |
| B3 | **Play Integrity / hardware attestation pass** (detector-app + SPIC/TB) | Architectural (TEE) | A real TEE / hardware-backed keystore — impossible in a software container by definition. | Yes — the in-process detector-app run is proven (0.1526 SUSPICIOUS spoofed vs 0.3050 DETECTED unspoofed); only the TEE-gated token is out of reach. |
| B4 | **Hardened (non-privileged) `container_lifecycle` auto-boot** (SPEC §4: cap_drop+seccomp, refuses privileged) | Posture decision | Decide privileged-vs-hardened: the hardened posture **cannot boot ReDroid on binderfs-only kernels** (proven 2026-05-30); only privileged self-mount boots. Needs a board-reviewed narrowed-seccomp amendment OR acceptance of privileged for the lab. | Yes — the orchestrator true-matrix executor (run_id+persistence+live_matrix+--resume) attaches to already-booted containers and produces real per-cell scored+persisted runs. |
| B5 | **Credential purge + rotation** | Credential + destructive history rewrite | Rotate `paris` SSH pw; `git filter-repo` purge of the secret from `origin/main` (tracked file `audit/track-a-reinstall-submitted-2026-05-19.md` + history) + force-push. | This `session/e2e-2026-05-30` branch tree is secret-free; see `proof/BLOCKER-credential.md`. |

All other (non-owner-gated) plan points are being worked to DONE + E2E + pushed, point by point.
