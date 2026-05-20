# SpoofStack Magisk Module (ReDroid 12)

Pixel-7 spoof overlay derived from the executable spec in
[`audit/spoof-stack/production-hooks-spec.md`](../../audit/spoof-stack/production-hooks-spec.md).
Bundles every property / settings / sysfs / file mutation needed to drive
the live PAR822349 ReDroid 12 host to the same observable surface that
the `RedroidSpoofedSnapshot` JVM replay produces.

This module covers **86 of the 104 hooks** in the spec. The remaining 18
hooks are Java-API-level intercepts that require a companion **LSPosed
module** (§5 of the spec). LSPosed integration is out of scope for this
Magisk-only package.

## What's in the box

| Category | Count | Files |
|---|---:|---|
| `resetprop` | 30 system properties | `post-fs-data.sh`, `service.d/00-spoof.sh` |
| `settings put` | 13 keys (7 secure + 6 global) | `service.d/01-settings.sh` |
| magic-mount | 33 files (32 fonts + resolv.conf) | `system/fonts/*`, `system/etc/resolv.conf` |
| sysfs bind-mounts | 4 paths | `sysfs-binds.sh` (/proc/version, /sys/fs/selinux/enforce, hci0/address, wlan0/address) |
| `wm` framework | 2 (size + density) | `service.d/01-settings.sh` |
| LSPosed (NOT here) | ~20 Java hooks | See companion LSPosed module |
| Launch-flag (NOT here) | 1 (`--display=1080x2400`) | See "Install" below |
| DenyList (NOT here) | 1 (per-app `/system/bin/su`) | See "Install" below |

## Directory layout

```
infrastructure/spoof-stack-magisk/
├── module.prop                 Magisk module metadata
├── customize.sh                install-time hook (permissions, deps check)
├── post-fs-data.sh             early-boot resetprop + sysfs-binds invocation
├── sysfs-binds.sh              bind-mounts /proc/version, /sys/.../address
├── uninstall.sh                cleanup hook
├── service.d/
│   ├── 00-spoof.sh             late-boot resetprop reassertion
│   └── 01-settings.sh          settings put + wm size/density (waits for SettingsProvider)
└── system/                     magic-mount overlay tree
    ├── etc/resolv.conf         T-Mobile US DNS pair
    └── fonts/                  32 Pixel-7 font placeholders
        ├── Roboto-Regular.ttf
        ├── ... (30 more)
        └── NotoColorEmoji.ttf
```

## Install on PAR822349

The target host is the ReDroid 12 container on PAR822349. Magisk must
already be installed inside the container (Magisk-in-ReDroid uses the
`Magisk-zygisk` build).

### Step 1: build the module zip

```sh
cd infrastructure/spoof-stack-magisk
zip -r /tmp/spoof-stack-redroid-12.zip \
    module.prop customize.sh post-fs-data.sh sysfs-binds.sh uninstall.sh \
    service.d/ system/
```

### Step 2: copy into the container

```sh
docker cp /tmp/spoof-stack-redroid-12.zip redroid12:/data/local/tmp/
```

### Step 3: install via Magisk CLI

```sh
docker exec redroid12 sh -c '
    magisk --install-module /data/local/tmp/spoof-stack-redroid-12.zip
'
```

### Step 4: relaunch container with the display flag

The §5.6 `displayWidthPixels` / `displayHeightPixels` snapshot fields are
resolved by ReDroid at container-start; they cannot be patched at
runtime. Stop the container and restart with the launch flag:

```sh
docker stop redroid12
docker run -d --name redroid12 \
    --display=1080x2400 \
    --memory=4g --cpus=4 \
    redroid/redroid:12.0.0_64only-latest
```

### Step 5: add target app to Magisk DenyList (§2.1)

Replaces the `/system/bin/su` removal hook. Per target app — repeat for
each app under evaluation:

```sh
docker exec redroid12 sh -c '
    magisk --denylist add com.target.app
'
```

### Step 6: install the companion LSPosed module

Out of scope for this Magisk package. See
`audit/spoof-stack/production-hooks-spec.md` §5 for the Java hook
definitions.

## Verification

The `verify.sh` one-liner below matches the verification table in §
Verification of the spec. Run it AFTER reboot:

```sh
docker exec redroid12 sh -c '
    echo "== resetprop =="
    getprop ro.build.fingerprint
    getprop ro.product.model
    getprop ro.hardware
    getprop ro.boot.verifiedbootstate
    getprop ro.debuggable
    echo "== files =="
    ls /system/bin/su 2>&1   # expect "No such file" when run inside denylisted app
    cat /proc/version
    cat /sys/fs/selinux/enforce
    cat /sys/class/bluetooth/hci0/address
    cat /sys/class/net/wlan0/address
    cat /etc/resolv.conf
    echo "== settings =="
    settings get global http_proxy
    settings get secure android_id
    echo "== fonts =="
    ls /system/fonts/ | wc -l   # expect >= 32 (overlay merges with backing FS)
    ls /system/fonts/NotoColorEmoji.ttf
'
```

Expected outputs are tabulated in
`audit/spoof-stack/production-hooks-spec.md` § Verification.

## Acceptance criterion

After this module + the LSPosed companion + the launch-flag relaunch
are all in place, the full 63-probe panel built against the production
`ProbeContext` (not `SnapshotReplayContext`) should report
`aggregate.category = CLEAN`, `criticalFailures = 0`,
`weightedScore = 0.0000`.

## Uninstall

```sh
docker exec redroid12 sh -c '
    magisk --remove-modules
'
docker restart redroid12
```

The `uninstall.sh` script handles the cleared-state restore for
`settings put` keys and unmounts the sysfs bind-mounts.

## Notes on deviations from the spec

| Spec field | Spec layout | This package | Why |
|---|---|---|---|
| `/proc/version` | Listed under `system/proc/version` (magic-mount) | Lives in `sysfs-binds.sh` as a bind-mount | Magisk magic-mount does not cleanly overlay procfs (kernel-virtual); bind-mount is the standard mechanism. The spec acknowledges this in §3.1 (the "SELinux `mount --bind` overlay" phrase). |
| `/sys/class/bluetooth/hci0/address` and `/sys/class/net/wlan0/address` | Listed under `system/sys/class/...` | Same — bind-mount via `sysfs-binds.sh` | Same reason: sysfs is kernel-virtual. |
| `/sys/fs/selinux/enforce` | `system/sys/fs/selinux/enforce` (magic-mount) | Same — bind-mount via `sysfs-binds.sh` | Same reason. |
| LSPosed hooks (§5) | `zygisk/arm64-v8a.so` in spec's boot-sequence sketch | Not bundled here | LSPosed is a separate module ecosystem; bundling it would violate the "one concern per module" Magisk convention. |
| `system.prop` alt to service.d | Spec mentions it as an alternative | Not used | service.d/00-spoof.sh covers everything; system.prop would be redundant. |

All values (props, settings keys, MAC addresses, font filenames,
fingerprint string) are byte-for-byte the values in the spec.
