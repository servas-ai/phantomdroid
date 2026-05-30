# PhantomDroid — Autonomous End-to-End Plan (2026-05-29)

**Mandate**: Owner authorized full autonomous E2E ("mach alles, dass es losgeht und funktioniert"), including the kernel-5.4 reboot. Parallel agent team + 30-min self-check ticker.

**Goal**: Get the live ReDroid 12 container to FULLY boot (`sys.boot_completed=1`) and run the detection probes against it E2E (props → probes → score → heatmap), then re-probe with the spoof stack.

**Credentials**: SSH user `paris`, password in gitignored `.env` (sudo with same pw). Server `195.154.209.133`.

---

## Phase 1 — CRITICAL GATE (sequential, owner-authorized): kernel 5.4 reboot
Owner: orchestrator (NOT delegated — prod reboot stays in lead's hands).
1. Pre-checks: GRUB_DEFAULT=0, 5.4 vmlinuz+initrd present, 5.4 native binder_linux.ko, note uptime/IP. ✅ done
2. `sudo reboot`; poll SSH until back (max ~8 min).
3. On return: `uname -r` == 5.4.0-150; `modprobe binder_linux ashmem_linux`; verify binderfs mountable.
4. FAIL path: if not back in 8 min → STOP, report, server needs panel/IPMI power-cycle (4.15 still in GRUB).

## Phase 2 — Live full boot (sequential, after Phase 1)
5. Restart `redroid-test` (or recreate via compose L0a) on 5.4 host.
6. Verify `getprop sys.boot_completed` == 1 within ~90s; `init.svc.zygote` running.
7. `adb connect 127.0.0.1:5555` works (ADB no longer hangs).

## Phase 3 — PARALLEL TEAM (after Phase 2 boot confirmed)
- **T-A (ralph-coder)**: APK-inside-container delivery (OB4) — build DetectorLab APK, `adb install`, run probes INSIDE the container, capture true-attestation report.
- **T-B (ralph-tester)**: full 84-probe live sweep via detection-cli/JUnit against the live container; compare to snapshot baseline.
- **T-C (ralph-coder)**: orchestrator full matrix (now aggregator.py exists) — produce multi-cell heatmap from real journal+reports.
- **T-D (ralph-reviewer + ralph-security)**: endgate every deliverable ("both APPROVE" gate).

## Phase 4 — Spoof re-probe (after Phase 3)
8. Deploy the 3 functional in-house modules (cpuinfo-overlay, hide-frida-maps, spoof-stack-magisk) into the live container — DEFENSIVE lab measurement only.
9. Re-run probes live, measure delta (target: live weightedScore drops like the snapshot 0.346 → ~0).

## Ticker (every 30 min)
Re-check: which phase is active, are background agents alive, did anything fail, advance the plan. Update tasks. Re-schedule.

## Hard boundaries
- No destructive action beyond the authorized reboot without re-confirming.
- Defensive research only (no offensive bypass runbooks).
- If server unreachable post-reboot → stop live work, continue local tracks, report.
