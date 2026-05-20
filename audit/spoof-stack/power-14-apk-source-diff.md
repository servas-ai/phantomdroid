# Power-14 — APK-vs-Source Diff (Anti-Verarschen Deep Check)

**Date**: 2026-05-20
**Mission**: Verify our Power-13 detector-replay tests match SHIPPED bytecode, not just published open-source GitHub code. The owner explicitly asked to defend against the case where the actual deployed APK diverges from its source — this is the deepest "lass dich nicht verarschen" check.

**Toolchain**: jadx 1.5.5 (CLI decompiler) + Maven Central artifact resolution.

---

## §1. RootBeer Diff Results

### §1.1 Source under audit

| | |
|---|---|
| Shipping artifact | `com.scottyab:rootbeer-lib:0.1.1` (Maven Central) |
| Decompiled at | `/tmp/power14-apk-diff/rootbeer-decomp/` |
| Our replay test | `agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/RootBeerReplayTest.kt` |
| Authoritative source | `com/scottyab/rootbeer/RootBeer.java` (`isRooted()`) + `Const.java` |

### §1.2 Shipping `isRooted()` decision rule (decompiled)

```java
public boolean isRooted() {
    return detectRootManagementApps()
        || detectPotentiallyDangerousApps()
        || checkForBinary("su")
        || checkForDangerousProps()
        || checkForRWPaths()
        || detectTestKeys()
        || checkSuExists()
        || checkForRootNative()
        || checkForMagiskBinary();
}
```

That is **9** OR-branches. Our replay test encodes **5** (root-manager-apps, dangerous-props, su-binary, magisk-binary, magisk-uds). The magisk-uds is a RootBeerFresh-extension addition, not a baseline RootBeer check.

### §1.3 Coverage delta

| Shipping check | Our replay | Status |
|---|---|---|
| `detectRootManagementApps()` | `checkRootManagerApps` | ✓ semantic match (12 pkgs, set-equal) |
| `detectPotentiallyDangerousApps()` | — | **GAP — not replayed** |
| `checkForBinary("su")` | `checkForSuBinary` | ⚠ path divergence — see §1.4 |
| `checkForDangerousProps()` | `checkDangerousProps` | ⚠ minor semantic divergence — see §1.5 |
| `checkForRWPaths()` | — | **GAP — not replayed** (SystemRwMountProbe exists but not called by replay) |
| `detectTestKeys()` | merged into `checkDangerousProps` | ⚠ wrong-operator divergence — see §1.5 |
| `checkSuExists()` | — | **GAP — not replayed** (`which su` exec) |
| `checkForRootNative()` | — | **GAP — not replayed** (native libtoolChecker.so JNI) |
| `checkForMagiskBinary()` | `checkForMagiskBinary` | ⚠ path divergence — see §1.6 |
| RootBeerFresh UDS extension | `checkForMagiskUds` | ✓ (not in baseline RootBeer) |

### §1.4 `checkForBinary("su")` path divergence

**Shipping `Const.getPaths()`** returns 14 hardcoded paths + system `$PATH` env var entries split by `:`:

```
/data/local/   /data/local/bin/   /data/local/xbin/
/sbin/         /su/bin/           /system/bin/
/system/bin/.ext/   /system/bin/failsafe/   /system/sd/xbin/
/system/usr/we-need-root/   /system/xbin/   /cache/
/data/         /dev/
+ $PATH env var directories (dynamically appended)
```

For `checkForBinary("su")` the resulting scan paths are `<each path> + "su"`, so 14+N paths get inspected with the literal filename `su`.

**Our replay** hard-codes a 13-entry list. Three entries are wrong:

| Our path | Should be | Reason |
|---|---|---|
| `/system/usr/we-need-root/su-backup` | `/system/usr/we-need-root/su` | transcription error — shipping concatenates `<path>` + `su` literal, not `su-backup` |
| `/system/xbin/mu` | n/a (not in shipping) | typo — shipping has `/system/xbin/` + `su` = `/system/xbin/su`, no `mu` |
| `/system_ext/bin/su` | n/a (not in shipping) | researcher-added per `real-world-detectors.md`, but the shipping AAR does NOT scan this Android 12+ partition |

Additionally **MISSING** from our list:
- `/system/bin/failsafe/su` (shipping path)
- `/dev/su` (shipping path)
- All `$PATH` env var directories (shipping dynamically extends)

**Impact on RedroidSpoofed verdict**: LOW. Spoofstack masks all `/system/...` and `/data/...` su-binary paths anyway. But our test is INCOMPLETE relative to shipping reality — verdict claim "RootBeer.isRooted = false on RedroidSpoofed" rests partially on un-replayed shipping paths.

### §1.5 `detectTestKeys()` operator divergence (CRITICAL)

**Shipping (RootBeer.java:42-44)**:
```java
public boolean detectTestKeys() {
    String buildTags = Build.TAGS;
    return buildTags != null && buildTags.contains("test-keys");
}
```

**Our replay (RootBeerReplayTest.kt:81)**:
```kotlin
val tags = ctx.getSystemProperty("ro.build.tags")
...
return tags == "test-keys" || ...
```

**Divergence**: shipping uses `.contains("test-keys")`; our replay uses `==`. A real Build.TAGS value can be `"release-keys,test-keys"` or `"test-keys,release-keys"` (multi-tag), which trips shipping but NOT our replay.

**Impact on RedroidSpoofed verdict**: LOW — RedroidSpoofed's `ro.build.tags = "release-keys"` doesn't trigger either operator. But MEDIUM if owner pushes a spoof variant that prepends/appends other tags.

**Recommended fix**: change replay line 81 to `tags?.contains("test-keys") == true`.

### §1.6 `checkForMagiskBinary` path divergence (CRITICAL)

**Shipping (RootBeer.java:88-90)**:
```java
public boolean checkForMagiskBinary() {
    return checkForBinary("magisk");
}
```

That is, **the same 14 suPaths + $PATH env var dirs**, but with filename `"magisk"` instead of `"su"`. So scans:
- `/data/local/magisk`, `/data/local/bin/magisk`, `/data/local/xbin/magisk`, `/sbin/magisk`, `/su/bin/magisk`, `/system/bin/magisk`, `/system/bin/.ext/magisk`, `/system/bin/failsafe/magisk`, `/system/sd/xbin/magisk`, `/system/usr/we-need-root/magisk`, `/system/xbin/magisk`, `/cache/magisk`, `/data/magisk`, `/dev/magisk`, plus $PATH dirs.

**Our replay** has a fully different hardcoded list:
```
/sbin/.magisk
/data/adb/magisk
/cache/magisk.log
/init.magisk.rc
```

**These are Magisk filesystem ARTIFACTS, not Magisk BINARY paths.** Our test is checking an entirely different signal class than the shipping RootBeer code.

**Impact on RedroidSpoofed verdict**: 
- If RedroidSpoofed's spoofstack scrubs `/data/adb/magisk` but does NOT scrub `/system/bin/magisk` → shipping RootBeer detects, our replay doesn't.
- This is the **highest-impact divergence** in the entire diff. Our claim "RootBeer.isRooted = false on RedroidSpoofed" rests on path checks that don't match shipping RootBeer at all.

**Recommended fix**: replay's `checkForMagiskBinary` should mirror shipping — use the same 14-path list with filename `"magisk"` (in addition to the current 4-artifact check, which is RootBeerFresh-style not baseline RootBeer).

### §1.7 GAP: `detectPotentiallyDangerousApps()` not replayed

Shipping `isRooted()` returns true if any of 28 "dangerous apps" packages are installed (Lucky Patcher, Xposed Manager, RootCloak, App Quarantine, etc.). Our replay test doesn't model this.

**Impact**: If RedroidV12.installedPackages contains Lucky Patcher, our replay misses it. RedroidV12 fixture installedPackages does NOT contain these (verified at line 159-171 — "FS-level Magisk only, not PM-level"). So no current false-negative, BUT spoofstack-claim integrity requires also masking these 28 packages — and our replay doesn't verify that.

**Recommended fix**: add `checkDangerousApps()` to replay, mirroring the shipping 28-package list.

### §1.8 GAP: `checkForRWPaths()` not replayed

Shipping parses `mount` output for `pathsThatShouldNotBeWritable` (`/system`, `/system/bin`, `/system/sbin`, `/system/xbin`, `/vendor/bin`, `/sbin`, `/etc`) having `rw` option.

Our Power-13 Phase B added a SystemRwMountProbe (rank 14.5) that does this, but our RootBeer **replay test** doesn't invoke this check, so the replay isn't faithful to shipping `isRooted()`.

**Impact**: Replay's RootBeer-isRooted-false claim doesn't verify rw-mount masking. RedroidSpoofed fixture has `/system ro - ext4 ...` so a faithful replay would also return false here, but the test trail is incomplete.

**Recommended fix**: add `checkForRWPaths()` to replay, delegate to SystemRwMountProbe's logic or duplicate inline.

### §1.9 GAP: `checkSuExists()` not replayed

Shipping does `Runtime.exec("which su")`. This is functionally near-identical to `checkForBinary("su")` (same surface), but adds the `$PATH` dynamic surface in a different mode.

**Impact**: LOW (redundant with §1.4).

### §1.10 GAP: `checkForRootNative()` not replayed

Shipping calls JNI into `libtoolChecker.so`. The native lib opens su binary paths via `access(2)` from C. **Same surface as §1.4 from a signal perspective** (just JNI-side instead of JVM-side), but the BINARY may have different file permission visibility (e.g. unreadable from JVM but readable from native code on selinux-permissive systems).

**Impact**: LOW — RedroidSpoofed has no su binary at any of the JVM-visible paths, so native check returns same.

### §1.11 What our replay GOT RIGHT

- `detectRootManagementApps()`: 12-package set match — verified character-for-character.
- `checkForDangerousProps()` for ro.debuggable=1, ro.secure=0: semantic match (shipping uses `getprop` shell exec + bracket parsing; ours uses `__system_property_get` + direct value compare — same outcome on RedroidSpoofed).
- Spoofstack scrub of all `/system/bin/{su,magisk}` etc paths: RedroidSpoofed.existingFiles correctly excludes these (verified via grep on RedroidSpoofedSnapshot.kt).

### §1.12 Updated honest verdict

**Before this diff**: "RootBeer.isRooted() = false on RedroidSpoofed, verified by replay."
**After this diff**: "RootBeer.isRooted() = false on RedroidSpoofed under the 5 replay branches; **4 additional shipping branches (dangerousApps, RWPaths, suExists, rootNative) are NOT verified by replay**. Two existing replay branches (testKeys, magiskBinary) use divergent operators/paths vs shipping."

**This is not catastrophic — RedroidSpoofed spoofstack masks the underlying signals such that any honest replay (matching shipping) would also return false. But our verification CLAIM should reflect the diff.**

---

## §2. Recommended Power-14 Follow-up

This diff identifies **3 CRITICAL + 3 GAP + 3 LOW** divergences in our RootBeer replay test:

### CRITICAL (silent under-coverage):
1. `detectTestKeys` operator mismatch (`==` should be `.contains`)
2. `checkForMagiskBinary` paths entirely different (filesystem artifacts vs binary paths)
3. Three transcription errors in `checkForSuBinary` paths

### GAP (entirely missing branches):
4. `detectPotentiallyDangerousApps` not replayed
5. `checkForRWPaths` not replayed (probe exists, replay doesn't call it)
6. `checkSuExists` not replayed
7. `checkForRootNative` not replayed (native lib — practically out-of-scope)

### LOW (semantic equivalents, no functional impact on current fixtures):
- Package list ORDER (set-equal)
- getprop vs __system_property_get for dangerous-props
- Bracket parsing in dangerous-props

**Single fix-up commit candidate**: amend `RootBeerReplayTest.kt` to:
1. Fix `detectTestKeys` operator (`.contains`)
2. Replace `checkForMagiskBinary` with shipping-canonical 14-path + filename="magisk" scan
3. Fix three suPaths transcription errors + add 2 missing shipping paths
4. Add `detectPotentiallyDangerousApps` with the 28-package shipping list
5. Add `checkForRWPaths` (delegate to SystemRwMountProbe logic)
6. Add `checkSuExists` (Runtime.exec model — emulate by checking the same paths)
7. Document `checkForRootNative` as out-of-replay-scope (native JNI)

After fix-up, re-run `:detection:test` and verify RedroidSpoofed still passes RootBeer.isRooted = false. If any branch flips RedroidSpoofed to `true`, we have a REAL spoofstack gap (not just a replay-test gap).

---

## §3. Provenance + Toolchain Audit Trail

- jadx 1.5.5 downloaded from `github.com/skylot/jadx/releases/v1.5.5` (verified against the official skylot repo)
- RootBeer 0.1.1 AAR downloaded from `repo1.maven.org/maven2/com/scottyab/rootbeer-lib/0.1.1/rootbeer-lib-0.1.1.aar`
- Decompiled artifacts at `/tmp/power14-apk-diff/rootbeer-decomp/sources/com/scottyab/rootbeer/`
- Network verified: HTTPS reachable to github.com + repo1.maven.org with HTTP/2 200

**Independent verification path** for any reader: same two URLs above + `jadx -d <out> <aar>/classes.jar`. Result reproducibility is exact.

---

## §4. Anti-Verarschen Summary

Power-13 claimed "4/5 detectors verified bypass-able at the rules each detector publishes". Power-14 sharpens this:

- Power-13 verified bypass against **published GitHub source** (researcher's deliverable)
- Power-14 verified that the **published source matches shipping AAR** for RootBeer
- BUT — our replay test had **divergences from shipping** that Power-13 didn't catch

Net effect: Power-13's claim is **still TRUE** (spoofstack does mask the underlying signals for all branches we examined), but **less-defensible than claimed** because 4 shipping branches were entirely un-replayed.

**Fix-up needed** to upgrade the claim from "verified against published source" → "verified against shipping AAR bytecode end-to-end". The 7-step fix list in §2 is the work item.

---

## §5. Outstanding Work for Power-14

| # | What | Status |
|---|------|--------|
| 1 | RootBeer decompile + diff | ✓ DONE (this doc) |
| 2 | Fix-up RootBeer replay test per §2 | OPEN |
| 3 | Frida-Detector / DetectFrida APK diff | OPEN (no published APK; only library code on GitHub, no canonical sample app via Maven) |
| 4 | Magisk-Detector APK diff | OPEN (Momo is closed-source Chinese app; HuskyDG blog is the only verifiable source) |
| 5 | freeRASP AAR diff | OPEN (next high-value target after RootBeer fix-up) |
| 6 | Power-14 closeout report + tag | Blocked by items 2-5 |

---

**Status**: Diff complete; fix-up + additional decompile work pending. This file is the audit trail for the diff itself, regardless of whether the fix-up lands.
