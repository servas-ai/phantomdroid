#!/system/bin/sh
# service.sh — late_start_service for cpuinfo-overlay Magisk module
#
# Bind-mounts a synthesised Cortex-A78/Tensor-G2 /proc/cpuinfo over the host
# file inside the ReDroid container, masking x86 bogomips and Serial leaks.
#
# Targets: CLO-27 / probe kernel.cpuinfo_bogomips / proposal 019e2f12
# Rollback: touch /data/adb/modules/cpuinfo-overlay/disable && umount /proc/cpuinfo ; reboot
#
# Idempotent: checks Hardware field before re-binding.
umask 077
set -euo pipefail

LOG=/data/adb/cpuinfo-overlay.log
SPOOFED=/system/etc/cpuinfo.spoofed
STAGED=/data/adb/modules/cpuinfo-overlay/cpuinfo.runtime
SERIAL_FILE="${CPUINFO_OVERLAY_SERIAL_FILE:-/data/local/tmp/cpuinfo-overlay.serial}"

log() { printf '[%s] cpuinfo-overlay: %s\n' "$(date -u +%H:%M:%SZ)" "$*" >> "$LOG"; }

# Persistent synthetic Serial: written once on first boot, read thereafter.
# Source: sha256 of the container UUID (hostname, then /etc/machine-id, then
# /proc/sys/kernel/random/uuid as fallback), truncated to 16 hex chars.
# This is NOT a real device identifier — it is stable across container reboots
# so the overlay does not become its own detection signal.
if [ ! -s "$SERIAL_FILE" ]; then
  mkdir -p "$(dirname "$SERIAL_FILE")" 2>/dev/null || true
  CONTAINER_UUID="$( (hostname 2>/dev/null \
      || cat /etc/machine-id 2>/dev/null \
      || cat /proc/sys/kernel/random/uuid 2>/dev/null \
      || echo "00000000-0000-0000-0000-000000000000") | tr -d '\n' )"
  if ! printf '%s' "$CONTAINER_UUID" | sha256sum 2>/dev/null | head -c 16 > "$SERIAL_FILE"; then
    printf '%s' "0000000000000000" > "$SERIAL_FILE"
  fi
  chmod 0400 "$SERIAL_FILE" 2>/dev/null || true
fi
SERIAL="$(cat "$SERIAL_FILE" 2>/dev/null || echo "0000000000000000")"

# Build the runtime cpuinfo file with the persistent Serial injected.
sed "s/^Serial.*$/Serial\t\t: $SERIAL/" "$SPOOFED" > "$STAGED"

# Check if /proc/cpuinfo already shows Tensor G2 (idempotent).
if grep -q 'Hardware.*Tensor G2' /proc/cpuinfo 2>/dev/null; then
  log "already bound (Hardware=Tensor G2). No-op."
  exit 0
fi

# Bind-mount the staged file over /proc/cpuinfo.
mount --bind "$STAGED" /proc/cpuinfo && \
  log "bind-mount OK. Serial=$SERIAL Hardware=Tensor G2 BogoMIPS=38.40" || {
    log "ERROR: mount --bind failed (exit $?)"
    exit 1
  }
