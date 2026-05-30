# Anti-Spoof ≥80% Effectiveness — Metric Definition & Unspoofed Baseline

**Scope:** Define the precise, defensible ≥80% anti-spoof effectiveness metric
validated against the REAL detector apps installed live in a ReDroid 12
container, and capture the UNSPOOFED baseline.

**Source of truth (read-only):**
- Harness: `scripts/p21/run-all-checks.py` (`harness_version = "p21-c-1.0"`)
- Baseline reports: `p21/report.json`, `p21/report-ext.json`
- Install inventory: `p21/install-report-merged.json`, `p21/install-report-ext.json`, `p21/install-report.json`
- Target device: `172.17.0.2:5555` (ReDroid 12), per both reports' `redroid_target`.

This document states only what those files actually contain. Anything not
directly evidenced is marked **UNVERIFIED**.

---

## 1. How the P21-C harness works

The harness `scripts/p21/run-all-checks.py` runs **3 tests (T1 cold-boot,
T2 warm-reboot, T3 prop-diff)** against every app marked `status == "installed"`
in the install report. Each (app × T) pair is one "cell". Reports emit 99 cells
(`docstring`, lines 4–6).

### 1.1 Install (adb) — done by a separate script, consumed here

The check harness does **not** install apps itself; it reads the install report.
Installation is performed by `scripts/p21/install-apps.sh` and
`scripts/p21/install-apps-ext.sh`, which use:
- `adb connect "$ADB_SERIAL"` then `adb -s "$ADB_SERIAL" wait-for-device`
  (`install-apps-ext.sh:27-28`, default `ADB_SERIAL=172.17.0.2:5555`).
- `adb -s "$ADB_SERIAL" install-multiple "${apk_files[@]}"` for split/xapk bundles,
  falling back to `adb -s "$ADB_SERIAL" install -r "$base_apk"`
  (`install-apps-ext.sh:74,85,114`).
- Writes per-app `status` (`installed` / `aurora-required` / `skip` /
  `install-failed`) into the install-report JSON.

The check harness loads that report via `load_install_report()`
(`run-all-checks.py:309-315`): apps with `status == "installed"` become
**testable**; everything else becomes **not-tested** cells.

### 1.2 Launch (adb)

For each installed app (`build_cells`, `run_test_for_app`):
1. Pre-resolve the launcher component:
   `adb shell cmd package resolve-activity --brief <pkg>` → `pkg/activity`
   (`resolve_launcher`, lines 142-154). No launcher ⇒ `NOT-TESTED`
   (`not_tested_reason = "no-launcher-activity"`).
2. `adb shell am force-stop <pkg>` (line 355).
3. **T2 only:** `adb reboot` (TimeoutExpired tolerated), `wait_for_device`
   (≤90 s), then settle 20 s (lines 360-370).
4. **T3 only:** capture `adb shell getprop` pre-launch (lines 373-376).
5. Launch: `adb shell am start -n <pkg/activity>` (line 380). Nonzero rc ⇒
   `verdict = "CRASH"` with evidence `am.start.nonzero_rc` (lines 381-389).
6. Settle `LAUNCH_SETTLE_SEC = 8 s`.
7. Special case: for `com.henrikherzig.playintegritychecker` it taps the
   "Make Play Integrity Request" button (`input tap 360 634`) then waits 5 s
   (lines 394-397).

### 1.3 Capture: focus + uiautomator XML + screenshot

- **Focus:** `adb shell dumpsys window | grep mCurrentFocus | head -1`, regex
  `\b([\w\.]+)/[\w\.\$]+` extracts the focused package (`get_focused_pkg`,
  171-180) and the full `pkg/activity` (`get_focused_activity`, 183-188).
- **UIA XML:** `adb shell uiautomator dump /data/local/tmp/window-<pkg>-<t>.xml`,
  then `cat` the remote file, written to `p21/uia/<pkg>-<t>.xml`
  (lines 405-413). Parsed by `collect_text_nodes` → all `text` and
  `content-desc` attribute values (191-203).
- **Screenshot:** `adb exec-out screencap -p` → `p21/screenshots/<pkg>-<t>.png`
  (lines 416-419).
- **T3 prop-diff:** post-launch `getprop` is unified-diffed against the
  pre-launch dump → `p21/props/<pkg>-T3-diff.txt` (lines 421-433).

### 1.4 EXACT verdict-decision logic (`extract_verdict`, lines 206-274)

Evaluated in this order:

| Order | Condition (on focused package) | Verdict |
|---|---|---|
| 1 | focused pkg ∈ `LAUNCHER_PKGS` (launcher3 / nexuslauncher / launcher) | **CRASH** (`focus.is_launcher`) |
| 2 | focused pkg is `None` (no `mCurrentFocus`) | **UNKNOWN** (`focus.unavailable`) |
| 3 | focused pkg ∈ `SYSTEM_OVERLAY_PKGS` (permissioncontroller / systemui) | **UNKNOWN** (`focus.system_overlay`) — app launched but UI obscured |
| 4 | focused pkg ≠ target pkg | **CRASH** (`focus.wrong_pkg`) |
| 5 | focused pkg == target pkg | proceed to keyword scan |

Keyword scan (case-insensitive substring match over all `text`/`content-desc`
node values; lines 253-274):
- Collect `pass_hits` (any `PASS_KEYWORDS` substring) and `fail_hits` (any
  `FAIL_KEYWORDS` substring).
- `fail_hits and not pass_hits` ⇒ **FAIL**
- `pass_hits and not fail_hits` ⇒ **PASS**
- otherwise (both or neither) ⇒ **UNKNOWN**

`PASS_KEYWORDS` (lines 70-75):
`device_integrity, meets_device_integrity, meets_strong_integrity, "not rooted",
"not emulator", "basic integrity: true", "real device", "no root",
"ctsprofilematch: true", "hardware-backed"`.

`FAIL_KEYWORDS` (lines 86-95):
`rooted, "is rooted", "emulator detected", "virtual device", violation,
"basic integrity: false", test-keys, "test keys", redroid, "mock location",
"mock provider", "ro.debuggable=1", "abnormal environment", suspicious,
"bootloader is unlocked", "tampered with", "software attestation",
"does not support hardware-level", "service unavailable"`.

> Note (from harness comments, lines 83-85): bare `x86` is deliberately **not**
> a FAIL keyword because it appears in benign device-info strings (e.g.
> `system-x86_64-ab.img.xz`). FAIL detection uses more specific phrases.

`EXPECTED_VERDICT` (lines 39-65) is a separate per-app expectation table used
only for the `matches_expected` bookkeeping field; it does **not** drive the
observed verdict.

---

## 2. Unspoofed baseline — per-app verdict table

Two baseline runs exist, both UNSPOOFED, both target `172.17.0.2:5555`:

| Report | harness | generated_at | installed/testable | verdict_counts |
|---|---|---|---|---|
| `p21/report.json` | p21-c-1.0 | 2026-05-21T14:04:46Z | 7 apps / 21 cells | FAIL 12, UNKNOWN 9, NOT-TESTED 78 |
| `p21/report-ext.json` | p21-c-1.0 | 2026-05-21T15:43:26Z | 23 apps / 69 cells | FAIL 15, UNKNOWN 45, CRASH 9, NOT-TESTED 30 |

`report-ext.json` is the superset (the original 7 + 16 extended apps = 23
installed) and is the authoritative baseline used below. **T1, T2, and T3
verdicts are identical for every app** in `report-ext.json` (verified: no app
has differing verdicts across the three tests), so the per-app (T1) verdict is
representative.

### 2.1 Full installed-app baseline (23 apps, from `report-ext.json`, T1)

| App (pkg) | Verdict | Key evidence |
|---|---|---|
| rikka.safetynetchecker | FAIL | xml.contains.redroid |
| com.henrikherzig.playintegritychecker | UNKNOWN | focus.on_target (no PASS/FAIL kw) |
| com.byxiaorun.detector | FAIL | xml.contains.suspicious, xml.contains.abnormal environment |
| icu.nullptr.applistdetector | FAIL | xml.contains.suspicious, xml.contains.abnormal environment |
| io.github.vvb2060.keyattestation | FAIL | software attestation, tampered with, bootloader is unlocked, does not support hardware-level |
| tk.hack5.treblecheck | UNKNOWN | focus.on_target |
| com.mantle.verify | UNKNOWN | focus.system_overlay |
| krypton.tbsafetychecker | UNKNOWN | focus.system_overlay |
| com.joeykrim.rootcheck | UNKNOWN | focus.on_target |
| com.scottyab.rootbeer.sample | UNKNOWN | focus.on_target |
| com.scottyab.safetynet.sample | UNKNOWN | focus.on_target |
| ru.andr7e.deviceinfohw | FAIL | xml.contains.redroid |
| imoblife.androidsensorbox | UNKNOWN | focus.on_target |
| com.androidfung.drminfo | CRASH | focus.wrong_pkg |
| flar2.devcheck | CRASH | focus.wrong_pkg |
| com.evozi.deviceid | UNKNOWN | focus.system_overlay |
| com.akademiteknoloji.androidallid | UNKNOWN | focus.on_target |
| tw.reh.deviceid | UNKNOWN | focus.on_target |
| com.ubnt.usurvey | CRASH | focus.wrong_pkg |
| ua.com.streamsoft.pingtools | UNKNOWN | focus.on_target |
| net.techet.netanalyzerlite.an | UNKNOWN | focus.on_target |
| com.eclipsim.gpsstatus2 | UNKNOWN | focus.on_target |
| com.finalwire.aida64 | UNKNOWN | focus.on_target |

---

## 3. Excluded apps (non-countable noise — architectural / API-EOL)

These apps are **NOT software-spoofable** because they depend on
attestation/integrity infrastructure that is architecturally absent or
API-deprecated on ReDroid 12 — fixing them is out of scope for a software
spoofing layer. Justification quoted directly from the report `notes`:

> "SafetyNet API (used by YASNAC) was deprecated by Google 2024-01; 'service
> unavailable' is API-EOL noise, not a clean emulator-detected verdict."
>
> "Play Integrity (used by SPIC) requires TEE + Play Services — absent on
> Redroid; 'service unavailable' here is also API-class noise."

| Excluded app (pkg) | Class | Reason (architectural / API-EOL) |
|---|---|---|
| rikka.safetynetchecker (YASNAC) | SafetyNet | SafetyNet API EOL 2024-01 (Google) |
| com.scottyab.safetynet.sample | SafetyNet | SafetyNet API EOL 2024-01 (Google) |
| com.henrikherzig.playintegritychecker (SPIC) | Play Integrity | needs TEE + Play Services (absent on ReDroid) |
| krypton.tbsafetychecker (TB Checker) | Play Integrity-class | needs TEE + Play Services (absent on ReDroid) |
| io.github.vvb2060.keyattestation | Hardware key attestation | no TEE on ReDroid → AOSP software cert only |

**Count excluded: 5.**

> Caveat (anti-fabrication): `rikka.safetynetchecker` currently reports **FAIL**
> via `xml.contains.redroid` — i.e. its on-screen text includes the string
> "redroid", which the harness keyword-matches. This is a UI string leak, not a
> SafetyNet-API result. It is still excluded from the denominator because the
> *app class* (SafetyNet) is API-EOL and not the software-spoof target; this is
> a defensible exclusion, but the specific "redroid" text leak is independently
> a hostname/build-string spoofing concern. Marked for awareness.

---

## 4. The denominator: software-spoofable, meaningful-verdict apps

**Denominator = the 18 installed apps that are software-spoofable detectors
yielding a meaningful pass/fail verdict** (23 installed − 5 excluded = 18).

| # | App (pkg) | Baseline verdict (T1=T2=T3) |
|---|---|---|
| 1 | com.byxiaorun.detector (Ruru) | **FAIL** (suspicious / abnormal environment) |
| 2 | icu.nullptr.applistdetector | **FAIL** (suspicious / abnormal environment) |
| 3 | ru.andr7e.deviceinfohw (Device Info HW) | **FAIL** (redroid) |
| 4 | com.joeykrim.rootcheck (Root Checker) | UNKNOWN |
| 5 | com.scottyab.rootbeer.sample (RootBeer) | UNKNOWN |
| 6 | tk.hack5.treblecheck (Treble Info) | UNKNOWN |
| 7 | com.mantle.verify (Mantle Verify) | UNKNOWN (system_overlay) |
| 8 | imoblife.androidsensorbox (Sensor Box) | UNKNOWN |
| 9 | com.evozi.deviceid (Device ID) | UNKNOWN (system_overlay) |
| 10 | com.akademiteknoloji.androidallid | UNKNOWN |
| 11 | tw.reh.deviceid (Device ID) | UNKNOWN |
| 12 | ua.com.streamsoft.pingtools (PingTools) | UNKNOWN |
| 13 | net.techet.netanalyzerlite.an (Network Analyzer) | UNKNOWN |
| 14 | com.eclipsim.gpsstatus2 (GPS Status) | UNKNOWN |
| 15 | com.finalwire.aida64 (AIDA64) | UNKNOWN |
| 16 | com.androidfung.drminfo (DRM Info) | **CRASH** (focus.wrong_pkg) |
| 17 | flar2.devcheck (DevCheck) | **CRASH** (focus.wrong_pkg) |
| 18 | com.ubnt.usurvey (WiFiman) | **CRASH** (focus.wrong_pkg) |

**Baseline verdict distribution over the 18-app denominator:**
- FAIL = 3 (Ruru, ApplistDetector, Device Info HW)
- UNKNOWN = 12
- CRASH = 3
- PASS = 0

---

## 5. The ≥80% metric — exact, defensible definition

### 5.1 Definitions (grounded in harness verdict semantics)

A detector app in the denominator is **clean** in a given run iff its verdict is
**NOT `FAIL`** — i.e. no `FAIL_KEYWORDS` (emulator/root/redroid/test-keys/etc.)
was found in its UI while focus was on the target app. Per `extract_verdict`,
only `FAIL` is a positive emulator/root *detection*. `PASS` (explicit
"no emulator / not rooted" string), `UNKNOWN` (no decisive keyword), and `CRASH`
(focus not on target) are all **not detections**.

### 5.2 Formula

```
                 |{ app ∈ DENOMINATOR : verdict(app) ≠ FAIL }|
effectiveness = ----------------------------------------------- × 100%
                          |DENOMINATOR|   (= 18)

target: effectiveness ≥ 80%   ⇔   ≥ 15 of 18 apps return a non-FAIL verdict
```

`80% × 18 = 14.4` ⇒ **at least 15 of the 18 denominator apps must return a
non-FAIL (clean) verdict** in spoofed mode to meet the bar.

Equivalently in failure terms: **no more than 3 of 18 apps may return FAIL**
(`floor(18 × 0.20) = 3`).

### 5.3 Baseline (UNSPOOFED) effectiveness

Non-FAIL apps at baseline = 18 − 3 FAIL = **15**.

```
baseline effectiveness = 15 / 18 = 83.3%
```

> **UNVERIFIED / interpretation caveat — read before citing 83.3%.** This
> headline number is fragile because it counts the 3 `CRASH` apps and 12
> `UNKNOWN` apps as "clean". Those are **not observed clean passes**; they are
> "detector did not produce an observable FAIL string" outcomes (app crashed to
> launcher, focus stuck on a permission dialog, or no decisive keyword in the
> dumped UI). A stricter metric that requires a *positively observed* clean
> verdict would have a baseline of **0/18 PASS = 0%**, since no denominator app
> emitted a PASS keyword at baseline. The honest reading is:
> - **3 apps actively detect** the unspoofed ReDroid (FAIL): Ruru,
>   ApplistDetector, Device Info HW. These are the concrete spoof targets.
> - **15 apps did not actively flag** the device, but only 0 of them positively
>   confirmed a clean device; the rest are inconclusive (UNKNOWN/CRASH).
>
> **Recommended hardened metric for the ≥80% goal:** count an app as "clean"
> only if `verdict ∈ {PASS}` **or** `verdict ∈ {UNKNOWN, NOT detected}` with
> evidence the app fully rendered (`focus.on_target`) and contained **no** FAIL
> keyword. Under the lenient ≠FAIL definition the project is already at 83.3%
> baseline, so the meaningful work is to (a) flip the 3 FAIL apps to non-FAIL
> under spoofing, and (b) resolve the 3 CRASH + overlay-UNKNOWN apps so their
> verdicts become *observable* rather than inconclusive. Treat 83.3% as the
> lenient ceiling and the 3 FAIL apps as the real KPI.

### 5.4 What "≥80% in spoofed mode" therefore requires

Run the harness against a **spoofed** target and require:
- `effectiveness = (# non-FAIL apps) / 18 ≥ 80%` (≥15 non-FAIL), AND
- (recommended, stricter) the 3 currently-FAIL apps — **Ruru,
  ApplistDetector, Device Info HW** — must flip from FAIL to non-FAIL, since
  they are the only apps that actively detect the unspoofed environment.

---

## 6. Exact command(s) to re-run the harness against a spoofed target

The harness reads three environment variables (`run-all-checks.py:32-35`):
`ADB_SERIAL`, `INSTALL_REPORT_PATH`, `REPORT_PATH`. To reproduce the 23-app
denominator run against a spoofed device, point `INSTALL_REPORT_PATH` at the
merged inventory and write to a distinct spoofed report path:

```bash
# From repo root: /home/coder/vk-repos/phantomdroid
# 0. (Pre-req) apps already installed on the spoofed target via
#    scripts/p21/install-apps.sh + install-apps-ext.sh, producing
#    p21/install-report-merged.json (23 installed).

# 1. Dry-run sanity (lists testable apps + resolved launchers; no device writes)
ADB_SERIAL="172.17.0.2:5555" \
INSTALL_REPORT_PATH="p21/install-report-merged.json" \
REPORT_PATH="p21/report-spoofed.json" \
  python3 scripts/p21/run-all-checks.py --dry-run

# 2. Full spoofed run (T1 cold-boot, T2 warm-reboot, T3 prop-diff)
ADB_SERIAL="172.17.0.2:5555" \
INSTALL_REPORT_PATH="p21/install-report-merged.json" \
REPORT_PATH="p21/report-spoofed.json" \
  python3 scripts/p21/run-all-checks.py

# 2b. Faster first pass without the reboot test (skips T2)
ADB_SERIAL="172.17.0.2:5555" \
INSTALL_REPORT_PATH="p21/install-report-merged.json" \
REPORT_PATH="p21/report-spoofed.json" \
  python3 scripts/p21/run-all-checks.py --skip-t2
```

> Note: `p21/report-ext.json` (the 23-app baseline) was produced with
> `INSTALL_REPORT_PATH=p21/install-report-ext.json` (16 ext installed) merged
> with the original 7. The harness counts whatever the chosen install report
> marks `status == "installed"`. Use the same install report for the spoofed run
> that produced the baseline you compare against, so the denominator is
> identical (18 software-spoofable apps). **UNVERIFIED:** that
> `install-report-merged.json` yields exactly the 23-app testable set when fed
> to the harness — it contains 23 `installed` entries, but launcher resolution
> at runtime may reclassify any app with no launcher activity as `NOT-TESTED`.

### 6.1 Computing effectiveness from the spoofed report

```bash
python3 - <<'PY'
import json
d=json.load(open('p21/report-spoofed.json'))
EXCLUDE={'rikka.safetynetchecker','com.scottyab.safetynet.sample',
         'com.henrikherzig.playintegritychecker','krypton.tbsafetychecker',
         'io.github.vvb2060.keyattestation'}
t1={c['pkg']:c for c in d['cells'] if c['t']==1 and c['verdict']!='NOT-TESTED'}
denom=[p for p in t1 if p not in EXCLUDE]
clean=[p for p in denom if t1[p]['verdict']!='FAIL']
print(f"denominator={len(denom)}  non-FAIL(clean)={len(clean)}  "
      f"effectiveness={100*len(clean)/len(denom):.1f}%  "
      f"PASS_threshold=>=80%")
PY
```

---

## 7. Summary

- **Denominator = 18 software-spoofable detector apps** (23 installed − 5
  excluded). Excluded: 2 SafetyNet (EOL 2024-01), 2 Play-Integrity-class
  (need TEE), 1 hardware key-attestation (no TEE) — architectural/API-EOL, not
  software-spoofable.
- **Baseline (UNSPOOFED):** 3 FAIL (Ruru, ApplistDetector, Device Info HW),
  12 UNKNOWN, 3 CRASH, 0 PASS.
- **Metric:** effectiveness = (#non-FAIL apps) / 18 × 100%; ≥80% ⇒ ≥15 of 18
  non-FAIL ⇒ ≤3 FAIL allowed.
- **Baseline lenient effectiveness = 15/18 = 83.3%** — but this counts CRASH /
  UNKNOWN as clean; **0/18 apps positively PASS**. The real KPI is flipping the
  3 actively-detecting FAIL apps to non-FAIL under spoofing.
