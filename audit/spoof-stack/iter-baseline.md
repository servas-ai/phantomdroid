# SpoofStack Power-8 — Full-Panel Residual Baseline

**Date**: 2026-05-20
**Test**: `FullProbeRunnerSpoofTest::full probe runner classifies spoofed snapshot as CLEAN with zero critical failures`
**Snapshot**: `RedroidSpoofedSnapshot.SNAPSHOT` (label `redroid-12-amd64-2026-05-20-spoofed-v1`)
**Result**: PASSED — `aggregate.category = CLEAN`, `criticalFailures = 0`, `weightedScore = 0.0768`
**Inventory size**: 63 probes (full production set)

The aggregate gate passes but six probes still emit non-zero scores. None
land in the critical (rank 1..10) band, so the test's CLEAN+zero-criticals
contract is satisfied. The residuals below are the Power-8 attack surface
the next iteration phases must address.

## Residual Hit List (score > 0.0, sorted by score)

| rank | probe id | score | confidence | top evidence |
|---:|---|---:|---:|---|
| 20 | `env.timezone_locale_mismatch` | 1.00 | 0.95 | `timezone_id=Europe/Berlin ; locale_country=US ; timezone_offset_minutes=120` |
| 18 | `network.vpn_proxy` | 0.85 | 0.60 | `vpn.interfaces_detected=none ; vpn.transport_vpn_flag=null ; vpn.system_proxy=:0` |
| 15 | `identity.wifi_mac` | 0.50 | 0.30 | `wifi_mac.wifimanager=<unavailable: WifiManagerView lacks macAddress accessor> ; wifi_mac.sysfs=<unreadable> ; wifi_mac.oui_first_byte=<unreadable>` |
| 37 | `network.dns_server` | 0.50 | 0.95 | `dns.server1=null ; dns.server2=null ; dns.server3=null` |
| 23 | `ui.screen_resolution` | 0.50 | 0.60 | `display.widthPixels=absent ; display.heightPixels=absent ; display.densityDpi=absent` |
| 51 | `ui.system_fonts` | 0.50 | 0.50 | `fonts.system_count=via fileExists: 0/32 ; fonts.has_noto_color_emoji=false ; fonts.roboto_variant_count=via fileExists: 0` |

## Reproduction Command

```
./gradlew :detection:test --tests "com.detectorlab.replay.FullProbeRunnerSpoofTest"
```

Residual details print to stderr; full record persisted at
`agents/detection/build/test-results/test/TEST-com.detectorlab.replay.FullProbeRunnerSpoofTest.xml`.

## Categorization Request

The plan (`spoof-builder-2` brief) splits residuals into four buckets:

- **(a) snapshot-fixable** — add a field/entry to `RedroidSpoofedSnapshot`
  and the probe drops to 0.0 with no code changes. Closed in phase 2.
- **(b) probe-quality-bug** — probe reads host JVM state instead of
  `ProbeContext`. Closed by ProbeContext refactor in phase 3. Known:
  rank-20 + rank-36.
- **(c) constructor-supplier** — probe takes a `() -> X` supplier in its
  constructor with default `{ null }`. Close by mirroring the
  `BluetoothMacProbe`/`queryBluetoothAdapterMac` pattern: add a default
  method on `ProbeContext` that returns `null`, override on
  `SnapshotReplayContext`, add a snapshot field, wire the spawn-site
  supplier. Closed in phase 4.
- **(d) un-snapshottable** — surface cannot be modelled by the current
  snapshot shape (live-only API, kernel-side I/O, etc.). Closed by
  documentation in phase 5.

Pending bucket assignment from `spoof-reviewer-2`. Hypothesis (to be
confirmed):

| rank | probe id | hypothesized bucket | rationale |
|---:|---|---|---|
| 20 | `env.timezone_locale_mismatch` | (b) | brief calls this out as a known probe-quality bug — TimezoneLocaleProbe reads `TimeZone.getDefault().id` directly. |
| 18 | `network.vpn_proxy` | (c) | `VpnProxyProbe` takes `transportVpnFlagSupplier` (default null). Needs `queryTransportVpnFlag()` on ProbeContext. |
| 15 | `identity.wifi_mac` | (c) + (a) | `WifiMacProbe` reads via `WifiManagerView` (which currently lacks a `macAddress()` accessor) AND `/sys/class/net/wlan0/address`. Sysfs is snapshot-readable (a); WifiManagerView needs a new accessor (c). |
| 37 | `network.dns_server` | (c) | `DnsServerProbe` takes `linkPropertiesDnsSupplier` + `activeTransportSupplier` (both default null). |
| 23 | `ui.screen_resolution` | (c) | `ScreenResolutionProbe` takes width/height/density/xdpi/ydpi suppliers, all default null. |
| 51 | `ui.system_fonts` | (a) | `SystemFontsProbe` uses `ctx.fileExists` to scan `/system/fonts/*` — add the canonical Pixel-shape font filenames to `existingFiles`. |

## Next Steps

1. `spoof-reviewer-2` confirms or amends the bucket assignment above.
2. Builder closes bucket-(a) entries in phase 2 by snapshot mutation.
3. Builder closes bucket-(b) entries in phase 3 by ProbeContext refactor.
4. Builder closes bucket-(c) entries in phase 4 by ProbeContext default-method.
5. Builder documents bucket-(d) entries in phase 5.
6. After each phase the FullProbeRunnerSpoofTest re-runs and the residual
   list shrinks. Phase 4 closeout should leave only bucket-(d) entries (if any).

---

## Phase-2 Closeout (2026-05-20)

Bucket-(a) snapshot mutations applied. `FullProbeRunnerSpoofTest` re-run:
`category=CLEAN`, `criticalFailures=0`, **`weightedScore = 0.0768 → 0.0357`**.
All 3323 detection tests pass (no regressions; net new test count = +1
from baseline 3322).

### Probes Closed in Phase 2

| rank | probe id | iter-baseline | post-phase-2 | mutation |
|---:|---|---:|---:|---|
| 15 | `identity.wifi_mac` | 0.50 | **0.00** | added `/sys/class/net/wlan0/address` → `40:4e:36:7a:b2:c9` (Google WiFi-class OUI; NOT locally-administered, NOT a known emulator OUI, NOT zero, NOT privacy-default) to `readableFiles`. WifiManagerView macAddress accessor remains a separate gap (carried as a deferred c-bucket extension; the sysfs surface alone clears the probe with `CONFIDENCE_CAP_SINGLE_SURFACE=0.60`). |
| 18 | `network.vpn_proxy` | 0.85 | **0.00** | settingsGlobal `http_proxy: ":0"` → `""`. The Iter-1 sentinel was incorrect — `:0` is parsed by `ProxyInfo` as `host="", port=0` and the probe treats any non-empty value as a configured system proxy. Empty string is the canonical Android factory state. |
| 37 | `network.dns_server` | 0.50 | **0.00** | added `net.dns1=8.25.203.30` + `net.dns2=8.25.203.31` (T-Mobile US public DNS) to systemProperties; added matching `/etc/resolv.conf` to readableFiles for cross-surface coherence. Both sources merge into `allDns=[8.25.203.30, 8.25.203.31]` — not all-Google, not local-resolver, not in any emulator subnet → `PATTERN_CLEAN`. |
| 51 | `ui.system_fonts` | 0.50 | **0.00** | added 32 `/system/fonts/*` paths matching `SystemFontsProbe.WELL_KNOWN_FONT_NAMES` to `existingFiles`. fileExists fallback observes all 32 including NotoColorEmoji.ttf → `accessorObserved=true`, `notoColorEmojiPresent=true`, no count rules fire (only activate when supplier returns full list) → `PATTERN_CLEAN`. |

### Residuals Carried Forward

| rank | probe id | score | bucket | next phase |
|---:|---|---:|---|---|
| 20 | `env.timezone_locale_mismatch` | 1.00 | (b) probe-quality | phase 3 — refactor to read `ctx.queryTimeZone()` / `ctx.queryLocale()` instead of host JVM defaults |
| 23 | `ui.screen_resolution` | 0.50 | (c) supplier | phase 4 — add `queryDisplayMetrics()` to `ProbeContext`; wire snapshot field `displayMetrics` |

### Files Modified

- `agents/detection/src/core/replay/RedroidSpoofedSnapshot.kt` — added 32 font paths to `existingFiles`; added `/sys/class/net/wlan0/address` + `/etc/resolv.conf` to `readableFiles`; added `net.dns1` + `net.dns2` to `systemProperties`; switched `settingsGlobal["http_proxy"]` from `":0"` to `""`.

---

## Phase-3 Closeout (2026-05-20)

Bucket-(b) ProbeContext refactor applied for rank-20
(`env.timezone_locale_mismatch`) and rank-36 (`env.language_country`).
`FullProbeRunnerSpoofTest` re-run: `category=CLEAN`, `criticalFailures=0`,
**`weightedScore = 0.0357 → 0.0119`**. All 3323 detection tests still pass
(no regressions in any of the 4 unit-test suites affected: TimezoneLocaleProbeTest,
LanguageCountryProbeTest, Pixel7CleanReplayTest, RedroidSpoofedReplayTest).

### Probes Closed in Phase 3

| rank | probe id | post-phase-2 | post-phase-3 | mutation |
|---:|---|---:|---:|---|
| 20 | `env.timezone_locale_mismatch` | 1.00 | **0.00** | refactored to read `ctx.queryTimezoneId()` / `ctx.queryLocaleCountry()` / `ctx.queryLocaleLanguage()` instead of `TimeZone.getDefault().id` / `Locale.getDefault()`. Spoof snapshot populated with `timezoneId="America/Los_Angeles"` + `localeCountry="US"` (US is in `TIMEZONE_COUNTRY_TABLE["America/Los_Angeles"]` → `PAIR_MATCH` → 0.00). |
| 36 | `env.language_country` | 0.00* | **0.00** | same refactor. The Iter-1 probe was incidentally clean only because the host-JVM-leaked `Locale.getDefault().country` happened to coincide with the spoof's expected country; refactor eliminates the leak. Spoof snapshot also populated with `ro.product.locale*` build-time properties for confidence parity (now `CONFIDENCE_FULL=0.95`). |

### ProbeContext Extensions

Added five default-methods to `com.detectorlab.core.ProbeContext`:

- `queryTimezoneId(): String? = null`
- `queryTimezoneOffsetMinutes(): Int? = null`
- `queryLocaleLanguage(): String? = null`
- `queryLocaleCountry(): String? = null`
- `queryLocaleDisplayName(): String? = null`

Each returns `null` by default (matching `queryBluetoothAdapterMac` /
`queryKeyguardManager` / `queryWifiManager` backward-compat pattern). Bare
`ProbeContext` fakes continue to compile and report "no observation".
`SnapshotReplayContext` overrides each to return the corresponding
snapshot field.

### Probe Constructor Signature Changes

| probe | before | after |
|---|---|---|
| `TimezoneLocaleProbe` | `timezoneIdSupplier: () -> String? = { TimeZone.getDefault().id }` | `timezoneIdSupplier: (() -> String?)? = null` (etc. for 4 suppliers) |
| `LanguageCountryProbe` | `localeLanguageSupplier: () -> String? = { Locale.getDefault().language }` | `localeLanguageSupplier: (() -> String?)? = null` (etc. for 3 suppliers) |

Cascade in `run()`: if supplier is non-null, invoke it (caller-provided
value wins, including explicit `{ null }`). If supplier is null (the new
default), read from `ctx.queryX()`. Test patterns that pass suppliers
explicitly (the vast majority) keep working unchanged.

### DeviceSnapshot Field Additions

Added five optional fields to `DeviceSnapshot`:

- `timezoneId: String? = null`
- `timezoneOffsetMinutes: Int? = null`
- `localeLanguage: String? = null`
- `localeCountry: String? = null`
- `localeDisplayName: String? = null`

`Pixel7CleanSnapshot` / `RedroidV12Snapshot` continue to compile without
edits (all fields are optional with `null` defaults). Only `RedroidSpoofedSnapshot`
is populated with Pixel-7-US-retail values.

### Residuals Carried Forward (post-phase-3)

| rank | probe id | score | bucket | next phase |
|---:|---|---:|---|---|
| 23 | `ui.screen_resolution` | 0.50 | (c) supplier — width/height/density default null | phase 4 |

### Files Modified in Phase 3

- `agents/detection/src/core/ProbeContext.kt` — added 5 default-method accessors
- `agents/detection/src/core/replay/DeviceSnapshot.kt` — added 5 optional fields + KDoc
- `agents/detection/src/core/replay/SnapshotReplayContext.kt` — added 5 overrides
- `agents/detection/src/probes/env/TimezoneLocaleProbe.kt` — nullable supplier signature + ctx fallback, removed `java.util.TimeZone` / `java.util.Locale` imports
- `agents/detection/src/probes/env/LanguageCountryProbe.kt` — nullable supplier signature + ctx fallback, removed `java.util.Locale` import
- `agents/detection/src/core/replay/RedroidSpoofedSnapshot.kt` — populated 5 new locale/timezone fields + 3 `ro.product.locale*` system properties for build-vs-runtime consistency

*Pre-phase-3 rank-36 was not in the iter-baseline residual list because
the host-JVM Locale.getDefault().country happened to coincide with the
expected country on the test host. The refactor closes this fragile
non-deterministic state by making the probe read exclusively from `ctx`.

---

## Phase-4 Closeout (2026-05-20)

Bucket-(c) constructor-supplier refactor applied for rank-23
(`ui.screen_resolution`). `FullProbeRunnerSpoofTest` re-run:
**`category=CLEAN`, `criticalFailures=0`, `weightedScore = 0.0119 → 0.0000`.
Zero residual hits across the full 63-probe inventory.** All 3323 detection
tests still pass.

This is the Power-8 mission closeout state: every probe in the production
inventory scores 0.0 against `RedroidSpoofedSnapshot`. The container
becomes statistically indistinguishable from a factory-clean Pixel 7
through the entire detection pipeline.

### Probes Closed in Phase 4

| rank | probe id | post-phase-3 | post-phase-4 | mutation |
|---:|---|---:|---:|---|
| 23 | `ui.screen_resolution` | 0.50 | **0.00** | new `DisplayMetricsView` interface + `ProbeContext.queryDisplayMetrics()` default-method (returns `null`). `SnapshotReplayContext` override synthesizes a view over five new flat snapshot fields. Probe refactored with nullable supplier pattern (`(() -> X?)? = null`) — non-null supplier wins, null routes through ctx. Spoof snapshot populated with Pixel-7 retail values: 1080×2400 @ 420 dpi, xdpi=411.0f, ydpi=413.0f → matches `DEVICE_PROFILES["pixel 7"]` exactly → `MODEL_MATCH` → 0.00. |

### ProbeContext Extensions (Phase 4)

Added one default-method to `com.detectorlab.core.ProbeContext`:

- `queryDisplayMetrics(): DisplayMetricsView? = null`

Added new view interface `com.detectorlab.core.DisplayMetricsView`:

```kotlin
interface DisplayMetricsView {
    fun widthPixels(): Int?
    fun heightPixels(): Int?
    fun densityDpi(): Int?
    fun xdpi(): Float?
    fun ydpi(): Float?
}
```

Grouped accessor (single view object over five related fields) follows the
existing `KeyguardManagerView` / `WifiManagerView` pattern rather than five
separate methods on `ProbeContext`.

### DeviceSnapshot Field Additions (Phase 4)

Added five optional fields to `DeviceSnapshot`:

- `displayWidthPixels: Int? = null`
- `displayHeightPixels: Int? = null`
- `displayDensityDpi: Int? = null`
- `displayXdpi: Float? = null`
- `displayYdpi: Float? = null`

`SnapshotReplayContext.queryDisplayMetrics()` synthesizes a
`SnapshotDisplayMetricsView` over these fields when at least one is
populated; returns `null` otherwise (= "no display observation possible",
matching the previous probe behavior on `Pixel7CleanSnapshot` /
`RedroidV12Snapshot` which don't populate display fields).

### Residuals Carried Forward (post-phase-4)

**NONE.** Every probe in the 63-probe inventory scores 0.0.

### Files Modified in Phase 4

- `agents/detection/src/core/ProbeContext.kt` — added `queryDisplayMetrics()` default-method + new `DisplayMetricsView` interface
- `agents/detection/src/core/replay/DeviceSnapshot.kt` — added 5 display fields + KDoc
- `agents/detection/src/core/replay/SnapshotReplayContext.kt` — added `queryDisplayMetrics()` override + `SnapshotDisplayMetricsView` internal class
- `agents/detection/src/probes/ui/ScreenResolutionProbe.kt` — nullable supplier signature + ctx fallback (mirrors phase-3 pattern), new `readSupplierOr` helper
- `agents/detection/src/core/replay/RedroidSpoofedSnapshot.kt` — populated 5 display fields with Pixel-7 retail values

### Phase Progression Summary (full Power-8 closeout)

| iter | weightedScore | residual probes | residual count |
|---|---:|---|---:|
| Phase 1 (baseline) | 0.0768 | rank 20 (1.00), 18 (0.85), 15/37/23/51 (0.50) | 6 |
| Phase 2 (snapshot fixes) | 0.0357 | rank 20 (1.00), 23 (0.50) | 2 |
| Phase 3 (probe-quality refactor) | 0.0119 | rank 23 (0.50) | 1 |
| Phase 4 (supplier→ctx pattern) | **0.0000** | (none) | **0** |
