# Power-2 Run — Status Report (2026-05-19 → 2026-05-20)

**Branch**: `report/CLO-143-weekly-W20`
**Predecessor tag**: `power-1-2026-05-19` (40 probes)
**Mode**: continuous autonomous build with active builder+reviewer team
**Owner**: Martin (a@servas.ai)

---

## TL;DR

**Power-1 → Power-2: 40 → 62 probes (+22 net, 86% of inventory).** All CRITICAL-tier easy-wins closed (rank 4, 7, 9). All HIGH-tier RASP easy-wins closed (rank 8.5). All TRACE-tier UI/env easy-wins closed (rank 51, 52, 53, 58, 59). Sensor batch complete (rank 42-45). Plus rank-66 collision bug fixed, 3 new cross-cutting follow-ups documented (TikTokArgus A10+ broken, Probe.rank Int-vs-Double, SensorSample axis-count). Server PAR822349 reinstall complete (Ubuntu 18.04 verified live via SSH).

---

## Coverage state (verified shell)

| Metric | Power-1 close | Power-2 close | Delta |
|---|---:|---:|---:|
| **Probes implemented** | 40 / 72 | **62 / 72** | +22 |
| **Coverage %** | 56% | **86%** | +30 pts |
| **Test classes** | 39 | **61** | +22 |
| **Test methods total** | 1687 | **3232** | +1545 |
| **Commits since Power-1** | 0 | **41** | — |
| **Working tree** | clean | clean | — |

`./gradlew :detection:test` → BUILD SUCCESSFUL (all green).

---

## Probes added this run (22 new, in commit order)

| Rank | ID | Severity | Commit | Notes |
|---|---|---|---|---|
| 40 | env.accounts | medium | `a8545a9` | First |
| 42 | sensors.proximity | low | `a4de14c` | Sensor batch start |
| 43 | sensors.light | low | `490e7cd` | |
| 44 | sensors.magnetometer | low | `f238994` | (revised once) |
| 45 | sensors.barometer | low | `9ac77a5` + `11c25f6` + `f2b974b` | (3 polish rounds, KNOWN_BAROMETER_MODELS 28-entry disposition approved) |
| 28 | buildprop.board_hardware | medium | `0d20466` | (revised once) |
| 50 | runtime.services_processes | medium | `69f0180` | (revised once) |
| 47 | env.uptime | low | `d404fde` + `5815895` | |
| 48 | env.nfc_state | low | `3c3d8a3` + `cfc4ac0` | |
| 49 | env.bluetooth_state | low | `265a14e` + `1352ec5` | 50-probe milestone |
| 46 | ui.refresh_rate | low | `97b52c3` + `7a47d2a` | Pixel 7a fix |
| 51 | ui.system_fonts | trace | `11e11b2` | (revised once, CalyxOS calibration) |
| 52 | ui.display_cutout | trace | `a37d222` + `263a7c2` | |
| 53 | env.camera_info | trace | `163168c` + `1eb0ad6` | (revised once) |
| 4 | emulator.qemu_artifacts | **critical** | `61b5dcf` + `c50ff79` | First critical-tier easy-win this run |
| 7 | buildprop.tags_and_type | **critical** | `c204162` | Focused-extraction pattern |
| 9 | buildprop.model_brand_manufacturer | **critical** | `bb4c18a` + `8adb6e8` | All critical-tier easy-wins now done |
| 8.5 | runtime.debugger_tracerpid (code 80) | high | `d27c96c` + `57c899e` + `92bc13f` | RASP T2 / MASTG-RESILIENCE-2 |
| 58 | ui.input_method | trace | `1f70791` | No phone-class gate (builder judgment confirmed) |
| 59 | env.accessibility_services | trace | `3787d85` + `80b43b7` | Null = unobservable (session discipline) |
| 38 | network.http_proxy | medium | `675fcbb` | Focused-extraction vs rank 18 |
| 39.5 | env.location_mock_rasp (code 82) | medium | `7f98954` | Final easy-win — freeRASP T16 |

**Also**: `7519c5b` `fix(detection): resolve rank-66 collision (ScreenLock 66 → 61)` — caught by parallel multi-agent audit mid-run.

---

## Remaining 10 inventory ranks (all blocked or skippable)

| Rank | ID | Severity | Status |
|---|---|---|---|
| 2 | integrity.play_integrity | critical | blocked: needs Google Play Integrity API client |
| 5 | network.ip_asn | critical | **skippable**: live network invariant |
| 6 | integrity.keystore_attestation | critical | blocked: needs hardware crypto / cert-chain accessor |
| 9.7 | runtime.native_prologue_hash | critical | blocked: needs native JNI memory accessor |
| 9.8 | integrity.prologue_got_hooks | critical | blocked: needs GOT/PLT JNI accessor |
| 41 | env.gps_coordinates | low | blocked: needs full LocationManagerView |
| 54 | ui.audio_fingerprint | trace | **skippable**: WebView-only |
| 55 | ui.canvas_fingerprint | trace | **skippable**: WebView-only |
| 56 | ui.webgl_fingerprint | trace | blocked: needs GL ES context (not just RENDERER) |
| 57 | ui.touch_pressure | trace | blocked: needs MotionEventView |
| 60 | integrity.app_signature | medium | blocked: needs `PackageManager.GET_SIGNATURES` |

**Path to 100% coverage**: requires ~5 ProbeContext core-contract additions:
1. `queryPlayIntegrityClient` — rank 2
2. `queryKeyStoreAttestation` — rank 6
3. `queryNativeMemoryMaps` (JNI) — rank 9.7, 9.8
4. `queryLocationManager` — rank 41 (+ rank 39.5 polish)
5. `queryGlInfo` (full) — rank 56
6. `queryMotionEvent` — rank 57
7. `queryPackageSignatures` — rank 60

These are cross-cutting #3-equivalent items. Tracked in `audit/cross-cutting-followups-2026-05-19.md`.

---

## Cross-cutting follow-ups state

`audit/cross-cutting-followups-2026-05-19.md`:

| # | Item | Status |
|---|---|---|
| 1 | `pkg.*` evidence-key collision | open (contained at 3 probes) |
| 2 | rank 10 marker-list verification | open |
| 3 | ProbeContext lacks `querySettingGlobal` | open (3+ probes work around) |
| 4 | inventory.yml rank 20 description | open (one-line fix) |
| 5 | Pixel 8 Pro density | open (telemetry needed) |
| 6 | SensorSample axis-count invariant | open |
| 7 | Probe.rank Int vs inventory Double | open (3 probes now mapped: ScreenLock 61, DebuggerTracerPid 80, LocationMockRasp 82) |
| 8 | TikTokArgusSigningProbe broken Android 10+ | **high urgency**, open |
| 9 | Rank-66 collision | **FIXED** in `7519c5b` |

---

## Server PAR822349 — reinstall completed

Confirmed via SSH on 2026-05-20:
- **Ubuntu 18.04.6 LTS (Bionic Beaver)**, kernel 4.15.0-213-generic x86_64
- `/dev/sda3` 1.8 TB total, 1% used (fresh install)
- Uptime ~20h before SSH check (reinstall completed ~2026-05-19 ~02:00 CEST)
- User `paris` + stored password works
- HP P410 RAID controller "Not responding" issue **resolved** by reinstall
- Sub-agent produced bring-up plan (Path D recommended: stay on 18.04, install Docker; Path U release-upgrade to 22.04 NOT recommended due to brick risk)
- All 8 bring-up steps need explicit owner authorization (steps 2-8)

---

## Quality observations (from parallel multi-agent audit mid-run)

**Code review** (4-agent audit):
- 🔴 Found rank-66 collision (fixed in `7519c5b`)
- 🔴 Found TikTokArgus A10+ path bug (tracked in cross-cutting #8)
- 🟡 7 non-standard score values across 4 probes (documented per probe)
- 🟡 `app/` directory probes have category-vs-directory mismatch (cosmetic)
- 🟢 54/54 probes conform to `Probe` interface
- 🟢 Constructor-injection pattern dominant and correct
- 🟢 Cross-rank constant reuse via invariant tests is robust

**Gap analysis**:
- 6 easy-wins remained at audit time, 6 closed since (4, 7, 8.5, 9, 38, 39.5 — plus rank 58, 59 batch).
- 7 blocked by ProbeContext extensions (rank 2, 6, 9.7, 9.8, 41, 57, 60).

**Quality auditor**:
- 2788→3232 tests architecturally healthy
- ~15% mechanical repeats identified as parameterization candidates
- No test-name-vs-assertion drift (rank 22 fix held)
- Cross-rank invariant tests are NOT brittle (compile-fail on rename, semantic-fail on drift)

**Server bring-up planner**:
- Provisioning path designed (Path D: 18.04 + Docker)
- All steps require explicit owner go (only baseline-snapshot is implicit-ok)
- Real blocker for ReDroid = binder/ashmem kernel modules, not OS version

---

## Non-goals respected (same as Power-1)

- ❌ Did NOT touch `main` branch
- ❌ Did NOT push to remote
- ❌ Did NOT add core-contract `ProbeContext` methods (every gap honestly documented)
- ❌ Did NOT touch `inventory.yml` (5 description-divergences tracked separately)
- ❌ Did NOT make paid external API calls
- ❌ Did NOT click Express/VIP, server cancel, BIOS/RAID
- ❌ Did NOT integrate the 10 proxy documentations (still missing from repo)

---

## Notable design wins this run

1. **rank-66 collision detection** via parallel multi-agent code-review (would have silently dropped one probe's results)
2. **rank 26 ANGLE-on-Pixel-8 guard** (would have false-positived every Pixel 8+ Android 14 user) — earlier in run
3. **rank 29 entropy threshold math correction** (6.0 → 4.0; log₂(32)=5.0 cap) — earlier
4. **rank 45 KNOWN_BAROMETER_MODELS 28-entry disposition**: substring-efficiency vs ≤10-entry guideline — established new convention (≤30 entries OR ≥3 variants/entry)
5. **rank 51 CalyxOS recalibration**: Builder caught false-positive on minimal-but-legit AOSP builds, calibrated NotoColorEmoji/font-count rules down
6. **Builder's "session discipline > spec literal" judgment**: validated twice in rank 58/59 dispositions
7. **Reviewer's structural-grep verification** of no-network invariant carried across rank 18/25/37/38

---

## Recommended next phase

After Power-2 tag, the natural next phases (owner choice):

**A) ProbeContext core-contract refactor** (cross-cutting #3, #7 + all the blocked-rank accessors). Unblocks 5-7 probes at once. Estimated 1 dedicated cycle.

**B) TikTokArgus A10+ path fix** (cross-cutting #8). Currently silently broken on all real-world Android 10+ devices. Single probe revision but requires `listDirectory` accessor add. ~30 min.

**C) Server bring-up** (Ubuntu 18.04 → Docker → ReDroid 12). Requires owner go on each step. ~2-4h.

**D) Test parameterization pruning** (~25 method reduction, ~15% redundancy). Low-priority maintenance. ~30 min.

**E) Continue probe push toward 100%** (10 remaining all blocked — must do A) first).

**Recommendation**: B (TikTok fix is a real correctness bug) → then A (core-contract refactor) → then E. C (server) needs owner gating per step.

---

## Provenance

- Commits 41 since `power-1-2026-05-19` tag (`a8545a9` → HEAD)
- 22 new probes + 1 critical bugfix + 1 cross-cutting doc update
- Working tree clean
- Tag `power-2-2026-05-20` to be created
