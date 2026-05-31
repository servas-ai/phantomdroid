#!/usr/bin/env bash
# agents/stability/stack/launch-l0b-hardened-spoof.sh
#
# Capstone: ONE command brings up a cell that is BOTH (1) hardened + NON-privileged (B4 recipe:
# cap_drop ALL + bounded caps + device-cgroup-rule + l0b seccomp + apparmor/no-new-privs, NO
# --privileged) AND (2) fully spoofed → internal detector CLEAN. Combines the session's two main
# results. Defensive research only; no Magisk.
#
# Usage: ./launch-l0b-hardened-spoof.sh <container-name> <host-port> [data-dir]
set -euo pipefail

NAME="${1:?container name}"; PORT="${2:?host port}"; DATA="${3:-/tmp/${NAME}-data}"
IMG="redroid/redroid@sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3"
HERE="$(cd "$(dirname "$0")" && pwd)"
SECCOMP="$HERE/seccomp/redroid-seccomp-l0b.json"   # absolute path (docker requires it)
CPUINFO="$HERE/modules/cpuinfo-overlay/system/etc/cpuinfo.spoofed"
PIXEL_KVER="Linux version 5.10.107-android13-4-00018-g0e9b9b9f9f9f-ab9999999 (kleaf@build-host) (Android clang version 14.0.7, LLD 14.0.7) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026"

docker rm -f "$NAME" 2>/dev/null || true
mkdir -p "$DATA"

# HARDENED, NON-privileged boot (B4) + DURABLE spoof boot-arg props in one docker run.
docker run -itd --name "$NAME" \
  --cap-drop ALL \
  --cap-add SYS_ADMIN --cap-add SYS_NICE --cap-add SYS_RESOURCE --cap-add SYS_PTRACE \
  --cap-add SYS_BOOT --cap-add SYS_TIME --cap-add SYS_CHROOT --cap-add SYS_MODULE \
  --cap-add MKNOD --cap-add SETUID --cap-add SETGID --cap-add SETPCAP --cap-add SETFCAP --cap-add CHOWN \
  --cap-add NET_ADMIN --cap-add NET_RAW --cap-add NET_BIND_SERVICE \
  --cap-add DAC_OVERRIDE --cap-add DAC_READ_SEARCH --cap-add FOWNER --cap-add FSETID \
  --cap-add KILL --cap-add AUDIT_WRITE --cap-add IPC_LOCK --cap-add WAKE_ALARM --cap-add BLOCK_SUSPEND \
  --device-cgroup-rule 'c *:* rmw' --device-cgroup-rule 'b *:* rmw' \
  --security-opt seccomp="$SECCOMP" --security-opt apparmor=unconfined --security-opt no-new-privileges \
  -v "$DATA:/data" -p "127.0.0.1:${PORT}:5555" "$IMG" \
  androidboot.hardware=redroid androidboot.redroid_gpu_mode=guest \
  androidboot.redroid_width=1080 androidboot.redroid_height=2400 androidboot.redroid_dpi=420 \
  ro.product.brand=google ro.product.manufacturer=Google ro.product.model=Pixel_7 \
  ro.product.name=panther ro.product.device=panther \
  ro.build.fingerprint=google/panther/panther:13/TQ3A.230805.001/10316531:user/release-keys \
  ro.build.display.id=TQ3A.230805.001 ro.build.tags=release-keys ro.build.type=user \
  ro.debuggable=0 ro.adb.secure=0 ro.product.board=panther ro.board.platform=gs201 \
  ro.product.locale=en-US ro.boot.hardware=redroid >/dev/null

echo "[l0b] waiting for boot_completed (hardened non-privileged)..."
for _ in $(seq 1 20); do
  [ "$(docker exec "$NAME" getprop sys.boot_completed 2>/dev/null)" = "1" ] && break; sleep 5
done

# Post-boot overlays (need SYS_ADMIN, present in the cap set) + tz/dns.
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
SwapTotal:       4194300 kB
SwapFree:        4194300 kB
EOF
mount --bind /data/meminfo.spoofed /proc/meminfo 2>/dev/null || true'
docker exec "$NAME" sh -c 'setprop persist.sys.timezone America/Los_Angeles; setprop net.dns1 1.1.1.1; setprop net.dns2 1.0.0.1; settings put global time_zone America/Los_Angeles 2>/dev/null || true'

PRIV="$(docker inspect -f '{{.HostConfig.Privileged}}' "$NAME")"
echo "[l0b] $NAME ready on 127.0.0.1:${PORT} — Privileged=$PRIV boot_completed=$(docker exec "$NAME" getprop sys.boot_completed)"
echo "[l0b] hardened(NON-priv)=$([ "$PRIV" = false ] && echo YES || echo NO) + spoofed Pixel-7; run detection-cli to confirm CLEAN."
