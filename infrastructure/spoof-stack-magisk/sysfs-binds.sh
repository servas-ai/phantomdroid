#!/system/bin/sh
# SpoofStack sysfs-binds.sh
#
# Bind-mounts canned content over kernel-virtual paths that Magisk
# magic-mount cannot cleanly overlay. Called from post-fs-data.sh with
# $MODDIR as the first argument.
#
# Why bind-mounts and not magic-mount?
# - /sys/class/* and /proc/* are tmpfs / procfs / sysfs — kernel-virtual.
# - Magisk's magic-mount targets the /system partition's overlay tree and
#   does not extend cleanly to non-/system mount points.
# - mount --bind is the standard kernel mechanism that DOES work here.
#
# Source of truth: audit/spoof-stack/production-hooks-spec.md §3.1, §3.3,
# §3.4, §3.5.

set -e

MODDIR="${1:-${0%/*}}"
PAYLOAD="$MODDIR/sysfs-payload"

# ---------------------------------------------------------------------------
# §3.1 /proc/version — Pixel-7 Android-13 GKI banner.
# Mirrors what `uname -a` + `cat /proc/version` print on a real Pixel 7
# (kernel 5.10.149 GKI; kleaf+clang build host).
# ---------------------------------------------------------------------------
PROC_VERSION_PAYLOAD="$PAYLOAD/proc-version"
mkdir -p "$PAYLOAD"
cat > "$PROC_VERSION_PAYLOAD" <<'EOF'
Linux version 5.10.149-android13-4-00007-g4350da46ef7e-ab9947749 (kleaf@build-host) (Android (8508608, based on r450784e) clang version 14.0.7 (https://android.googlesource.com/toolchain/llvm-project 4c603efb0cca074e9238af8b4106c30add4418f6), LLD 14.0.7) #1 SMP PREEMPT Tue May 24 03:31:48 UTC 2022
EOF
mount --bind "$PROC_VERSION_PAYLOAD" /proc/version

# ---------------------------------------------------------------------------
# §3.3 /sys/fs/selinux/enforce — must be "1" (enforcing).
# selinuxfs is read-only for direct writes; bind-mount is the only path.
# ---------------------------------------------------------------------------
SELINUX_ENFORCE_PAYLOAD="$PAYLOAD/selinux-enforce"
printf '1' > "$SELINUX_ENFORCE_PAYLOAD"
mount --bind "$SELINUX_ENFORCE_PAYLOAD" /sys/fs/selinux/enforce

# ---------------------------------------------------------------------------
# §3.4 /sys/class/bluetooth/hci0/address — Pixel-7-realistic MAC.
# Pair with the LSPosed `BluetoothAdapter.getAddress()` hook for the
# framework-side dual surface.
# ---------------------------------------------------------------------------
HCI0_PAYLOAD="$PAYLOAD/hci0-address"
printf '3c:5a:b4:8d:f1:27\n' > "$HCI0_PAYLOAD"
if [ -e /sys/class/bluetooth/hci0/address ]; then
    mount --bind "$HCI0_PAYLOAD" /sys/class/bluetooth/hci0/address
fi

# ---------------------------------------------------------------------------
# §3.5 /sys/class/net/wlan0/address — Pixel-7-realistic MAC.
# Pair with the LSPosed `WifiInfo.getMacAddress()` hook (the framework
# redacts to 02:00:00:00:00:00 for non-LOCAL_MAC_ADDRESS callers).
# ---------------------------------------------------------------------------
WLAN0_PAYLOAD="$PAYLOAD/wlan0-address"
printf '40:4e:36:7a:b2:c9\n' > "$WLAN0_PAYLOAD"
if [ -e /sys/class/net/wlan0/address ]; then
    mount --bind "$WLAN0_PAYLOAD" /sys/class/net/wlan0/address
fi
