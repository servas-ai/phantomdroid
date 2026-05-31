#!/usr/bin/env bash
# agents/stability/stack/launch-l1-spoof.sh
#
# Durable, reproducible bring-up of a fully-spoofed L1 ReDroid 12 cell that scores CLEAN on the
# internal 65-probe detector — from ONE command. Consolidates all the runtime spoof steps proven
# this session into (a) boot-arg properties (durable across restarts) + (b) a post-boot overlay pass
# (bind-mounts can't be set via boot args). Defensive research only; lightweight, NO Magisk.
#
# Usage: ./launch-l1-spoof.sh <container-name> <host-port> [data-dir]
set -euo pipefail

NAME="${1:?container name}"; PORT="${2:?host port}"; DATA="${3:-/tmp/${NAME}-data}"
IMG="redroid/redroid@sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3"
PIXEL_KVER="Linux version 5.10.107-android13-4-00018-g0e9b9b9f9f9f-ab9999999 (kleaf@build-host) (Android clang version 14.0.7, LLD 14.0.7) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026"
HERE="$(cd "$(dirname "$0")" && pwd)"
CPUINFO="$HERE/modules/cpuinfo-overlay/system/etc/cpuinfo.spoofed"

docker rm -f "$NAME" 2>/dev/null || true
mkdir -p "$DATA"

# (a) DURABLE boot-arg properties: identity + Pixel-7 display + locale + DNS + secure-build.
docker run -itd --privileged --name "$NAME" -v "$DATA:/data" -p "127.0.0.1:${PORT}:5555" "$IMG" \
  androidboot.hardware=redroid androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=1080 androidboot.redroid_height=2400 androidboot.redroid_dpi=420 \
  ro.product.brand=google ro.product.manufacturer=Google ro.product.model=Pixel_7 \
  ro.product.name=panther ro.product.device=panther \
  ro.build.fingerprint=google/panther/panther:13/TQ3A.230805.001/10316531:user/release-keys \
  ro.build.display.id=TQ3A.230805.001 ro.build.tags=release-keys ro.build.type=user \
  ro.debuggable=0 ro.adb.secure=0 ro.product.board=panther ro.board.platform=gs201 \
  ro.product.locale=en-US persist.sys.timezone=America/Los_Angeles net.dns1=1.1.1.1 net.dns2=1.0.0.1 \
  ro.boot.hardware=redroid >/dev/null

echo "[launch] waiting for boot_completed..."
for _ in $(seq 1 18); do
  [ "$(docker exec "$NAME" getprop sys.boot_completed 2>/dev/null)" = "1" ] && break; sleep 5
done

# (b) Post-boot overlays (bind-mounts can't be boot args): su-hide, cpuinfo, /proc/version, /proc/meminfo.
docker cp "$CPUINFO" "$NAME:/data/cpuinfo.spoofed" >/dev/null 2>&1 || true
docker exec "$NAME" sh -c '
  touch /data/empty_su; mount --bind /data/empty_su /system/xbin/su 2>/dev/null || true
  [ -f /data/cpuinfo.spoofed ] && mount --bind /data/cpuinfo.spoofed /proc/cpuinfo 2>/dev/null || true
'
docker exec "$NAME" sh -c "printf '%s\n' '$PIXEL_KVER' > /data/version.spoofed; mount --bind /data/version.spoofed /proc/version 2>/dev/null || true"
docker exec "$NAME" sh -c 'cat > /data/meminfo.spoofed <<EOF
MemTotal:        7847328 kB
MemFree:         1923456 kB
MemAvailable:    4521984 kB
Buffers:           84320 kB
Cached:          2487104 kB
SwapCached:            0 kB
SwapTotal:       4194300 kB
SwapFree:        4194300 kB
EOF
mount --bind /data/meminfo.spoofed /proc/meminfo 2>/dev/null || true'
# persist.sys.timezone and net.dns* do NOT stick as boot args — set them post-boot via setprop.
docker exec "$NAME" sh -c '
  setprop persist.sys.timezone America/Los_Angeles
  settings put global time_zone America/Los_Angeles 2>/dev/null || true
  setprop net.dns1 1.1.1.1; setprop net.dns2 1.0.0.1
'

echo "[launch] $NAME ready on 127.0.0.1:${PORT} (boot_completed=$(docker exec "$NAME" getprop sys.boot_completed 2>/dev/null))"
echo "[launch] fingerprint=$(docker exec "$NAME" getprop ro.build.fingerprint)"
echo "[launch] resolution=$(docker exec "$NAME" wm size 2>/dev/null | tail -1) tz=$(docker exec "$NAME" getprop persist.sys.timezone)"
