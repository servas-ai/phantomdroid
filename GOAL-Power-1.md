# GOAL — Power 1

**Start**: 2026-05-19, after `GOAL-8h.md` closed at tag `weekly-W20-2026-05-19`
**Owner**: Martin (a@servas.ai)
**Working branch**: `report/CLO-143-weekly-W20`
**Mode**: continuous autonomous build with active builder+reviewer team

---

## The Goal (one sentence)

> **Finish everything that can be finished locally on this dev-VM — push the Detector to 40+ probes AND scaffold the remaining 6 SpoofStack layers AND make the Orchestrator runnable — so the owner can inspect a coherent, reviewable snapshot of the full agent system without dependency on the broken external server PAR822349 or any real device.**

---

## Starting point (verified state, 2026-05-19)

| Component | State | % done |
|---|---|---|
| Detector — probes implemented | 21 / 72 ranks | 29% |
| Detector — tests passing | 540 / 540 | 100% |
| SpoofStack — layers with compose file | 2 / 7 (L0a, L1) | 29% |
| SpoofStack — layers with RUNBOOK | 1 / 7 (L1 only) | 14% |
| SpoofStack — modules implemented | 2 (cpuinfo-overlay functional, hide-frida-maps skeleton) | — |
| Orchestrator — Python source | 430 LOC, runner+journal | unverified-runnable |
| Audit | recovery-FINAL, branch-triage, 8h-status, all track reports | clean |
| Server PAR822349 | reinstall pending provider-side | external |

Cross-cutting observations carried over from team session:
1. Evidence-key collision `pkg.<id>` across rank 3/8/10 — needs namespacing
2. 5 unverified package IDs in rank 10 marker list
3. ProbeContext lacks `querySettingGlobal` — 3 probes work around
4. inventory.yml rank 20 description says "IP geolocation" but probe is locale-country
5. Pixel 8 Pro density 489 vs 480 needs real-device telemetry

---

## Acceptance criteria (binary, observable)

| # | Criterion | How to verify |
|---|---|---|
| 1 | **Detector ≥40 probes implemented** | `find agents/detection/src/probes -name '*.kt' -not -name '*Test*' \| wc -l` returns `>= 40` |
| 2 | **Detector test suite stays green** | `./gradlew :detection:test` exits 0 with `≥ 540` tests |
| 3 | **All 7 SpoofStack layers have a compose file** | `ls agents/stability/stack/compose/L*.yml` shows L0a, L0b, L1, L2, L3, L4, L5, L6 (8 files for L0a/b split) |
| 4 | **All 7 SpoofStack layers have a RUNBOOK skeleton** | `ls agents/stability/stack/L*-RUNBOOK.md` shows ≥ 7 files (L0/L1/L2/L3/L4/L5/L6) |
| 5 | **Existing modules have README + acceptance criteria** | `agents/stability/stack/modules/cpuinfo-overlay/README.md` AND `stack/L4/hide-frida-maps/README.md` exist, each ≥ 50 LOC with acceptance list |
| 6 | **Orchestrator smoke test passes** | `python -m agents.orchestrator.src.runner --help` exits 0 AND `python -c "from agents.orchestrator.src.runner import main; print('ok')"` exits 0 |
| 7 | **Cross-cutting observations addressed or tracked** | A new audit doc `audit/cross-cutting-followups-2026-05-19.md` either fixes each of the 5 items OR tracks them as known issues with concrete acceptance criteria for later |
| 8 | **Audit closeout written** | `audit/Power-1-Status-2026-05-19.md` documents: probes added, layers scaffolded, modules documented, blockers, next |
| 9 | **Tag created if all 1–8 green** | Annotated tag `power-1-2026-05-19` on HEAD of `report/CLO-143-weekly-W20` |

---

## Non-goals (explicit out-of-scope)

- **Real device telemetry** — no Pixel 7/8 baseline, no real ReDroid runtime on this VM
- **Live container boot test** — Docker compose files can be written but won't be executed
- **Anti-detection runtime bypasses for production attack** — all work is **lab measurement and defensive research** only (per repo Hard Rules)
- **Live network probes** — no IP/ASN/proxy/MITM/residential-routing work; pure JDK + local file checks only
- **The 10 proxy documentations** — not present in this repo; owner to provide path before they can be integrated
- **`main` branch** — all work on `report/CLO-143-weekly-W20`
- **Express/VIP escalation, server cancellation, BIOS/RAID change, paid APIs** — same NOT-authorized list as `GOAL-8h.md`
- **Pushing to remote** — local commits + local tag only

---

## Probe-build plan (next 19+ probes, ordered by signal value)

Ordered to maximize coverage of CRITICAL + HIGH severity ranks first:

**Critical (rank 1–10 remaining)**: 4 + 6 from existing baseline = need rank 2, 4, 5, 6, 7, 9 still
- rank 7 `buildprop.tags_and_type` (mostly covered by BuildFingerprintProbe; verify and skip or extend)
- rank 9 `buildprop.model_brand_manufacturer` (overlap with rank 1; verify and skip or extend)
- rank 4 `emulator.qemu_artifacts` (overlap with CpuInfoProbe; extend)
- rank 2 `integrity.play_integrity` (needs Play Integrity API; document gap if no accessor)
- rank 5 `network.ip_asn` — **SKIP**: invariant prohibits live IP probes
- rank 6 `integrity.keystore_attestation` (needs hardware; document gap)

**High (rank 12–25 remaining)**:
- rank 12 `identity.imei_serial`
- rank 15 `identity.wifi_mac`
- rank 16 `identity.gaid`
- rank 17 `identity.gsf_id`
- rank 18 `network.vpn_proxy` (local-only fields — `getActiveNetworkInfo` type checks)
- rank 21 `identity.sim_iccid`
- rank 22 `identity.carrier_mccmnc`
- rank 25 `network.network_type`

**Medium (rank 26–40)**:
- rank 26 `runtime.usb_debugging` (already in `env.developer_options` rank 19 — verify scope)
- rank 27 `env.timezone` (overlap with rank 20; extend)
- rank 28 `env.battery_charge_pattern` (battery curve realism)
- rank 29 `env.uptime` (boot time consistency)
- rank 30 `env.installed_certificates` (CA store check)
- rank 31+ — picked dynamically based on what survives ProbeContext-API constraints

Target: 21 → **≥ 40** in this session.

---

## SpoofStack scaffold plan

For each of L0b, L2, L3, L4, L5, L6 (6 layers), create:

1. **`agents/stability/stack/compose/<layer>.compose.yml`** — Docker compose with image pins, env vars, healthcheck. Scaffold form (not bootable yet, but valid YAML).
2. **`agents/stability/stack/L<layer>-RUNBOOK.md`** — Same structure as `L1-MAGISK-RUNBOOK.md`: baseline-layer decision, mutations, boot budget, acceptance, rollback.
3. **`agents/stability/stack/modules/<module>/README.md`** — README per existing module (cpuinfo-overlay, hide-frida-maps). With acceptance criteria.

The compose files don't need to boot a real ReDroid (no Docker capable host); they need to be **schema-valid and consistent with image-pins.yml**.

---

## Orchestrator smoke plan

- Read `agents/orchestrator/src/runner.py` (141 LOC) and `journal.py` (288 LOC).
- Add a `__main__` entry if missing.
- Verify `python -m agents.orchestrator.src.runner --help` exits 0.
- Verify `from agents.orchestrator.src.runner import main` succeeds.
- If `agent.yaml` references CLI flags not implemented in `runner.py`, document the gap (don't fix unless trivial).

---

## Workflow

1. Continue the `detector-build-2026-05-19` team (builder + reviewer) on probes. Same cycle: brief → draft → review → commit.
2. Spawn a second team `stack-scaffold-2026-05-19` for SpoofStack work (parallel, doesn't conflict with detector files).
3. Owner can inspect progress at any time via:
   - `git log --oneline`
   - `find agents/detection/src/probes -name '*.kt' -not -name '*Test*' \| wc -l`
   - `ls agents/stability/stack/compose/`
   - `cat audit/Power-1-Status-2026-05-19.md` (when written)

---

## Stop conditions

- All 9 acceptance criteria green → tag `power-1-2026-05-19` + write closeout
- Hard blocker that needs owner input (ProbeContext core-contract change, missing API, ambiguous scope) → flag via this chat, pause that branch, continue others
- Owner intervenes → adjust scope

---

## Owner intervention checkpoints

- Owner approves/redirects after any single probe approval round
- Owner can hand-pick which 19 probes to add (default = order above) — say at any time
- Owner can tell me to stop scaffolding stack layers if priority shifts
- Owner has not yet provided path to the 10 proxy docs — when received, those go into SpoofStack L6 (Network) scaffold per the BEST-STACK-v2 partition
