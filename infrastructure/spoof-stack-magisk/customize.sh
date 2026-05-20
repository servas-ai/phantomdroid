#!/system/bin/sh
# SpoofStack customize.sh
#
# Magisk install-time customization hook. Runs once when the module is
# installed via Magisk Manager (or `magisk --install-module spoofstack.zip`).
#
# Magisk reads the following variables from this script:
#   - SKIPUNZIP=0     keep automatic unzip (default)
#   - SKIPMOUNT=0     keep automatic mount (default)
#
# We use this hook to:
#   1. Print install banner and target environment.
#   2. Validate that resetprop, settings, and wm binaries exist (sanity
#      check — if any are missing, the module won't work, fail loudly now).
#   3. Set execute permissions on the bundled scripts.
#
# Source of truth: audit/spoof-stack/production-hooks-spec.md §Boot Sequence.

set -e

ui_print "*****************************************"
ui_print "  SpoofStack Power-8 (ReDroid 12)"
ui_print "  Pixel-7 spoof overlay"
ui_print "  v1.0.0 — 104 hooks consolidated"
ui_print "*****************************************"

ui_print "- Validating runtime dependencies"
for bin in resetprop settings wm mount; do
    if ! command -v "$bin" >/dev/null 2>&1; then
        ui_print "! ERROR: $bin not found on PATH"
        ui_print "!        SpoofStack requires Magisk 20.4+ on ReDroid 12"
        abort "  install aborted"
    fi
done

ui_print "- Setting permissions on scripts"
set_perm "$MODPATH/post-fs-data.sh"          0 0 0755
set_perm "$MODPATH/sysfs-binds.sh"            0 0 0755
set_perm "$MODPATH/service.d/00-spoof.sh"     0 0 0755
set_perm "$MODPATH/service.d/01-settings.sh"  0 0 0755
set_perm "$MODPATH/uninstall.sh"              0 0 0755

ui_print "- Setting permissions on overlay tree"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644

ui_print "- Reminder: companion LSPosed module"
ui_print "  must be installed for §5 hooks"
ui_print "  (TelephonyManager, Bluetooth, WiFi,"
ui_print "  Locale/TimeZone, SensorManager,"
ui_print "  Build.SUPPORTED_ABIS reflection)"

ui_print "- Reminder: companion ReDroid launch flag"
ui_print "  --display=1080x2400 required for §5.6"
ui_print "  DisplayMetrics coverage"

ui_print "*****************************************"
ui_print "  Install complete — reboot to activate"
ui_print "*****************************************"
