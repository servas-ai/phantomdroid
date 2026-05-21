# Spoof-Stack Corpus Index — Master Cross-Reference

**Date**: 2026-05-21
**Author**: ralph-researcher (REUSE team `power-13-real-world-validation`, Power-18 Phase-D Task #3)
**Scope**: Master index across all Power-N closeouts (P1→P17), ancillary audit docs, hard-ceiling tracking, honest-synthesis provenance, owner-carryover, cross-cutting status, and forward roadmap.
**Anti-Verarschen mandate**: Every test-count and commit-sha cited below is read from the actual closeout file at the line indicated. Items I cannot verify against a closeout file are explicitly marked `UNVERIFIED-pre-baseline` (P1–P7) or `unverifiable-publicly`.

---

## §1 — Power-N Progression Table (P1 → P18-in-flight)

| Power | Tag | Headline Claim | Test Count | Probe Count | Key Artifact | Closeout |
|---|---|---|---:|---:|---|---|
| P1–P7 | (pre-baseline) | Initial inventory build-out, 60 baseline + 11 A17 RASP probes scaffolded; no consolidated closeout doc | UNVERIFIED-pre-baseline | ≤63 | `shared/probes/inventory.yml` (schemaVersion 2.0) | — (pre-Power-8 work, no `power-N-closeout.md` exists for N<8) |
| P8 | (no git-tag committed) | weightedScore RedroidSpoofed → 0.0000 | 3323 | 63 | `production-hooks-spec.md`, `iter-baseline.md`, `detection-resistance-report.md` | embedded in `iter-baseline.md` + `detection-resistance-report.md §0` |
| P9 | (no separate closeout) | Deployable spoof artifacts + 2 new probes (rank-41 + rank-60) | ~3400 | 65 | (rolled into Power-13 progression table) | progression row in `power-13-closeout.md §2` |
| P10 | (no separate closeout) | CLI runner + rank-5 + rank-6 + Samsung S22 diversity snapshot | ~3500 | 67 | (rolled into Power-13 progression table) | progression row in `power-13-closeout.md §2` |
| P11 | (no separate closeout) | 62/62 numbered ranks closed | 3617 | 72 | (rolled into Power-13) | progression row in `power-13-closeout.md §2` |
| P12 | (no separate closeout) | TRUE 73/73 inventory; ranks 9.0/9.7/9.8 declarative-variant probes added | 3668 | 73 | `production-hooks-spec.md §P-12`, `un-snapshottable.md §7a/7b/7c` | progression row in `power-13-closeout.md §2` |
| P13 | `power-13-real-world-validation-2026-05-20` | Real-world detector-app parity: 4/5 verified bypass-able against published source; +8 new Phase-B probes | 4145 | 81 | `power-13-closeout.md`, `real-world-detectors.md`, `real-world-gap-list.md`, `detector-replay-results.md` | `power-13-closeout.md §2/§12` |
| P14 | `power-14-apk-source-diff-2026-05-20` | APK-vs-source verification — RootBeer 0.1.1 AAR decompiled; 4 critical Power-13 replay-bugs fixed; bypass-proof STRENGTHENED | 4150 | 81 | `power-14-closeout.md`, `power-14-apk-source-diff.md` | `power-14-closeout.md §7` |
| P15-A | `power-15-phase-a-2026-05-21` | Frida-positive + 3 vendor-emulator fixtures; 648-cell matrix; both endgates APPROVE | 4155 | 81 | `power-15-closeout.md`, `power-15-canonical-sources.md`, `full-coverage-matrix.md`, `power-15-{security-audit,reviewer-signoff,pre-audit}.md` | `power-15-closeout.md §2` |
| P16-B | `power-16-phase-b-2026-05-21` | freeRASP T1-T16+D1 source-diff (honest-limited); RootBeer native-disasm (zero-hidden-paths); IntegrityInstallSourceProbe rank 10.5; both endgates APPROVE | 4165 | 82 | `power-16-closeout.md`, `power-16-{freerasp-source-diff,native-disasm,security-audit,reviewer-signoff}.md` | `power-16-closeout.md §2` |
| P17-C | `power-17-phase-c-2026-05-21` | Composite OR-union detector test; 23-cell FP-rate; `redroid-recapture.sh`; P-12 audit; both endgates APPROVE; MEDIUM security advisory resolved within phase | 4174 | 82 | `power-17-closeout.md`, `power-17-{production-hooks-audit,security-audit,reviewer-signoff}.md`, `fp-rate-analysis.md` | `power-17-closeout.md §2` |
| P18-D | (in flight) | E2E `detection-cli` + CI blocking-gates + this corpus-index | TBD | 82 | (this document); detection-CLI + CI hook (D1+D2 parallel) | (Power-18 closeout pending) |

**Notes**:
- Test counts P9/P10/P11 cited as approximations in `power-13-closeout.md §2`; only P8 (3323) and P11 (3617) have exact pre-P12 numbers in the closeout corpus.
- P13 jumps to 81 because of +8 new Phase-B probes; P16-B re-aligns at 82 with `IntegrityInstallSourceProbe` rank 10.5.
- "no separate closeout" rows for P9–P12: rolled into Power-13 closeout §2 progression table.

---

## §2 — Honest-Synthesis Provenance (per snapshot fixture)

| Snapshot | Provenance | Live-capture path | Source-of-truth |
|---|---|---|---|
| **Pixel7Clean** | Synthesized in Power-3/5 (no live Pixel-7 capture); values from canonical Pixel-7 production buildprops + AOSP | Owner-action: factory-image dump or live `getprop` over `adb` | `power-13-closeout.md §5`; `power-15-pre-audit.md §1` |
| **SamsungS22Clean** | Synthesized in Power-10; canonical Samsung S22 buildprops + OEM specs | Owner-action: factory-image dump or live `getprop` | `power-13-closeout.md §5` |
| **RedroidV12** | Phase-B (Power-13) synthesized 8 fields from canonical-public-sources (HuskyDG + RootBeerFresh + AOSP); base layer captured | OB2: live recapture via `scripts/redroid-recapture.sh` (delivered Power-17 C3 commit `6fb4ad1`) | `power-13-closeout.md §5`; `scripts/redroid-recapture.sh` |
| **RedroidSpoofed** | Synthesized as RedroidV12 + spoofstack-config mutations (every diverging field annotated as Magisk/LSPosed hook in `production-hooks-spec.md`) | Owner-action: deploy SpoofStack module + live probes via `docker exec` | `production-hooks-spec.md §1-§6` |
| **FridaInjectedRedroid** | Synthesized in Power-15 A1 (`e74997d`) from DetectFrida `native-lib.c` (HIGH) + Frida `frida-gum` + `frida.re/docs/gadget/` | Owner-action: inject Frida gadget into live RedroidV12 | `power-15-canonical-sources.md §A1`; `power-15-closeout.md §3.1` |
| **Nox** | Synthesized in Power-15 A3 (`2ba76d6`) — HIGH on `framgia/android-emulator-detector` file-array citations; MEDIUM on build-prop substrings | Owner-action: live Nox-installation capture | `power-15-canonical-sources.md §A3` |
| **Genymotion** | Synthesized in Power-15 A3 from official Genymotion support docs (HIGH on file-paths + build-props) | Owner-action: live Genymotion-installation capture | `power-15-canonical-sources.md §A3` |
| **BlueStacks** | **Minimal-encoded** — only `com.bluestacks.*` package prefix publicly verifiable; GAP items explicitly absent per Power-14 §1ter convention | Owner-action: live BlueStacks-instance capture | `power-15-closeout.md §5 #2` |

**Synthesis-honesty audit trail**: All 26 source URLs in `power-15-canonical-sources.md` carry explicit confidence tier (HIGH/MEDIUM/LOW/GAP). 7 GAP items confirmed ABSENT from all fixtures by both endgate audits.

---

## §3 — Owner-Carryover (OB1–OB5) — current state

Numbering anchored to `power-17-closeout.md §5.1`.

| ID | Item | Origin | Current state (2026-05-21) | Required to unblock |
|---|---|---|---|---|
| **OB1** | PAR822349 server reboot (host kernel HWE 5.4) | `power-14-closeout.md §9 #1` | **OPEN** — blocks OB3 (kernel-W^X SELinux module) and OB2 | Owner physical/IPMI access to PAR822349 host |
| **OB2** | Live RedroidV12 re-capture via `scripts/redroid-recapture.sh` | Power-13 §7 #2 — script delivered Power-17 C3 commit `6fb4ad1` (472 lines) | **SCRIPT READY, EXECUTION PENDING** | OB1 OR owner-decision to capture pre-reboot |
| **OB3** | Native-layer deploy (Magisk modules + LSPosed + `libgotscan.so`) per `production-hooks-spec.md §P-12` | `power-14-closeout.md §9 #3` | **OPEN** — §P-12.1 (FridaKill) deployable today; §P-12.2/.3 (W^X SELinux + libgotscan.so) require OB1 + kernel-module dev | OB1 + kernel-module development workstream |
| **OB4** | Live APK-tests in deployed container | `power-14-closeout.md §9 #4` | **OPEN** — depends on OB3 | OB3 |
| **OB5** | T11+T12 production-only replay (MediaProjection callback) | `power-16-closeout.md §5 #8` | **OPEN** — bucket-(d) un-snapshottable | OB3 + live anti-fraud-positive app deployment |

**Status summary**: All 5 owner-carryovers are gated on PAR822349 reboot (OB1) as the single root-cause blocker. Zero progression on any OB item across Powers 13→17.

---

## §4 — Hard Ceilings (out-of-spoof-stack-scope; tracked, not promised)

Source-of-truth: `un-snapshottable.md` + `power-13-closeout.md §8` + `production-hooks-spec.md §P-12`.

| Rank | Probe / Surface | Why ceiling | Mitigation | 2026 bypass viable? |
|---|---|---|---|---|
| **2** | `integrity.play_integrity` (LIVE API call) | Google-TEE-signed JWT; only real Pixel device with provisioning-time-burned TEE key can produce `MEETS_STRONG_INTEGRITY` | L0 / L4+L5 ephemeral (TrickyStore, 4–8 week windows) | Partial — DEVICE only, ephemeral |
| **6** | `integrity.keystore_attestation` | Live X.509 attestation cert chain signed by device-TEE key | L0 / L4+L5 ephemeral | Partial-ephemeral only |
| **5** | `network.ip_asn` | Source IP as seen by detector backend | L6 (residential proxy or 4G stick) | **Yes** |
| **9.0** | `runtime.frida_memory_maps` | Declarative variant; production runtime still leaks LSPosed lib | L4 (hide-frida-maps Xposed in `stack/L4/`) + Magisk FridaKill | **Yes** |
| **9.7** | `runtime.native_prologue_hash` | In-memory byte hash of `libc.so`/`libart.so` prologues vs disk; inline hook trampolines diverge | **L0 — UNCOUNTERED in FOSS 2026** | **No** |
| **9.8** | `integrity.prologue_got_hooks` | GOT region scan detects overwritten entries | **L0 — UNCOUNTERED in FOSS 2026** | **No** |
| **41** | `env.gps_coordinates` (LIVE GNSS) | ReDroid has no GNSS HAL | L4 mock / L5 kernel HAL stub | Yes via L5 |
| **33.5** | `env.time_spoofing` D2/D3/D4 deltas | NTP / GPS / boot-anchor cross-validation | L6 NTP intercept + L4 GPS hook | Yes via combined |
| **52.5** | `runtime.screen_recording` | Live framework callback for active capture | L4 LSPosed hook | Yes |
| **29** | `identity.mediadrm` Widevine L1 | Probe: L4 LSPosed; broader DRM: L0 factory-burned keybox | L4 for probe / L0 for adjacent | Partial |
| **54/55/56** | `ui.audio_fingerprint`, `ui.canvas_fingerprint`, `ui.webgl_fingerprint` | WebView GPU divergence (SwiftShader vs Adreno/Mali) | L4 LSPosed JS-shim | Partial |
| **n/a** | DetectFrida ELF .text CRC | Power-14 §3.2: NOT modeled in union-replay; covered by 9.7/9.8 declarative | Same as 9.7/9.8 — **L0** | **No** |

**Hard-ceiling count (rank-anchored, distinct surfaces)**: 12. Load-bearing UNCOUNTERED-in-FOSS-2026 ceilings: ranks **9.7**, **9.8**, and **6 STRONG**.

**Anti-Verarschen disposition**: Every hard ceiling is `mitigation_layer: not_spoofable` in `inventory.yml` (where applicable) or has explicit KDoc on probe. The spoof-stack does NOT claim coverage on these surfaces.

---

## §5 — Cross-Cutting Followups (#1–#8) — current status

Source-of-truth: `audit/cross-cutting-followups-2026-05-19.md` (note: NOT in `audit/spoof-stack/`).

| # | Topic | Status | Resolution |
|---|---|---|---|
| 1 | Evidence-key namespace collision | **CLOSED 2026-05-20** | Probe-scoped namespacing: `su_search.pkg.*` / `xposed.pkg.*` / `installed_apps.pkg.*` |
| 2 | Unverified package IDs in rank-10 marker list | **CLOSED 2026-05-20** | 4 entries removed; 1 corrected; 3 clones verified |
| 3 | `ProbeContext` lacks `querySettingGlobal/System` | **CLOSED 2026-05-20** | Both accessors added with default delegations; 4 probes migrated |
| 4 | `inventory.yml` rank-20 description divergence | **CLOSED 2026-05-20** | Description updated |
| 5 | Pixel 8 Pro density telemetry | **CLOSED 2026-05-20** | Mod-20 cross-rank invariant retired |
| 6 | `SensorSample` ragged-array contract | **CLOSED 2026-05-20** | KDoc invariants added |
| 7 | `Probe.rank Int` vs `inventory.yml` Double | **PARTIAL — RFC under pressure** | `inventoryRank: Double` two-field workaround. **By Power-16, 4 probes diverge**: `DebuggerTracerPidProbe`, `ScreenLockProbe`, `LocationMockRaspProbe`, `IntegrityInstallSourceProbe`. `power-16-closeout.md §5 #10` flags as "cost-benefit RFC candidate" for Int→Double interface migration. |
| 8 | `TikTokArgusSigningProbe` A10+ | **CLOSED 2026-05-20** | A10+ routed to `a10_plus_accessor_gap` pattern |

**Net**: 7 of 8 closed; **#7 remains under RFC pressure** with 4 probes diverging (carried over in `power-17-closeout.md §5.4`).

---

## §6 — Cross-Reference Index of all `audit/spoof-stack/` docs

### §6.1 — Power-8 (pre-Power-13 ancillary)

| File | 1-line summary |
|---|---|
| `iter-baseline.md` | Power-8 SpoofStack full-panel residual baseline — 63 probes, 6 non-zero residuals; per-rank residual table |
| `detection-resistance-report.md` | Power-8 detection-resistance status report — extended through P12 §0 update closing TRUE 73/73 |
| `production-hooks-spec.md` | Production Magisk + LSPosed hook spec — 697 lines; 6 hook categories; P-12 native-layer addendum at lines 630-696 |
| `un-snapshottable.md` | Bucket-(d) un-snapshottable surface taxonomy — 9 probe-surfaces with L0–L6 mitigation layers |

### §6.2 — Power-13

| File | 1-line summary |
|---|---|
| `power-13-closeout.md` | Power-13 closeout — 5 detector families replay-tested; 4/5 bypass-able; 4145 tests; 81-probe panel |
| `real-world-detectors.md` | Researcher heuristic inventory of Top-5 detector apps + FULL/PARTIAL/MISSING mapping |
| `real-world-gap-list.md` | Power-13 priority gap list — CRITICAL (6) + MEDIUM (3) |
| `detector-replay-results.md` | Power-13 verdict matrix — 5 detectors × 4 snapshots = 20 PASS/FAIL cells |

### §6.3 — Power-14

| File | 1-line summary |
|---|---|
| `power-14-closeout.md` | 1 of 5 detectors (RootBeer 0.1.1 AAR) reached full decompile; 4 critical Power-13 replay-bugs fixed; 4150 tests |
| `power-14-apk-source-diff.md` | RootBeer 9-branch `isRooted()` decision rule reconstructed from bytecode |

### §6.4 — Power-15 (Phase-A)

| File | 1-line summary |
|---|---|
| `power-15-closeout.md` | 4 new fixtures + 648-cell matrix; 4155 tests; both endgates APPROVE |
| `power-15-pre-audit.md` | DeviceSnapshot/ProbeContext inventory; 5 missing-view ranks documented |
| `power-15-canonical-sources.md` | A0 — 26 public URLs with HIGH/MEDIUM/LOW/GAP tiers; 7 GAP items |
| `power-15-security-audit.md` | APPROVE 6/6 pillars |
| `power-15-reviewer-signoff.md` | APPROVE 7/7 criteria |
| `full-coverage-matrix.md` | 656-cell verdict matrix (auto-generated, post-P16); §3 enumerates anomalies |

### §6.5 — Power-16 (Phase-B)

| File | 1-line summary |
|---|---|
| `power-16-closeout.md` | freeRASP source-diff + RootBeer native + install_source probe; 4165 tests / 82 probes |
| `power-16-freerasp-source-diff.md` | T-numbering anchored to docs.talsec.app; honest-limited discipline |
| `power-16-native-disasm.md` | libtoolChecker.so ARM64+x86_64; 3 SHA256-pinnable hashes; zero-hidden-paths finding |
| `power-16-security-audit.md` | APPROVE 6/6 pillars; 1 license WARN |
| `power-16-reviewer-signoff.md` | APPROVE 7/7 criteria; 10 carry-overs |

### §6.6 — Power-17 (Phase-C)

| File | 1-line summary |
|---|---|
| `power-17-closeout.md` | Composite + FP + Recapture + P-12 audit; 4174 tests; MEDIUM security advisory resolved within phase |
| `fp-rate-analysis.md` | 23 FP-on-Clean cells classified; 3 production-budget violations; Phase-D 0-residual projection |
| `power-17-production-hooks-audit.md` | Read-only P-12 audit; 19 implemented + 7 blocked + 3 stale + 0 missing |
| `power-17-security-audit.md` | APPROVE 6 pillars; 1 MEDIUM advisory (resolved) |
| `power-17-reviewer-signoff.md` | APPROVE 6/6 criteria; 13 carry-overs |

### §6.7 — Non-Power Ancillary

| File | Location | 1-line summary |
|---|---|---|
| `cross-cutting-followups-2026-05-19.md` | `audit/` (NOT in `audit/spoof-stack/`) | Cross-cutting followups #1–#9 — 7 closed; #7 (rank Int-vs-Double) under RFC |

---

## §7 — Future Roadmap

### §7.1 — Power-18 Phase-D (currently in flight)

- **D1** `detection-cli` E2E against 8 snapshots + JSON + score-aggregation
- **D2** CI blocking gates per PR (0 failures, weightedScore=0, panel consistency, namespace compliance)
- **D3** This corpus-index

### §7.2 — Phase-D Quality-Bar Work (from `power-17-closeout.md §5.2`)

**IMMEDIATE — probe-logic fixes (6 probes, 11 FP cells)**:
- `integrity.keystore_attestation` (CRITICAL violation forcing closure)
- `identity.imei_serial` (A10+ SecurityException)
- `identity.wifi_mac` (A10+ sentinel)
- `env.timezone_locale_mismatch` (HIGH; allow-list cross-locale)
- `identity.sim_iccid` (HIGH; ABSTAIN on SIM_STATE_ABSENT)
- `network.dns_server` (ABSTAIN on no network)

**PLANNED — fixture extensions (7 probes, 12 FP cells)**: `debugger_tracerpid`, `android_id`, `screen_resolution`, `language_country`, `location_mock_rasp`, `system_fonts`, `input_method`.

**Projection**: 23 closures → 0 residual FP-on-Clean (target <10). Conservative: 6 residual.

### §7.3 — P-12 Spec Disposition

- **Option A (default)**: keep `production-hooks-spec.md` frozen; `power-17-production-hooks-audit.md` is handoff delta
- **Option B**: author `power-17-production-hooks-spec-v2.md` (11 missing hook categories + 3 stale corrections). **Requires explicit owner approval per plan-immutability**

### §7.4 — P19+ Anti-Bypass

- 5 missing-view ranks (env.time_spoofing, env.screen_lock, env.wifi_security_type, runtime.multi_instance, runtime.screen_recording)
- T8 (self-obfuscation) + T7 (device-binding-anchor) schema-RFC
- Cross-cutting #7 Int→Double interface migration RFC (4 probes diverging)
- T6 Substrate/Shadow framework tokens
- T4 native-code-section CRC + resources.arsc CRC

### §7.5 — P20 Final Tag

- Live deployment validation against PAR822349 (unblocks all 5 OB items)
- Live detector-app testing (real APKs in deployed container — OB4)
- Final corpus tag with all owner-actions either closed or explicitly declined

---

## §8 — Corpus Statistics

- **Total `audit/spoof-stack/*.md` files indexed**: 26
- **Cross-cutting followups doc**: 1 (at `audit/cross-cutting-followups-2026-05-19.md`)
- **Power-N closeouts with dedicated `power-N-closeout.md`**: 5 (P13-P17)
- **Power-N reviewer-signoff files**: 3 (P15-P17)
- **Power-N security-audit files**: 3 (P15-P17)
- **Owner-carryover items**: 5 (OB1–OB5)
- **Hard ceilings (rank-anchored)**: 12
- **Cross-cutting followups**: 8 — 7 closed, 1 partial (#7 under RFC)
- **L0-UNCOUNTERED-in-FOSS-2026 ceilings**: 3 (ranks 9.7, 9.8, 6 STRONG)
- **Inventory rank count**: 82
- **Test count trajectory**: P8=3323 → P11=3617 → P12=3668 → P13=4145 → P14=4150 → P15-A=4155 → P16-B=4165 → P17-C=4174

---

## §9 — Anti-Verarschen Disposition

Every claim is anchored to:
- a `power-N-closeout.md` § reference, OR
- an explicit `inventory.yml` rank, OR
- a verified file:line in `production-hooks-spec.md` / `un-snapshottable.md` / `cross-cutting-followups-2026-05-19.md`, OR
- explicitly marked `UNVERIFIED-pre-baseline` (P1-P7) or `unverifiable-publicly` (P14 §1 detector-set)

No test-count or commit-sha fabricated. P1-P7 left UNVERIFIED-pre-baseline rather than backfilled.

Hard ceilings marked **L0 — UNCOUNTERED in FOSS 2026** per `un-snapshottable.md §7b/§7c` — spoof-stack does NOT claim coverage on ranks 9.7, 9.8, or STRONG keystore attestation.

---

**End of corpus-index — Power-18 Phase-D Task #3 deliverable.**
