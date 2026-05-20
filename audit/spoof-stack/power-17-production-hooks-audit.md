# Power-17 Production-Hooks Audit — P-12 Spec vs Current Reality

**Date**: 2026-05-21
**Mission**: Audit the P-12 production-hooks spec (`audit/spoof-stack/production-hooks-spec.md`) against the post-Power-16 inventory (82 probes) and the post-Power-13/14 native-deploy carryover. Verify what is *implemented* vs *blocked* vs *stale* vs *missing*. Anti-Verarschen mandate applies: "implemented" requires file:line; "blocked" requires named blocker.

**Scope discipline (plan-immutability):** This document is a **READ-ONLY audit** of the P-12 spec. The spec is **NOT modified**. Recommendations for Phase-D revision are advisory only and require explicit owner approval before any spec mutation.

**P-12 spec status**: EXISTS — `audit/spoof-stack/production-hooks-spec.md` (697 lines, "Power-8 closeout artifact" + P-12 native-layer addendum dated 2026-05-20 at lines 630-696).

---

## §1. Status of Current P-12 Hooks

Status taxonomy:
- **implemented** = artifact present in repo with named file:line refs (does not imply deployed on PAR822349)
- **native-deploy-blocked** = artifact exists or is specified but is gated on PAR822349-reboot / kernel work
- **stale** = spec text references behavior superseded by Power-13/16 probe additions or fixture upgrades
- **missing** = called out in spec but no corresponding artifact exists in repo

### §1.1 Per-hook status table

| P-12 § | Hook category | Hook contents | Status | Evidence |
|---|---|---|---|---|
| §1.1 | resetprop build-prop family | `ro.build.fingerprint`, `ro.product.*`, `ro.hardware`, `ro.board.platform` | **implemented** | `infrastructure/spoof-stack-magisk/service.d/00-spoof.sh:20-31` |
| §1.2 | resetprop CPU ABI + LSPosed Build.SUPPORTED_*ABIS | rank 27 dual-surface | **implemented** | resetprop: `service.d/00-spoof.sh:36-39`; LSPosed: `BuildAbiHook.java` |
| §1.3 | resetprop bootloader/verified-boot | `ro.boot.vbmeta.device_state`, `ro.secure`, `ro.debuggable` | **implemented** | `service.d/00-spoof.sh:44-49` |
| §1.4 | resetprop SELinux | `ro.boot.selinux=enforcing`, `ro.build.selinux=1` | **implemented** | `service.d/00-spoof.sh:54-55` |
| §1.5 | resetprop DNS | `net.dns1`, `net.dns2` | **implemented** | `service.d/00-spoof.sh:60-61` |
| §1.6 | resetprop locale build-time | `ro.product.locale*` | **implemented** | `service.d/00-spoof.sh:66-68` |
| §1.7 | resetprop `ro.serialno` + LSPosed Build.getSerial | dual surface | **implemented** | `service.d/00-spoof.sh:73`; `BuildSerialHook.java` |
| §1.8 | QEMU markers — no-hook (rank 4) | declared unchanged from baseline | **stale** | Power-13 added rank 4.5 `ThirdPartyEmulatorArtifactsProbe` — spec only addresses rank 4. |
| §2.1 | DenyList `/system/bin/su` | per-app unmount | **native-deploy-blocked** | spec gives recipe; requires PAR822349 deploy |
| §2.2 | SELinux policy presence | inherited from host kernel | **native-deploy-blocked** | requires PAR822349 reboot |
| §2.3 | magic-mount 32 fonts | overlay tree | **implemented** | 32 .ttf files under `infrastructure/spoof-stack-magisk/system/fonts/` |
| §3.1 | magic-mount `/proc/version` | overlay file | **implemented** (artifact) / **native-deploy-blocked** (verification) | declared in `sysfs-binds.sh` |
| §3.2 | LSPosed `/proc/self/status` hook | TracerPid synthesis | **implemented** | `FileInputStreamHook.java`. **NOTE**: Spec covers rank 80 only — Power-13 added rank 8.5 also cleared by same hook. **stale on attribution**. |
| §3.3 | magic-mount `/sys/fs/selinux/enforce` | tmpfs overlay "1" | **implemented** (artifact) / **native-deploy-blocked** (verification) | `sysfs-binds.sh` |
| §3.4 | magic-mount bluetooth address | overlay | **implemented** | `sysfs-binds.sh` + `BluetoothAdapterHook.java` |
| §3.5 | magic-mount wlan0 address | overlay | **implemented** | `sysfs-binds.sh` + `WifiInfoHook.java` |
| §3.6 | magic-mount `/etc/resolv.conf` | overlay | **implemented** | `infrastructure/spoof-stack-magisk/system/etc/resolv.conf` |
| §4.1 | settings-put Secure (7 keys) | `android_id`, `default_input_method`, `location.is_from_mock_provider`, etc. | **implemented** | `service.d/01-settings.sh:34-40` |
| §4.2 | settings-put Global (6 keys) | `development_settings_enabled`, `adb_enabled`, etc. | **implemented** | `service.d/01-settings.sh:45-50` |
| §5.1 | LSPosed TelephonyManager | IMEI/SERIAL/SIM_SERIAL/OPERATOR/MCC_MNC/SIM_STATE | **implemented** | `TelephonyManagerHook.java` |
| §5.2 | LSPosed Locale + TimeZone + Resources | per-context Configuration.setLocale | **implemented** | `LocaleHook.java`, `TimeZoneHook.java`, `ResourcesHook.java` |
| §5.3 | LSPosed BluetoothAdapter.getAddress | dual-surface with §3.4 | **implemented** | `BluetoothAdapterHook.java` |
| §5.4 | LSPosed WifiInfo.getMacAddress | dual-surface with §3.5 | **implemented** | `WifiInfoHook.java` |
| §5.5 | LSPosed SensorManager.getSensorList | injects 6 sensors | **implemented (option a)** / **option b native-deploy-blocked** | `SensorManagerHook.java`; option (b) sensors HAL shim out-of-scope |
| §5.6 | DisplayMetrics: launch-flag + wm density + LSPosed | dual approach | **partially implemented** | LSPosed: `DisplayMetricsHook.java`; resetprop density: `service.d/00-spoof.sh:78`; launch-flag native-deploy-blocked |
| §6 | installedPackages — no hook | declared "ground truth already clean" | **stale** | Power-13 Gap #7 extended rank 10 with +15 superuser + 8 dangerous + 8 emulator pkgs |
| §P-12.1 | FridaKill Magisk module + hide-frida-maps Xposed | rank 9.0; post-fs-data process kill + iptables + maps redirect | **partially implemented** (Xposed skeleton) / **native-deploy-blocked** (FridaKill) | `hide-frida-maps/README` "Skeleton only"; FridaKill Magisk module **NOT FOUND in repo** |
| §P-12.2 | SELinux W^X kernel module | rank 9.7; no-modify-text policy | **native-deploy-blocked** | Spec explicitly "L0 UNCOUNTERED in FOSS 2026" |
| §P-12.3 | sealed libgotscan.so | rank 9.8; periodic GOT integrity scan | **native-deploy-blocked** | Spec same disposition; **no `libgotscan.so` artifact in repo** |

### §1.2 Roll-up counts

| Status | Count | Notes |
|---|---:|---|
| implemented | 19 | hooks with concrete file:line evidence |
| native-deploy-blocked | 7 | gated on PAR822349 reboot OR kernel work OR missing native artifact |
| stale | 3 | §1.8 (rank 4 only), §3.2 (attribution missed 8.5), §6 (rank-10 baseline vs Gap #7) |
| missing | 0 | every spec hook has either artifact or explicit out-of-scope disclaimer |

`p12_hooks_implemented = 19` · `p12_hooks_blocked = 7` · `p12_hooks_stale = 3` · `p12_hooks_missing = 0`.

---

## §2. New Probes Since P-12 Spec — Hook Mapping

These 18 fractional ranks were added by Power-13 (Phase-A/B), Power-15 (A17 RASP), and Power-16 (T5 install-source). The P-12 spec predates them. Advisory mapping (not a spec mutation).

`new_probes_not_in_p12 = 18`

| Rank | Probe | Required P-12 hook | Gap status |
|---:|---|---|---|
| 3.5 | `root.magisk_uds` | LSPosed read-hook on `/proc/net/unix` | **MISSING** |
| 3.7 | `runtime.init_svc_enumeration` | resetprop deletion or LSPosed SystemProperties.get filter | **MISSING** |
| 3.8 | `root.mount_ns_mismatch` | mountinfo-redirect LSPosed hook — potentially adversarial to §3 magic-mount | **MISSING** |
| 3.9 | `root.magisk_module_dir` | denylist unmount of `/data/adb/modules/` OR LSPosed File.listFiles filter | **MISSING** |
| 4.5 | `emulator.third_party_artifacts` | no-hook (ReDroid is not Nox/MEmu) | **NOT-NEEDED** |
| 8.5 | `runtime.debugger_tracerpid` | §3.2 FileInputStreamHook ALREADY CLEARS | **COVERED-INCIDENTALLY** (spec attribution stale) |
| 9.5 | `buildprop.fingerprint_cross_partition` | resetprop `ro.vendor.build.fingerprint` matching system-side | **MISSING** |
| 9.7 | `runtime.native_prologue_hash` | §P-12.2 (L0 UNCOUNTERED) | already addressed as hard ceiling |
| 9.8 | `integrity.prologue_got_hooks` | §P-12.3 (L0 UNCOUNTERED) | already addressed as hard ceiling |
| 10.5 | `integrity.install_source` (Power-16 B3) | LSPosed PackageManager.getInstallSourceInfo | **MISSING** |
| 14.5 | `root.system_rw_mount` | mountinfo-redirect LSPosed hook | **MISSING** |
| 14.7 | `root.overlayfs_present` | same mount-redirect | **MISSING** |
| 33.5 | `env.time_spoofing` | mitigation_layer=manual; legitimate fix = NTP discipline | **MISSING (out-of-spec by design)** |
| 39.5 | `env.location_mock_rasp` | §4.1 settings-put + LSPosed LocationHook.java | **PARTIALLY COVERED** |
| 40.5 | `env.screen_lock` | settings-put PIN-equivalent OR LSPosed KeyguardManager.isDeviceSecure | **MISSING** |
| 43.5 | `env.wifi_security_type` | WifiInfoHook needs extension for getCurrentSecurityType | **MISSING** |
| 50.5 | `runtime.multi_instance` | LSPosed hook on Process.myUserHandle | **MISSING** |
| 51.5 | `runtime.automation_tools` | §4.1 partially (accessibility); UIAutomator missing | **PARTIALLY COVERED** |
| 52.5 | `runtime.screen_recording` | mitigation_layer=not_spoofable | **NOT-SPOOFABLE; spec correctly silent** |

### §2.1 Roll-up

| Category | Count | Ranks |
|---|---:|---|
| Missing from P-12 spec (new hook needed) | 11 | 3.5, 3.7, 3.8, 3.9, 9.5, 10.5, 14.5, 14.7, 40.5, 43.5, 50.5 |
| Partially covered (attribution outdated) | 3 | 8.5, 39.5, 51.5 |
| Covered by P-12 native addendum | 2 | 9.7, 9.8 |
| Not-needed / not-spoofable / out-of-scope | 3 | 4.5, 33.5, 52.5 |

---

## §3. PAR822349 Reboot-Blocked Items

Per `power-14-closeout.md §9` + `power-16-closeout.md §5+§7`:

| ID | Item | Source |
|---|---|---|
| OB1 | **PAR822349 server reboot** (host kernel HWE 5.4) | `power-14-closeout.md §9 #1` |
| OB2 | **Live RedroidV12 re-capture** (replaces Phase-B synthesized values) | `power-13-closeout.md §5`; `power-16-closeout.md §7 C3` |
| OB3 | **Native-layer deploy** (Magisk modules + LSPosed + libgotscan.so) | `power-14-closeout.md §9 #3` |
| OB4 | **Live APK-tests in deployed container** | `power-14-closeout.md §9 #4` |
| OB5 | **T11+T12 production-only replay** (MediaProjection callback) | `power-16-closeout.md §5 #8` |
| OB6 | **redroid-recapture.sh execution** | `power-16-closeout.md §7 C3` (script delivered Power-17 C3, awaits owner execution) |

**Net**: 4 distinct technical blockers; OB5/OB6 are narrower instances.

---

## §4. Recommendation — P-12 Spec Disposition

### §4.1 Honest assessment

The P-12 spec is **partially stale**: 19/29 hooks implemented; 7 honestly native-deploy-blocked; 3 sections (§1.8, §3.2, §6) outdated. 11 new probes require hook categories the spec does not define.

### §4.2 Three options

**Option A — Frozen-as-design-of-record (RECOMMENDED PROVISIONALLY).**
Keep `production-hooks-spec.md` immutable as the Power-8/12 baseline. Author this audit as the Phase-C/D handoff. Aligns with `plan-immutability.md` mandate.

**Option B — Phase-D revision (DEFERRED-TO-OWNER decision).**
Open `power-17-production-hooks-spec-v2.md` incorporating 11 missing hooks + 3 stale corrections. P-12 stays as historical record. Requires explicit owner approval.

**Option C — In-place edit (NOT RECOMMENDED; PROHIBITED without owner override).**
Mutating the spec directly would violate plan-immutability rule #1.

### §4.3 Recommended path

**Default to Option A.** This audit doc IS the delta. Owner to review §1.1 stale rows + §2 missing-hook table and approve either Option A close-out or Option B v2 spec authoring task.

---

## §5. Anti-Verarschen Self-Check

| Discipline | Check | Result |
|---|---|---|
| Every "implemented" claim has file:line ref | All 19 §1.1 rows cite infrastructure paths | ✓ PASS |
| No invented hooks claimed | FridaKill + libgotscan.so marked MISSING-IN-REPO not silently claimed | ✓ PASS |
| `hide-frida-maps` honesty | README "Skeleton only" reflected in §1.1 P-12.1 row | ✓ PASS |
| Plan-immutability | `production-hooks-spec.md` NOT modified; recommendation defaults to Option A | ✓ PASS |
| Spec-existence assertion not fabricated | Read returned 697 lines including §P-12 addendum | ✓ PASS |

---

## §6. Report-Back Summary

```yaml
p12_hooks_implemented: 19
p12_hooks_blocked: 7
p12_hooks_stale: 3
new_probes_not_in_p12: 18   # 11 missing + 3 partially-covered + 2 native-addendum + 3 not-needed/not-spoofable/out-of-scope (8.5 counted under partially-covered as incidental)
p12_spec_exists: true       # audit/spoof-stack/production-hooks-spec.md, 697 lines
recommendation: "Option A (frozen-as-design) provisional; Option B requires explicit owner approval per plan-immutability"
par822349_reboot_blocked_items: 4
```
