# ADR 0002: ProbeContext Contract Evolution via Default Methods for Backward Compatibility

**Status**: Accepted  
**Date**: 2026-05-20  
**Deciders**: detection-lab team  
**Technical area**: detection module / core contract  
**Closes**: cross-cutting #3 (`querySettingGlobal` missing), cross-cutting #6 (SensorSample invariant)

---

## Context

`ProbeContext` is the central interface that every probe receives as its sole
dependency on the Android runtime. At initial design the interface declared
exactly 7 methods:

```kotlin
fun getSystemProperty(key: String): String?
fun fileExists(path: String): Boolean
fun readFile(path: String, maxBytes: Int = 8192): String?
fun querySettingSecure(key: String): String?
fun queryTelephonyManager(field: TelephonyField): String?
fun queryPackageManager(): PackageManagerView
fun querySensorManager(): SensorManagerView
```

As the probe inventory grew from its initial scope to 62 probes, new platform
surfaces needed representation:

| Surface | Required by | Cross-cutting ref |
|---|---|---|
| `Settings.Global` namespace | `DeveloperOptionsProbe`, `AutomationToolsProbe`, `VpnProxyProbe`, `DnsServerProbe` | #3 |
| `Settings.System` namespace | Future system-settings probes | #3 |
| `KeyguardManager` | `ScreenLockProbe` | implied by probe inventory expansion |
| `WifiManager` | Wi-Fi security probes | implied by probe inventory expansion |
| `MediaProjectionManager` | Screen-recording detection probes | implied by probe inventory expansion |
| `UserHandle.myUserId()` | Multi-instance detection probes | implied by probe inventory expansion |
| `SystemClock` / NTP time sources | `TimeSpoofingProbe` | implied by probe inventory expansion |

### The backward-compatibility problem

The detection test suite accumulated **200+ test fixtures** (unit-test fake
implementations of `ProbeContext`) before the expansion methods were needed.
Adding a new abstract method to `ProbeContext` would force every fake to
implement it or fail at runtime with `AbstractMethodError`.

Cross-cutting #3 (`audit/cross-cutting-followups-2026-05-19.md`, line 56)
documents the concrete risk: `querySettingGlobal` was absent from `ProbeContext`
and 4 probes were silently reading `Settings.Global` keys via
`querySettingSecure` — a wrong-namespace read that would return `null` on
devices where the production wrapper correctly separates the two namespaces.
When `querySettingGlobal` was added as an **abstract** method, every pre-existing
fake would have required an `override` addition before the build could succeed.

### Alternatives considered

| Approach | Reason rejected |
|---|---|
| Abstract method (no default) | Would require updating 200+ test fixtures per new method; high mechanical-change risk, reviewer fatigue, merge conflict surface |
| Sealed class | Cannot be implemented outside the module; production wrapper lives in the app module. Sealed prevents the extension point entirely |
| Abstract class | Breaks existing fakes that use `object :` expression syntax; Kotlin `object` declarations cannot extend abstract classes with constructor parameters |
| Separate `ProbeContext2` interface | Would fracture the type hierarchy; `ProbeRunner` and all probe signatures would need a union type or a breaking rename |
| Code-generation / build-time scaffolding | Disproportionate complexity for what is fundamentally an "unknown default" answer |

---

## Decision

**New methods added to `ProbeContext` receive Kotlin default implementations
that return safe "unknown" values or delegate to an existing accessor.**

The rule has two variants:

### Variant A — Capability-view methods: return an `Unknown*` singleton

For platform capabilities that fakes simply never needed to implement
(Keyguard, Wifi, MediaProjection, UserHandle, Time), the default returns a
pre-defined conservative object that answers every query with `null` or the
most defensive sentinel:

```kotlin
// ProbeContext.kt lines 44–76
fun queryKeyguardManager(): KeyguardManagerView = UnknownKeyguardManagerView
fun queryWifiManager(): WifiManagerView = UnknownWifiManagerView
fun queryMediaProjectionManager(): MediaProjectionManagerView = UnknownMediaProjectionManagerView
fun queryUserHandle(): UserHandleView = UnknownUserHandleView
fun queryTimeView(): TimeView = UnknownTimeView
```

Each `Unknown*` object is defined in the same file and returns `null` / `0` /
`false` / `UNAVAILABLE` consistently (e.g. `UnknownWifiManagerView` lines
227–232, `UnknownKeyguardManagerView` lines 80–84). Any probe receiving the
unknown view must treat the result as "insufficient data" and either skip
scoring or return `score = 0.0, failed = false`.

Production wrappers **must** override every `queryXxx()` method with a real
platform binding. The default is a compile-time safety net for fakes, not a
runtime fallback for production.

### Variant B — Settings namespace methods: delegate to `querySettingSecure`

For `querySettingGlobal` and `querySettingSystem`, the correct long-term answer
is a distinct `Settings.Global` / `Settings.System` read. However, fakes that
predate the split only implement `querySettingSecure`. The default bridges the
gap by delegating:

```kotlin
// ProbeContext.kt lines 30–37
fun querySettingGlobal(key: String): String? = querySettingSecure(key)
fun querySettingSystem(key: String): String? = querySettingSecure(key)
```

The KDoc on each method explicitly documents the forward obligation
(`lines 19–29`):

> **Production implementations MUST override** to read the actual
> `Settings.Global` / `Settings.System` namespace.

This means:
- **Existing fakes**: continue to compile without any change. Their
  `querySettingGlobal` call goes to `querySettingSecure`, which may return
  `null` for Global keys not in the fake's Secure map — acceptable for unit
  tests that are not testing Global-namespace behavior.
- **Production wrapper**: must override both methods. If it does not, the
  default delegation means Global keys are silently read from the Secure
  namespace, which is a semantic error on a real device.
- **`SnapshotReplayContext`**: overrides both correctly
  (`SnapshotReplayContext.kt` lines 56–58), reading from the correct
  `settingsGlobal` / `settingsSystem` maps in the snapshot.

### Probes migrated as part of cross-cutting #3

The following probes were updated to use the new accessors
(`audit/cross-cutting-followups-2026-05-19.md` lines 63–69):

| Probe | Keys moved to `querySettingGlobal` |
|---|---|
| `DeveloperOptionsProbe` | `development_settings_enabled`, `adb_enabled`, `adb_wifi_enabled`, `package_verifier_enable` |
| `AutomationToolsProbe` | `adb_enabled` |
| `VpnProxyProbe` | `SETTING_GLOBAL_HTTP_PROXY` |
| `DnsServerProbe` | `PRIVATE_DNS_MODE`, `PRIVATE_DNS_SPECIFIER` |

Keys correctly remaining in `querySettingSecure`:
- `AutomationToolsProbe.enabled_accessibility_services`
- `AccessibilityServicesProbe` keys
- `LocationMockProbe`: `ALLOW_MOCK_LOCATION`, `GEOCODER_ANOMALY`
- `AndroidIdProbe`: `android_id`

---

## Consequences

### Positive

- **Zero mechanical churn on fakes**: Adding a new surface to `ProbeContext`
  does not require touching any existing test file. The 200+ fake implementations
  continue to compile unchanged.
- **Explicit production obligation**: The `MUST override` KDoc makes the
  production contract unambiguous. A future code-review checklist can verify
  that the production wrapper overrides every method that has a default.
- **Correct namespace reads in production**: The 4 probes that were silently
  reading `Settings.Global` keys via `Secure` now call `querySettingGlobal`,
  which the production wrapper will route correctly. The silent false-negative
  on Global-namespace reads is closed.
- **`SnapshotReplayContext` inherits cleanly**: Because `SnapshotReplayContext`
  overrides the Settings methods explicitly and relies on the `Unknown*` defaults
  for the capability views (see `SnapshotReplayContext.kt` lines 39–43), the
  replay layer does not need any further update when new default methods are
  added — it gets the conservative "unknown device" behavior for free.
- **Test suite remains green**: 3253/3253 tests pass after the change
  (`audit/cross-cutting-followups-2026-05-19.md` line 74).

### Negative / Risks

- **Default delegation is semantically wrong in production**: If a production
  wrapper forgets to override `querySettingGlobal`, keys that live in
  `Settings.Global` will be looked up in `Settings.Secure` and return `null`.
  This is a silent false-negative, not a crash. The `MUST override` KDoc
  obligation is not enforced at compile time. A future lint rule or interface
  audit could catch this.
- **`Unknown*` views silently suppress scoring**: Probes that receive an
  `UnknownKeyguardManagerView` (or any other `Unknown*`) will see `null` /
  `UNAVAILABLE` from every accessor. If the probe does not have an explicit
  `if (result == null) return ProbeResult.skipped()` guard, it may silently
  score `0.0` rather than recording that data was unavailable. This is a probe
  authoring discipline issue, not a contract issue, but the default-method
  decision makes it easier to hit by accident.
- **Two fields for rank (parallel `inventoryRank` pattern)**: The same
  backward-compatibility philosophy was applied in cross-cutting #7
  (`Probe.inventoryRank: Double` default delegates to `rank.toDouble()`). This
  pattern of "add a default that delegates" is now established in two places
  in the interface hierarchy. New contributors may cargo-cult it in places
  where a breaking change would be more correct.
- **`querySettingSystem` has no test coverage against a real Settings.System
  read**: The default delegation means fakes never exercise the System namespace
  path, and no probe currently calls `querySettingSystem` for a key that would
  produce different results from `querySettingSecure`. Coverage gap exists until
  a System-namespace probe is added.
