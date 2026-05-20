# Power-15 Phase-A Pre-Audit

**Date**: 2026-05-21
**Reviewer**: ralph-reviewer (team `power-13-real-world-validation`)
**Method**: Direct code-read; no speculation.

---

## 1. Snapshot Class Inventory

Location: `agents/detection/src/core/replay/`

| File | Form | Public API |
|---|---|---|
| `DeviceSnapshot.kt` | `data class DeviceSnapshot(...)` — 30 fields, all defaults except `label` + `capturedAt` | named-args constructor |
| `Pixel7CleanSnapshot.kt` | `object Pixel7CleanSnapshot { val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(...) }` | `.SNAPSHOT` |
| `SamsungS22CleanSnapshot.kt` | same pattern | `.SNAPSHOT` |
| `RedroidV12Snapshot.kt` | same pattern | `.SNAPSHOT` |
| `RedroidSpoofedSnapshot.kt` | same pattern | `.SNAPSHOT` |
| `SnapshotReplayContext.kt` | `class SnapshotReplayContext(snapshot: DeviceSnapshot) : ProbeContext` | bridge |

**Convention**: top-level `object <Name>Snapshot` with `val SNAPSHOT` constant. Package `com.detectorlab.core.replay`. Constructor calls use **named-args exclusively**.

---

## 2. DeviceSnapshot Field Map (P15-relevant subset)

| Field | Type | A1 Frida | A3 Multi-Vendor |
|---|---|---|---|
| `label` / `capturedAt` | `String` | required | required |
| `systemProperties` | `Map<String, String?>` | — | Yes (ro.product.* / ro.kernel.qemu / ro.product.manufacturer) |
| `existingFiles` | `Set<String>` | — | Yes (/system/lib/libBstHwHelper.so etc.) |
| `installedPackages` | `Set<String>` | — | Yes (com.bluestacks.*, com.bignox.*) |
| `procSelfMapsLibs` | `Set<String>` | **Yes** (libfrida-agent.so, libfrida-gadget.so, frida-gum) | — |
| `runtimeThreadNames` | `Set<String>` | **Yes** (gum-js-loop, gmain, linjector) | — |
| `openTcpPorts` | `Set<Int>` | **Yes** (27042, 27043) | — |
| `mountInfo` | `Map<String, String?>` | — | Yes (qemu-pipe, /dev/qemu_pipe) |
| `initSvcProps` | `Map<String, String>` | — | Yes (nox-vbox-sf, bluestacks-init.rc) |
| `dirEntries` | `Map<String, List<String>>` | — | Yes (/proc/self/fd for linjector symlink) |

**A1 conclusion**: ALL 3 fields needed for Frida positive-injection signals are already present. No `DeviceSnapshot` / `ProbeContext` extension required.

**A3 conclusion**: ALL fields needed for Nox/BlueStacks/Genymotion are already present.

---

## 3. ProbeContext Default Methods

All 26 default-impl methods exist; `SnapshotReplayContext` overrides 18 of them. The 3 critical for A1:

| Method | Default | Override exists? |
|---|---|---|
| `queryProcSelfMapsLibs()` | `emptySet()` | Yes (line 185) |
| `queryRuntimeThreadNames()` | `emptySet()` | Yes (line 191) |
| `queryOpenTcpPorts()` | `emptySet()` | Yes (line 197) |

**No abstract-method gap** — A1+A3 snapshots only need to populate the data-class fields; the bridge is complete.

---

## 4. Cross-Cutting #1 Status

Detector-replays (`detectorapps/*.kt`) encode decision-logic against raw `ProbeContext` accessors, NOT via `Probe`-instances. They do not emit evidence-keys → cross-cutting #1 (namespacing) is structurally inapplicable.

**Violations: 0**.

---

## 5. Probes with no DeviceSnapshot Field (out-of-scope flag)

These 5 ranks have no `DeviceSnapshot` field; not in P15-A scope but tracked for future iterations:

| Rank | Probe | Missing View |
|---|---|---|
| 33.5 | `env.time_spoofing` | `TimeView` |
| 40.5 | `env.screen_lock` | `KeyguardManagerView` |
| 43.5 | `env.wifi_security_type` | `WifiManagerView` |
| 50.5 | `runtime.multi_instance` | `UserHandleView` |
| 52.5 | `runtime.screen_recording` | `MediaProjectionManagerView` |

Adding requires coordinated change across 6 files (`DeviceSnapshot.kt` + `SnapshotReplayContext.kt` + 4 snapshot objects). **Carry-over for P19+ or owner-decision.**

---

## 6. GO/NO-GO for P15 Phase-A

| Track | Status | Blocker |
|---|---|---|
| A1 FridaInjectedRedroidSnapshot | GO (post-Researcher canonical sources) | waiting for `power-15-canonical-sources.md` from researcher |
| A2 Positive-path test | GO (after A1) | depends on A1 |
| A3 Nox + BlueStacks + Genymotion | GO (post-Researcher) | waiting for vendor-citation sources |
| A4 648-cell matrix | GO (after A1+A3) | depends on A1+A3 |

**Infrastructure**: all 30 DeviceSnapshot fields + all 26 ProbeContext defaults + all 18 SnapshotReplayContext overrides are in place. No core-module changes needed for Phase-A.

---

## 7. Coder Briefing Notes

When spawning ralph-coder for A1+A3:
1. Use existing `RedroidV12Snapshot.kt` as the structural template.
2. Use named-args constructor exclusively.
3. Cite each populated field's source-URL in a `// cite: <url>` comment INSIDE the constructor literal — anti-verarschen audit-trail.
4. `label` format: `<device-tag>-<arch>-<YYYY-MM-DD>`. For Frida: `redroid-12-amd64-2026-05-21-frida-injected`. For vendors: `nox-vbox86-2026-05-21`, `bluestacks-x86_64-2026-05-21`, `genymotion-vbox86p-2026-05-21`.
5. Do NOT modify `DeviceSnapshot.kt` or `ProbeContext.kt`. If a snapshot needs a field that doesn't exist → STOP and ask via SendMessage.
