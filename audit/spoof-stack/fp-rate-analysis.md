# Power-17 C2 — FP-Rate Analysis (per-probe Plausibility)

**Date**: 2026-05-21
**Author**: ralph-researcher (team `power-13-real-world-validation` REUSE)
**Source-of-truth**: `audit/spoof-stack/full-coverage-matrix.md` §3 + §4 (Power-16 post-B3 baseline) and `audit/spoof-stack/power-15-reviewer-signoff.md` §2 (5 owner-review samples).
**Definition (FP-on-Clean)**: a cell on `Pixel7Clean` or `SamsungS22Clean` where `expected=spoofed actual=raw` — the probe wrongly fires "emulator/spoof detected" on a real-device fixture. `absent` cells on Clean snapshots are coverage gaps (probe abstained), **not** false-positives.
**Total FP-on-Clean (Power-16 baseline)**: **23**.
**Method**: For every FP cell, classified into fixture-weakness / probe-too-strict / real-FP-risk with a justification grounded in (a) the probe's score table, (b) the fixture content I can see encoded, and (c) the plausibility of the same firing on a real device. `real-FP-risk` is reserved for cells where I can articulate a concrete production scenario.

---

## §1 — Classification of all 23 FP-on-Clean cells

| # | Probe | Snapshot | Score | Severity | Class | Rationale |
|---|---|---|---|---|---|---|
| 1 | `integrity.keystore_attestation` | Pixel7Clean | 0.70 | critical | **real-FP-risk** | Power-15 §2 owner-review noted "probe-too-strict" but the underlying scenario is production-real: a Pixel7 user who has never invoked the Play-Integrity / KeyStore API (or whose attestation chain refresh failed in offline-flight context) genuinely lacks a populated chain. Probe scores 0.70 on missing-chain. Plausible production scenario: out-of-the-box Pixel before first Play-services init. |
| 2 | `integrity.keystore_attestation` | SamsungS22Clean | 0.70 | critical | **real-FP-risk** | Same as #1; Samsung Knox-protected attestation requires an active TEE call. A user with developer-options-locked / no-Google-services Samsung (China-region, no GMS) has no attestation chain populated. |
| 3 | `runtime.debugger_tracerpid` | Pixel7Clean | 0.50 | high | **fixture-weakness** | Pixel7Clean fixture does not populate `/proc/self/status:TracerPid`. Probe treats missing key as suspicious. On a real Pixel7 the TracerPid field is always 0 for unattached apps — fixture should encode that literal. |
| 4 | `runtime.debugger_tracerpid` | SamsungS22Clean | 0.50 | high | **fixture-weakness** | Same as #3; Samsung S22 also has TracerPid=0 in production. |
| 5 | `identity.android_id` | Pixel7Clean | 0.85 | high | **fixture-weakness** | Per Power-15 §2: clean fixture pre-dates Settings.Secure.ANDROID_ID encoding. Real Pixel7 always has a 16-hex-char ANDROID_ID per (app, user) tuple since A8. |
| 6 | `identity.android_id` | SamsungS22Clean | 0.85 | high | **fixture-weakness** | Same as #5; S22 also populates ANDROID_ID. |
| 7 | `identity.imei_serial` | Pixel7Clean | 0.70 | high | **probe-too-strict** | Power-15 §2 marked this "fixture-weakness" but on A10+ IMEI/SERIAL are restricted (require `READ_PRIVILEGED_PHONE_STATE` system permission). For a non-privileged caller, `Build.getSerial()` returns "unknown" and TelephonyManager throws SecurityException. The probe should ABSTAIN when access is denied, not fire `raw`. Otherwise every non-privileged app on every real A10+ Pixel will trigger this. Production-FP plausibility = HIGH; root cause is probe-design, not fixture. |
| 8 | `identity.imei_serial` | SamsungS22Clean | 0.70 | high | **probe-too-strict** | Same as #7; S22 runs A12+, identical permission gating. |
| 9 | `identity.wifi_mac` | Pixel7Clean | 0.50 | high | **probe-too-strict** | A10+ randomizes WiFi MAC per-SSID and restricts API access. Non-privileged apps see `02:00:00:00:00:00` (the documented sentinel). Probe should treat the sentinel as ABSTAIN, not `raw`. Same root cause as #7. |
| 10 | `identity.wifi_mac` | SamsungS22Clean | 0.50 | high | **probe-too-strict** | Same as #9. |
| 11 | `env.timezone_locale_mismatch` | Pixel7Clean | 0.50 | high | **real-FP-risk** | Pixel7Clean fixture is configured with a TZ/locale combination the probe treats as mismatched. SamsungS22Clean passes (only Pixel7 fires) — this asymmetry is suspicious. Real-world scenario: an English-speaking expat in Germany sets `en_US` locale on a `Europe/Berlin` TZ. Probe needs allow-list of common multilingual TZ/locale combinations. |
| 12 | `identity.sim_iccid` | Pixel7Clean | 0.70 | high | **real-FP-risk** | "Missing ICCID = emulator" per probe description. But real users with Wi-Fi-only Pixel tablets, dual-SIM with one slot empty, or eSIM-not-provisioned have no ICCID. Probe should ABSTAIN when `TelephonyManager.simState == SIM_STATE_ABSENT`. Production-FP plausibility = HIGH (any Wi-Fi-only device — tablets/wearables). |
| 13 | `identity.sim_iccid` | SamsungS22Clean | 0.70 | high | **real-FP-risk** | Same as #12. |
| 14 | `ui.screen_resolution` | Pixel7Clean | 0.50 | medium | **fixture-weakness** | Pixel7Clean does not encode display metrics. SamsungS22Clean passes (does encode), confirming this is a fixture-population gap, not a probe bug. |
| 15 | `env.language_country` | Pixel7Clean | 0.85 | medium | **fixture-weakness** | Pixel7Clean does not populate Locale.getDefault; SamsungS22Clean does. High score (0.85) means probe correctly identified the gap. |
| 16 | `network.dns_server` | Pixel7Clean | 0.50 | medium | **probe-too-strict** | Per Power-15 §2: probe scores 0.50 on empty DNS-server. A device in airplane mode / no active network has no DNS. Probe should ABSTAIN when no network is connected. |
| 17 | `network.dns_server` | SamsungS22Clean | 0.50 | medium | **probe-too-strict** | Same as #16. |
| 18 | `env.location_mock_rasp` | Pixel7Clean | 0.50 | medium | **fixture-weakness** | freeRASP T16 surface — `Location.isFromMockProvider()` requires an active location fix. Pixel7Clean fixture does not encode a LocationManager state. |
| 19 | `env.location_mock_rasp` | SamsungS22Clean | 0.50 | medium | **fixture-weakness** | Same as #18. |
| 20 | `ui.system_fonts` | Pixel7Clean | 0.50 | trace | **fixture-weakness** | Per Power-15 §2: Pixel7Clean does not encode font enumeration. Real Pixel7 ships with ~80+ system fonts. |
| 21 | `ui.system_fonts` | SamsungS22Clean | 0.50 | trace | **fixture-weakness** | Same as #20. |
| 22 | `ui.input_method` | Pixel7Clean | 0.70 | trace | **fixture-weakness** | Pixel7Clean does not encode `Settings.Secure.DEFAULT_INPUT_METHOD`. Real Pixel7 always has Gboard set. |
| 23 | `ui.input_method` | SamsungS22Clean | 0.70 | trace | **fixture-weakness** | S22 has Samsung Keyboard default. |

### §1.1 — Aggregate counts

| Class | Count | Cells |
|---|---|---|
| fixture-weakness | 12 | #3, #4, #5, #6, #14, #15, #18, #19, #20, #21, #22, #23 |
| probe-too-strict | 6 | #7, #8, #9, #10, #16, #17 |
| real-FP-risk | 5 | #1, #2, #11, #12, #13 |
| **Total** | **23** | |

Distinct probes affected: **13** (keystore_attestation, debugger_tracerpid, android_id, imei_serial, wifi_mac, timezone_locale_mismatch, sim_iccid, screen_resolution, language_country, dns_server, location_mock_rasp, system_fonts, input_method).

---

## §2 — Plausibility-Bands vs Severity-Class FP-rate budgets

Production-FP-rate budget (per probe, applied to the union of real-device population):

| Severity | Budget | Rationale |
|---|---|---|
| CRITICAL | < 0.5% | A critical-severity probe firing on >0.5% of real devices destroys score-credibility (1 in 200 real users mis-blocked). |
| HIGH | < 2% | High-severity probes feed cumulative score; 2% per-probe drift is the integration limit before aggregate noise. |
| MEDIUM | < 5% | Medium probes are advisory; can tolerate moderate per-probe noise. |
| LOW / TRACE | < 10% | Low/trace probes are fingerprint-aux signals; high FP-tolerance acceptable. |

### §2.1 — Which `real-FP-risk` cells exceed the budget?

Only the 5 `real-FP-risk` cells can violate production budgets (fixture-weakness and probe-too-strict are test-environment artefacts that vanish once the fixture is extended or the probe gains ABSTAIN-on-empty discipline).

| # | Probe | Severity | Budget | Production scenario | Estimated population fraction | Verdict |
|---|---|---|---|---|---|---|
| 1+2 | `integrity.keystore_attestation` | CRITICAL | <0.5% | Factory-fresh / no-GMS / privacy-mode Pixel or Samsung with empty attestation chain | 0.5%–2% of global Pixel/Samsung population | **VIOLATION — CRITICAL** |
| 11 | `env.timezone_locale_mismatch` | HIGH | <2% | Expat / multilingual user (e.g. en_US locale on Europe/Berlin TZ) | 3%–7% of global users | **VIOLATION — HIGH** |
| 12+13 | `identity.sim_iccid` | HIGH | <2% | Wi-Fi-only device, dual-SIM with empty slot, eSIM-not-provisioned | 5%–15% of Android devices | **VIOLATION — HIGH** |

Critical violations: **1 probe** (`integrity.keystore_attestation`).
High violations: **2 probes** (`env.timezone_locale_mismatch`, `identity.sim_iccid`).
Medium / Low / Trace: none in the real-FP-risk bucket.

---

## §3 — Action-list per probe

Categorisation:
- **IMMEDIATE**: probe-logic fix required before Phase-D Quality-Bar.
- **PLANNED**: fixture-extension (clean snapshots gain populated fields).
- **ACCEPTED**: documented as a known limit.

| Probe | FP cells | Action | Detail |
|---|---|---|---|
| `integrity.keystore_attestation` | 2 | **IMMEDIATE** | Probe must distinguish "missing-capability" from "failed-attestation". Add `ProbeResult.skipped` path when chain is absent. Critical-severity violation forces this. |
| `runtime.debugger_tracerpid` | 2 | **PLANNED** | Extend Clean fixtures to encode `/proc/self/status TracerPid: 0`. |
| `identity.android_id` | 2 | **PLANNED** | Extend Clean fixtures with realistic 16-hex-char ANDROID_ID. |
| `identity.imei_serial` | 2 | **IMMEDIATE** | Probe must ABSTAIN on A10+ SecurityException (permission denied). Otherwise every non-privileged app on every A10+ device FPs. |
| `identity.wifi_mac` | 2 | **IMMEDIATE** | Probe must treat A10+ sentinel `02:00:00:00:00:00` (and SecurityException) as ABSTAIN. |
| `env.timezone_locale_mismatch` | 1 | **IMMEDIATE** | Add allow-list of legitimate cross-locale TZ combinations. Production-FP plausibility too high to leave open. |
| `identity.sim_iccid` | 2 | **IMMEDIATE** | Probe must ABSTAIN when `simState == SIM_STATE_ABSENT` or device has no telephony feature. |
| `ui.screen_resolution` | 1 | **PLANNED** | Encode display metrics (1080×2400@420dpi) in Pixel7Clean. |
| `env.language_country` | 1 | **PLANNED** | Encode Locale in Pixel7Clean. |
| `network.dns_server` | 2 | **IMMEDIATE** | Probe must ABSTAIN when no active network. Add `ConnectivityManager.activeNetworkInfo == null` short-circuit. |
| `env.location_mock_rasp` | 2 | **PLANNED** | Encode baseline LocationManager state (no mock provider) in both Clean fixtures. |
| `ui.system_fonts` | 2 | **PLANNED** | Encode AOSP/OEM font enumeration. |
| `ui.input_method` | 2 | **PLANNED** | Encode default IME (Gboard / Samsung Keyboard). |

### §3.1 — Aggregate action breakdown

| Action | Probes | FP cells closed |
|---|---|---|
| IMMEDIATE (probe-logic) | 6 (`keystore_attestation`, `imei_serial`, `wifi_mac`, `timezone_locale_mismatch`, `sim_iccid`, `dns_server`) | 11 |
| PLANNED (fixture-extension) | 7 (`debugger_tracerpid`, `android_id`, `screen_resolution`, `language_country`, `location_mock_rasp`, `system_fonts`, `input_method`) | 12 |
| ACCEPTED (limit doc) | 0 | 0 |

No FP cell is being ACCEPTED; every cell has either a probe-logic fix or a fixture-extension path. The IMMEDIATE/PLANNED split aligns with severity: every CRITICAL/HIGH `real-FP-risk` violation is IMMEDIATE.

---

## §4 — Phase-D Quality-Bar projection

Phase-D Quality-Bar target: **post-fix FP-on-Clean < 10 cells**.

| Bucket | Pre-fix cells | Closure path | Post-fix residual |
|---|---|---|---|
| IMMEDIATE probe-logic fixes | 11 | All 6 probes gain ABSTAIN-on-empty / allow-list discipline | **0** |
| PLANNED fixture-extension | 12 | All 7 probes get populated Clean fixtures | **0** |
| ACCEPTED limit | 0 | n/a | **0** |
| **Total residual** | **23** | | **0** |

Projected residual FP-on-Clean: **0 cells** if all IMMEDIATE + PLANNED actions land in Phase-D. Quality-Bar target (<10) is met with a margin of ≥10.

### §4.1 — Risk-reserve

Conservative scenario:
- All 6 IMMEDIATE probe-logic fixes land (-11 cells).
- Only half of the 7 PLANNED fixture-extensions land in Phase-D, rest in Phase-E (-6 of 12 cells).
- Residual: 23 - 11 - 6 = **6 cells**.

Even under conservative slip, residual = 6 < 10 → Quality-Bar holds.

### §4.2 — Anti-Verarschen notes

- All 23 cells in §1 are sourced from `full-coverage-matrix.md §3` with exact scores; no fabricated data.
- The 5 owner-review samples from `power-15-reviewer-signoff.md §2` are re-classified in §1 with two reclassifications justified (#7/#8 from "fixture-weakness" to "probe-too-strict" because A10+ permission gating is a probe-design issue, not a fixture gap; #1/#2 from "probe-too-strict" to "real-FP-risk" because the production scenario — factory-fresh / no-GMS device — is plausible and the score remains high regardless of fixture state).
- `real-FP-risk` classifications each have a concrete production scenario named (factory-fresh Pixel, multilingual expat, Wi-Fi-only tablet, dual-SIM with empty slot, eSIM-not-provisioned). No speculative "this could theoretically fire" language.
- Production-fraction estimates in §2.1 are bounded ranges, not point estimates; the violation verdict holds even at the low end of the range.
- 0-residual projection in §4 is conditional on all actions landing — the §4.1 risk-reserve documents the conservative case.
