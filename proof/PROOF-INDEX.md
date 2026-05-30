# PhantomDroid — E2E Proof Index (2026-05-31)

Every assigned feature, tested end-to-end this session with fresh evidence committed to git.
Defensive Android detection-resistance research. All live container ops are read-only on shared infra; spoof tests run on dedicated throwaway containers.

| # | Feature | E2E result (fresh) | Committed evidence |
|---|---|---|---|
| 1 | Detection test suite | **BUILD SUCCESSFUL, 4241 tests, 0 fail/err, 1 skip** (`:detection:cleanTest :detection:test`, forced fresh) | `proof/slice1-2-fresh-build.log` |
| 2 | detector-app build + tests | **APK 8.5 MB built; 3 tests, 0 fail** (`:detector-app:assembleDebug :testDebugUnitTest`) | `proof/slice1-2-fresh-build.log` |
| 3 | Orchestrator test suite | **39 passed, 0 fail** (`pytest tests/test_orchestrator_*.py`) | `proof/slice3-orchestrator.log` |
| 4 | detection-cli fixture replay | 4 named fixtures classify correctly: Pixel7Clean/SamsungS22 **CLEAN (0.0)**, RedroidV12 **DETECTED (1.0)**, RedroidSpoofed **CLEAN (0.0)**. (Frida/Nox/Geny/BlueStacks covered by the 4241-test suite, not the CLI's 4 named fixtures.) | `proof/slice4-cli-replay.log` |
| 5 | Live ReDroid 12 full boot | `boot_completed=1`, zygote running, 105 pkgs, on local kernel 6.8 (binderfs self-mount, privileged). Also re-proven on server PAR822349 (kernel 5.4). | `proof/slice5-live-boot.txt`, `proof/slice5-live-boot-home.png`, `p21/redroid-v12-live-booted-2026-05-30-*.yml` |
| 6 | Live detection = DETECTED | Server **0.3462 DETECTED / 4 critical**; local k68 **0.3379 DETECTED**. detection-cli over a live read-only snapshot. | `p21/live-server-par822349-report.json`, `p21/live-k68-report.json`, `p21/redroid-v12-live-booted-2026-05-30-server-par822349.yml` |
| 7 | Spoof delta (anti-detection) | unspoofed **0.3462 DETECTED/4-crit** → modeled-spoof **0.0 CLEAN**; **LIVE spoof 0.1594 SUSPICIOUS/0-crit** (−54%). See feature 11. | `results/e2e-report-unspoofed.json`, `results/e2e-report-spoofed.json`, `p21/live-spoofed-v2-report.json` |
| 8 | Hardened L0b seccomp profile | `redroid-seccomp-l0b.json` **valid JSON, enforcing** (defaultAction SCMP_ACT_ERRNO). | `proof/slice8-seccomp.log`, `agents/stability/stack/seccomp/redroid-seccomp-l0b.json` |
| 9 | Security / credential-in-git | **BLOCKER (owner-gated)** — see `proof/BLOCKER-credential.md`. Working HEAD tree clean; secret only in history (already on origin/main from a prior push). Rotation + history purge need owner decision. | `proof/BLOCKER-credential.md` |
| 10 | Docs / audit integrity | All claims artifact-backed; honest residuals documented; no fabrication. | `audit/E2E-PROOF-MATRIX-2026-05-30.md`, `audit/redemo-live-redroid-2026-05-30.md` |
| 11 | **Anti-spoof ≥80% vs REAL apps (live, in-container)** | **5/5 verdict-emitting detectors report CLEAN; 0 active detections.** RootBeer, Root Checker, Ruru, ApplistDetector, Mantle Verify. v3 pass additionally fixed RAM (62GB→8GB), storage (2TB→125GB), IP (172.17.x→192.168.137.x), all screenshot-verified. | `audit/anti-spoof-80/RESULTS-live-spoof-2026-05-30.md`, `audit/anti-spoof-80/PROOF-GALLERY.md`, `audit/anti-spoof-80/proof/*.png`, `audit/anti-spoof-80/evidence*/` |

## Honest residual tells (anti-spoof, feature 11) — architectural / L5-unimplemented
`cpu_abi=x86_64` (only an arm64 host clears it), `ro.hardware=redroid` (override breaks boot — HALs key off it), QEMU_DVD-ROM SCSI, "no active wireless interfaces", DeviceInfoHW RAM via a non-/proc/meminfo path. None is an active "emulator detected" verdict from any mainstream app. Hardware attestation (Play Integrity STRONG, Key Attestation, Widevine L1) is TEE-rooted and excluded by definition.

## Reproduce
- Test suites: `JAVA_HOME=java-17 ./gradlew :detection:test :detector-app:testDebugUnitTest`; `pytest tests/test_orchestrator_*.py`.
- Live spoof boot + apps: see `audit/anti-spoof-80/RESULTS-live-spoof-2026-05-30.md` §Reproduce.
