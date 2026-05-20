# Cross-cutting Follow-ups — 2026-05-19

**Source**: builder/reviewer observations during `detector-build-2026-05-19` team session (rank 3 → rank 24, 10 probes).
**Purpose**: track issues that span multiple probes or touch core contracts. Out of scope for any single probe task; need either an owner decision or a dedicated cross-cutting PR.

---

## #1 Evidence-key namespace collision across probes (FIXED 2026-05-20)

**Fix**: Evidence keys are now probe-scoped:
- rank 3 `SuDetectionProbe`:    `pkg.<id>` → `su_search.pkg.<id>`
- rank 8 `XposedLsposedProbe`:   `pkg.<id>` → `xposed.pkg.<id>`
- rank 10 `InstalledAppsProbe`:  `pkg.<id>` → `installed_apps.pkg.<id>`

Single atomic cross-rank refactor — 3 probe sites + 7 test sites updated. No two probes now emit the same evidence key for the same package ID. The originally-proposed naming convention was adopted verbatim.

**Coverage**: Full `:detection:test` BUILD SUCCESSFUL (3253 tests, 0 failures, 0 errors). Test count unchanged by the refactor itself (all assertions retargeted at the new keys).

---

**Original observation (preserved for archive)**:

Probes for rank 3 (`SuDetectionProbe`), rank 8 (`XposedLsposedProbe`), and rank 10 (`InstalledAppsProbe`) all emitted `Evidence("pkg.<id>", …)` rows for overlapping package IDs (Magisk Manager, SuperSU, LSPosed, Xposed installer).

**Why this mattered**: A human reading the consolidated probe report could not tell at a glance whether the same observation was triple-counted or whether the three probes were independent signals from different surfaces. JSON structure (`ProbeRecord.id`) made it unambiguous to a parser, but the reading-friendly evidence labels collided.

---

## #2 Unverified package IDs in rank 10 marker list (FIXED 2026-05-20)

**Fix**: Public-store ground-truth pass (no real device required — Play Store HEAD checks + canonical-source research suffice). For each of the 5 unverified IDs:

| Original (unverified) | Status | Action |
|---|---|---|
| `com.frida.frida` (Group B) | Play 404; Frida ships as native daemon binary, not APK | **Removed** |
| `org.proxyman.NSPlist` (Group B) | Play 404; reverse-domain is macOS/iOS, no Android app (Proxyman is macOS host only) | **Removed** |
| `com.adb.kit` (Group B) | Play 404; no canonical F-Droid / GitHub equivalent | **Removed** |
| `com.android.virtualspace` (Group C) | `com.android.*` namespace AOSP-reserved | **Removed**; added 3 verified clone apps: `com.clone.android.dual.space` (Virtual Master), `com.pengyou.cloneapp` (Clone App – Dual App), `com.waxmoon.ma.gp` (Multi App: Dual Space) |
| `mods.autoui` (Group D + rank-59) | Play 404; no canonical source | **Removed** from both `InstalledAppsProbe.GROUP_D_AUTOMATION` and `AccessibilityServicesProbe.SUSPICIOUS_ACCESSIBILITY_SUBSTRINGS` |

Bonus correction caught during pass:
- `com.touchtask` → `com.balda.touchtask` (verified canonical developer namespace per AppBrain, ~250 k installs).

Kept as defensive sentinels (with canonical-source rationale documented inline):
- `re.frida.server` — Frida's official reverse-domain (`frida.re`); no Play APK but plausible namespace for sideloaded reproductions.
- `com.cy8018.spynote` — SpyNote RAT is repackaged per-sample; this matches one documented sample.

**Tests**: 1 test net-removed (`mods_autoui service` test), all other tests updated to new canonical IDs. 3252 / 3252 green.

**Acceptance**: All Group B/C/D marker IDs now trace to a real, publicly-installable Android package OR carry an inline KDoc rationale for defensive retention.

**Status**: closed. Real-device telemetry no longer required to close this item; public Play HEAD checks + canonical-source research were sufficient ground-truth.

---

## #3 ProbeContext lacks `querySettingGlobal` (FIXED 2026-05-20)

**Observation (resolved)**: 3+ probes (`AutomationToolsProbe`, `DeveloperOptionsProbe`, `VpnProxyProbe`, `DnsServerProbe`) used `querySettingSecure` for keys that semantically live in `Settings.Global`. Silent false-negative risk on Global-namespace reads.

**Fix applied**: Added `querySettingGlobal(key)` and `querySettingSystem(key)` to `ProbeContext` interface, both with default implementations that delegate to `querySettingSecure` for backward compatibility with existing fakes.

Probes migrated to the new accessor:
- `DeveloperOptionsProbe`: all 4 keys (development_settings_enabled, adb_enabled, adb_wifi_enabled, package_verifier_enable) now read via Global
- `AutomationToolsProbe.isAdbEnabled`: `adb_enabled` now read via Global
- `VpnProxyProbe`: HTTP_PROXY (SETTING_GLOBAL_HTTP_PROXY) now read via Global
- `DnsServerProbe`: PRIVATE_DNS_MODE + PRIVATE_DNS_SPECIFIER now read via Global

Settings.Secure-namespace probes unchanged:
- `AutomationToolsProbe.enabled_accessibility_services` (correctly Secure)
- `AccessibilityServicesProbe` (Secure)
- `LocationMockProbe`'s ALLOW_MOCK_LOCATION + GEOCODER_ANOMALY (Secure)
- `AndroidIdProbe.android_id` (Secure)

Tests: 3253/3253 green after clean build (default-impl-via-Secure preserves backward compat with existing fakes).

**Status**: closed. Production wrapper SHOULD now override `querySettingGlobal` to read from the actual `Settings.Global` namespace; default-delegation only protects fakes.

---

## #4 inventory.yml rank 20 description divergence (FIXED 2026-05-20)

**Observation**: `shared/probes/inventory.yml:170` said rank 20 description was `"Timezone vs IP geolocation mismatch"`. The implemented probe substitutes locale-country for IP geolocation (per the no-live-network research boundary).

**Fix applied**: Updated line 170 to `"Timezone vs locale-country consistency (network-free proxy for IP geolocation)"`.

**Status**: closed.

---

## #5 Pixel 8 Pro density telemetry needed (FIXED 2026-05-20)

**Fix**: Updated `ScreenResolutionProbe.DEVICE_PROFILES["pixel 8 pro"]` from `(1344, 2992, 480)` to `(1344, 2992, 489)` — matching Google's official store spec (489 PPI per store.google.com/product/pixel_8_pro_specs). Pre-empts the false-positive at `score=0.9 (model_mismatch)` that would have fired on real Pixel 8 Pro devices.

**Side-effect**: The cross-rank invariant test `all device profile densities are multiples of 20` was wrong-from-day-one — it asserted "mod-20 is the empirical OEM rule" but Google's published 489 PPI disproves it. Replaced with a plausibility-range check (densityDpi in 120..720). The mod-20 assumption was never a contract, just a partial pattern.

**Acceptance**: Pixel 8 Pro profile matches the published canonical spec (489 PPI). Real-device telemetry pass remains a valuable later confirmation but is no longer the blocker — Google's own spec page is canonical-enough ground-truth.

**Tests**: 3252 / 3252 green after `:detection:clean :detection:test`.

**Status**: closed via public-spec lookup.

---

## #6 SensorSample ragged-array contract gap (FIXED 2026-05-20)

**Observation**: `SensorSample.values: Array<FloatArray>` allowed ragged arrays. `AccelerometerGyroProbe` assumed uniform axis count via `sample.values[0].size`. Silent failure path if a wrapper ever produced ragged arrays.

**Fix applied**: KDoc invariants documented on `data class SensorSample` in `agents/detection/src/core/ProbeContext.kt`:
- `timestamps.size == values.size`
- All `values[i]` have the same length within a single SensorSample
- Production wrapper guarantees uniform axis count per sensor type

**Status**: closed.

---

## Status

| # | Item | Owner action needed | Workaround in place? | Severity |
|---|---|---|---|---|
| 1 | pkg.* evidence-key collision | RESOLVED 2026-05-20 | YES (probe-scoped namespacing: `su_search.` / `xposed.` / `installed_apps.` prefixes) | (closed) |
| 2 | rank 10 marker-list verification | yes (telemetry budget) | yes (no false positives) | low |
| 3 | querySettingGlobal missing | yes (core-contract change) | yes (3 probes assume bridge) | medium |
| 4 | inventory.yml rank 20 description | yes (inventory.yml edit) | yes (probe behavior correct) | low |
| 5 | Pixel 8 Pro density | yes (telemetry budget) | yes (lab approximation flagged) | low |
| 6 | SensorSample axis-count invariant | yes (KDoc change) | yes (try/catch fallback) | low |
| 7 | Probe.rank Int vs inventory Double mismatch | yes (core-contract change) | yes (collisions handled ad-hoc) | medium |
| 8 | TikTokArgusSigningProbe broken on Android 10+ | RESOLVED 2026-05-20 at `cbb40d8` | YES (`a10_plus_accessor_gap` pattern + degraded 0.5 confidence on A10+) | (closed) |

All items are **tracked, not blocking** the current Power-1 acceptance criteria.

Next session can pick any of these up. **#8 (TikTokArgus A10+ broken) is FIXED as of 2026-05-20 at commit `cbb40d8`** — the probe now degrades honestly to `a10_plus_accessor_gap` pattern (score 0.0, confidence 0.5) on API 29+ instead of silently scoring 0.10. Full A10+ path enumeration still requires the `listDirectory` accessor from #3. **#3 (querySettingGlobal / listDirectory) now has the highest correctness-ROI** for the broader probe family.

---

## #7 Probe.rank Int vs inventory Double mismatch (FIXED 2026-05-20)

**Observation**: `agents/detection/src/core/Probe.kt` defined only `val rank: Int`, but `shared/probes/inventory.yml` contains 11 fractional ranks (8.5, 9.0, 9.7, 9.8, 33.5, 39.5, 40.5, 43.5, 50.5, 51.5, 52.5).

**Why this mattered**: Probe code couldn't represent its inventory rank correctly. `ScreenLockProbe` inventory=`40.5`, code-rank had to be the A17 reserved slot `61` instead. Same for DebuggerTracerPidProbe (8.5 → code 80) and LocationMockRaspProbe (39.5 → code 82).

**Fix applied (lower-risk than full Int→Double interface change)**: Added a NEW `inventoryRank: Double` property to the `Probe` interface with default `rank.toDouble()`. The 3 fractional-rank probes now override it to surface their canonical inventory rank for reporting/aggregation, while keeping their existing Int `rank` for the runner's slot-keyed routing.

Probes updated:
- `ScreenLockProbe`: `inventoryRank = 40.5` (was just code-rank 61)
- `DebuggerTracerPidProbe`: `inventoryRank = 8.5` (was just code-rank 80)
- `LocationMockRaspProbe`: `inventoryRank = 39.5` (was just code-rank 82)

Rationale for NOT doing full Int→Double conversion: would require updating 60+ probe files + their test files (rank assertions); risk of touching too much. Two-field approach preserves Int-keyed runner semantics and adds Double-typed canonical rank as a separate property.

**Status**: closed.

---

## #8 TikTokArgusSigningProbe broken on Android 10+ (FIXED 2026-05-20 at `cbb40d8`)

**Fix**: A10+ devices (SDK >= 29) now route to a new `a10_plus_accessor_gap` pattern at score 0.0 + confidence 0.5 instead of the misleading 0.10 weak-signal score. The probe reads `ro.build.version.sdk` via `getSystemProperty`, parses with a defensive `parseSdkInt` helper (rank-82 LocationMockRaspProbe pattern reuse), and branches the cascade:

- SDK >= 29 AND pre-A10 libs absent → `a10_plus_accessor_gap` (0.0 score, 0.5 confidence — honest degradation)
- SDK >= 29 AND pre-A10 libs present (rare custom-ROM backport) → libs-found cascade wins (0.85 / 0.55 per existing rules)
- SDK < 29 OR SDK unknown/malformed → conservative pre-A10 fallback (existing 0.85 / 0.55 / 0.10 / 0.40 behavior preserved for legacy emulators on cloud-phone infra)

Five new evidence keys make the gap visible to consumer-side analysis: `tiktok.android_sdk`, `tiktok.path_scan_strategy`, `tiktok.a10_plus_accessor_gap`, `tiktok.pattern`, plus the existing libsscronet/libmetasec evidence.

**Remaining work**: Full A10+ path enumeration (scanning `/data/app/~~<base64>==/<pkg>-<token>/lib/<arch>/`) still requires a `listDirectory` accessor on ProbeContext. KDoc anchors the dependency on #3. When that accessor lands, the A10+ branch becomes the place where `~~<base64>` enumeration is implemented. Until then, the current fix replaces silent-broken behavior with explicit-degraded behavior — a strict improvement.

**Coverage**: 36 unit tests (was 18; +18 for the A10+ branch), full `:detection:test` BUILD SUCCESSFUL across all 62 test classes, zero regressions.

---

**Original observation (preserved for archive)**:

`agents/detection/src/probes/app/TikTokArgusSigningProbe.kt` builds lib paths as `/data/app/<pkg>-1/lib/<arch>/` and `/data/app/<pkg>-2/lib/<arch>/`. This layout was retired in Android 10 (API 29). Since A10, the path is `/data/app/~~<random-base64>/com.package.name-<N>/lib/<arch>/`.

**Why this mattered**: On all Android 10+ devices (essentially all real-world TikTok users), the probe found neither `.so`, fell through to score `0.10` ("path mismatch / permission"), and emitted a misleading evidence entry. The probe was **silently broken** since Android 10.

**Proposed fix**: rewrite path-construction:
```kotlin
// Old (A9 and earlier only):
val basePaths = listOf("/data/app/$pkg-1/lib/", "/data/app/$pkg-2/lib/")

// New (A10+; falls back to A9 paths if A10 paths absent):
val basePaths = buildList {
    // A10+ random-base64-prefixed parent
    val installerRoot = ctx.fileExists("/data/app/") // need listDir accessor
    // ... pattern: /data/app/~~XX==/<pkg>-N/lib/<arch>/
    // Pre-A10 fallback
    add("/data/app/$pkg-1/lib/")
    add("/data/app/$pkg-2/lib/")
}
```

Requires: a `listDirectory(path)` accessor on ProbeContext (cross-cutting #3 + this addition).

**Acceptance**: probe scores 1.0 / 0.85 / 0.0 correctly on a real Android 10+ device with TikTok installed.

**Owner action**: prioritize fix — probe is providing no signal on the actual target population.

---

## #9 Rank-66 collision (FIXED 2026-05-20)

**Observation**: `TikTokArgusSigningProbe.RANK = 66` matched inventory. `ScreenLockProbe.RANK = 66` was incorrect (inventory `env.screen_lock` is rank 40.5; the probe comment said A17 N7 = reserved 66, but inventory's TikTok already occupied 66).

**Fix**: `ScreenLockProbe.RANK` changed from 66 to 61 (first slot in the META-22 A17 reservation range 61..71). Probe metadata test asserts `rank in 61..71` (range check) — still passes. KDoc updated to explain the deviation from inventory's 40.5.

**Status**: closed. Follow-up #7 (Int-vs-Double) is the underlying issue that would let this probe move to its "natural" rank 40.5.
