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

## #2 Unverified package IDs in rank 10 marker list

**Observation**: 5 entries in the rank 10 (`runtime.installed_apps`) marker lists could not be verified as canonical published Android package IDs:
- Group B: `com.frida.frida`, `org.proxyman.NSPlist`, `com.adb.kit`
- Group C: `com.android.virtualspace`
- Group D: `mods.autoui`

**Why this matters**: The probe scores 0.85/0.7 if any of these is installed. If they're not real package IDs, they'll never fire → dead code, but no false positives.

**Proposed fix**: On the first real-device telemetry pass, ground-truth these against actual Play Store / sideload-installable APKs. Either replace with canonical IDs or remove from marker list.

**Acceptance**: All marker IDs in rank 10 trace to a real, publicly-installable Android package.

**Owner action**: prioritize relative to real-device validation budget.

---

## #3 ProbeContext lacks `querySettingGlobal`

**Observation**: 3 probes now (`AutomationToolsProbe`, `DeveloperOptionsProbe`, future Settings.Global-class probes) use `querySettingSecure` for keys that semantically live in `Settings.Global`. The KDoc says "assumes production wrapper handles namespace fallback" — but if it doesn't, every Global key these probes read returns null.

**Why this matters**: Silent false negatives on every Settings.Global probe.

**Proposed fix**: Add `fun querySettingGlobal(key: String): String?` to the `ProbeContext` interface. ~10 LOC interface change + production implementation update.

**Acceptance**:
- `ProbeContext.querySettingGlobal(key)` exists.
- Production wrapper reads from `Settings.Global` namespace specifically.
- The 3 affected probes migrate from `querySettingSecure` to `querySettingGlobal` where appropriate.

**Owner action**: approve core-contract change (this is one of the only no-touch zones during single-probe tasks).

---

## #4 inventory.yml rank 20 description divergence (FIXED 2026-05-20)

**Observation**: `shared/probes/inventory.yml:170` said rank 20 description was `"Timezone vs IP geolocation mismatch"`. The implemented probe substitutes locale-country for IP geolocation (per the no-live-network research boundary).

**Fix applied**: Updated line 170 to `"Timezone vs locale-country consistency (network-free proxy for IP geolocation)"`.

**Status**: closed.

---

## #5 Pixel 8 Pro density telemetry needed

**Observation**: The rank 23 `ScreenResolutionProbe.kt` device-profile table uses 480dpi for Pixel 8 Pro, but Google's spec is 489 PPI (Pixel 7 already proved Google doesn't always quantize to standard buckets — Pixel 7 reports 420 not 480). If real Pixel 8 Pro reports 489 (not 480), the profile false-positives at score=0.9 (model_mismatch).

**Why this matters**: Real-device false positive on the latest flagship.

**Proposed fix**: First real-device pass: capture `DisplayMetrics.densityDpi` from a Pixel 8 Pro and update the profile.

**Acceptance**: Pixel 8 Pro profile matches actual reported density on at least one real device.

**Owner action**: real-device telemetry pass when a Pixel 8 Pro is available.

---

## #6 SensorSample ragged-array contract gap

**Observation**: `SensorSample.values: Array<FloatArray>` allows ragged arrays (different axis counts per frame); `AccelerometerGyroProbe` assumes uniform axis count via `sample.values[0].size`. If the production wrapper ever produces ragged arrays, the outer try/catch catches it (`ProbeResult.failed`), but `SensorSample` doesn't document uniform-axis-count as a contract.

**Why this matters**: Silent failure path if a future wrapper produces ragged arrays.

**Proposed fix**: Add a KDoc invariant to `SensorSample`: "All `values[i]` must have the same length; the wrapper guarantees uniform axis count per sensor type."

**Acceptance**: `SensorSample` data class KDoc documents the invariant.

**Owner action**: ~3 LOC docstring change in `agents/detection/src/core/SensorManagerView.kt` (or wherever SensorSample lives).

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

## #7 Probe.rank Int vs inventory Double mismatch

**Observation**: `agents/detection/src/core/Probe.kt:?` defines `val rank: Int`, but `shared/probes/inventory.yml` contains 11 fractional ranks (8.5, 9.0, 9.7, 9.8, 33.5, 39.5, 40.5, 43.5, 50.5, 51.5, 52.5).

**Why this matters**: Probe code can't represent its inventory rank correctly. `ScreenLockProbe` inventory=`40.5`, code-rank had to be the A17 reserved slot `61` instead. The `41` slot (env.gps_coordinates) and `40` slot (env.accounts) are both taken. Half-ranks can't round.

**Proposed fix**: change `Probe.rank` from `Int` to `Double`. 54 probe files need their `override val rank = N` → `override val rank = N.0`. Tests already assert `assertEquals(N, probe.rank)` which Kotlin auto-promotes.

**Acceptance**: Probe.rank type matches inventory; ScreenLockProbe can move from `61` to `40.5`.

**Owner action**: approve core-contract change.

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
