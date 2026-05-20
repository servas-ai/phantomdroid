#!/system/bin/sh
# SpoofStack uninstall.sh
#
# Runs when the module is removed via Magisk Manager (or
# `magisk --remove-modules`). Reverts the writable side-effects:
# settings.* keys (the resetprop and magic-mount changes naturally
# disappear at next reboot once the module's system/ tree is no longer
# loaded; sysfs bind-mounts unmount on container restart).
#
# We do NOT attempt to restore the original `settings put` values because
# we don't have a record of them. Operators should either:
#   (a) accept the cleared defaults below, or
#   (b) snapshot the pre-install settings and restore them manually.
#
# Source of truth: audit/spoof-stack/production-hooks-spec.md §4.

set -e

# Best-effort: restore developer-mode-disabled defaults. These are the
# values a freshly-flashed Pixel reports, so they're a reasonable
# fallback.
settings put global development_settings_enabled 0 2>/dev/null || true
settings put global adb_enabled 0                2>/dev/null || true

# Unbind sysfs / proc overlays (handles the case where the operator
# uninstalls without rebooting).
umount /proc/version                        2>/dev/null || true
umount /sys/fs/selinux/enforce              2>/dev/null || true
umount /sys/class/bluetooth/hci0/address    2>/dev/null || true
umount /sys/class/net/wlan0/address         2>/dev/null || true
