#!/system/bin/sh
# service.sh — late_start_service for props-overlay Magisk module
#
# Re-applies the five Pixel-7 build.prop fields via `resetprop --persist`
# on top of the static system/build.prop overlay shipped by this module.
# Reason: some apps query properties via the binder property service, which
# reads the persisted resetprop store rather than re-parsing build.prop.
# This dual approach (static overlay + runtime resetprop) survives both
# init-time and runtime detection probes.
#
# Targets: CLO-132 (G-MOD-501) — Pixel-7 fingerprint matrix.
# Rollback: touch /data/adb/modules/props-overlay/disable && reboot
#
# Idempotent: a re-invocation re-asserts the same values; resetprop --persist
# is a no-op when the property already matches.
umask 077
set -eu

LOG=/data/adb/props-overlay.log
PROPS_FILE=/system/build.prop
MODULE_DIR="${MODULE_DIR:-/data/adb/modules/props-overlay}"

log() { printf '[%s] props-overlay: %s\n' "$(date -u +%H:%M:%SZ)" "$*" >> "$LOG"; }

# Five-field Pixel-7 (panther / Tensor-G2 / Android 14) fingerprint matrix.
# Values mirror system/build.prop and EXPERIMENTS.md target_device.
PROPS_FINGERPRINT='google/panther/panther:14/UQ1A.240205.004/11224170:user/release-keys'
PROPS_BUILD_ID='UQ1A.240205.004'
PROPS_BRAND='google'
PROPS_MANUFACTURER='Google'
PROPS_MODEL='Pixel 7'

apply_prop() {
  key="$1"
  want="$2"
  have="$(resetprop "$key" 2>/dev/null || true)"
  if [ "$have" = "$want" ]; then
    log "noop $key=$want (already set)"
    return 0
  fi
  if resetprop --persist "$key" "$want" 2>/dev/null; then
    log "set $key=$want (was '${have:-<unset>}')"
  else
    log "ERROR resetprop failed for $key=$want"
    return 1
  fi
}

apply_prop ro.product.brand        "$PROPS_BRAND"
apply_prop ro.product.manufacturer "$PROPS_MANUFACTURER"
apply_prop ro.product.model        "$PROPS_MODEL"
apply_prop ro.build.id             "$PROPS_BUILD_ID"
apply_prop ro.build.fingerprint    "$PROPS_FINGERPRINT"

log "props-overlay service complete."
exit 0
