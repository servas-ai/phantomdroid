#!/usr/bin/env bash
# agents/stability/stack/launch-l2-l6-sensor-lte-spoof.sh
#
# B2 — L2..L6 sensor/identity/LTE spoof layers, layered on the Magisk-ROOTED ReDroid 12 image
# under the hardened, NON-privileged posture (B4 recipe via container_lifecycle.build_hardened_run_argv).
# This is the root-enabled successor to launch-l0b-hardened-spoof.sh: it brings up a cell that is
# (1) hardened + non-privileged + rooted AND (2) spoofed across the L1 build surface PLUS the
# L2 identity / L6 LTE-radio surfaces that ONLY become writable with root (resetprop / property_service).
#
# Defensive detection-resistance research in an owned lab. NOT an operational evasion tool.
#
# Layer coverage (see proof/b2-sensor-lte/RESULT.md for per-layer live evidence + detector deltas):
#   L1  build props (Pixel-7 panther / A13)          — resetprop, ACHIEVED
#   L0b vbmeta/bootloader/debuggable lock surface     — resetprop, ACHIEVED (root unlocks ro.debuggable=0)
#   L2  identity: ro.serialno + telephony operator    — resetprop gsm.*/ril.iccid/ro.serialno, ACHIEVED
#   L6  LTE/radio: gsm.* operator + sim + LTE type     — resetprop, ACHIEVED
#   L5  sensors                                       — BLOCKED: ReDroid ships NO sensor HAL
#                                                        (dumpsys sensorservice = "No Sensors on the
#                                                        device", devInitCheck:-19). See BLOCKER-L5.md.
#   L3  attestation (PIF/TrickyStore keybox)          — out of B2 scope (needs keybox; tracked L3-DEFAULT.md)
#   L4  runtime hiding (Shamiko/HMA)                  — out of B2 scope (Zygisk module install)
#
# NOTE on measurement: the internal detector observes identity via the live-capture snapshot pipeline
# (agents/orchestrator/src/live_matrix.py). That capture currently hardcodes telephony={}, sensorTypes=[],
# bluetoothMac=null and does NOT read ro.serialno — so the L2 telephony/serial spoof set HERE is genuinely
# present ON THE DEVICE but is only SCORED when captured with a telephony-aware snapshot. See
# proof/b2-sensor-lte/RESULT.md "L2 measurement-path gap" for the augmented-capture proof that the
# device-side spoof flips identity.imei_serial/sim_iccid/carrier_mccmnc to 0.0 (CLEAN).
#
# Usage: ./launch-l2-l6-sensor-lte-spoof.sh <container-name> <host-port> [data-dir]
set -euo pipefail

NAME="${1:?container name}"; PORT="${2:?host port}"; DATA="${3:-/home/coder/redroid-data/${NAME}}"
IMG="redroid/redroid:12.0.0_magisk"   # Magisk-rooted image (B1); su -c id => uid=0
HERE="$(cd "$(dirname "$0")" && pwd)"
ORCH="$(cd "$HERE/../../orchestrator/src" && pwd)"
CPUINFO="$HERE/modules/cpuinfo-overlay/system/etc/cpuinfo.spoofed"
PIXEL_KVER="Linux version 5.10.107-android13-4-00018-g0e9b9b9f9f9f-ab9999999 (kleaf@build-host) (Android clang version 14.0.7, LLD 14.0.7) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026"

# Realistic, NON-emulator identity values (validated against the detector inventory):
#   - operator AT&T 310/410 (NOT the AVD-canonical T-Mobile 310/260 the CarrierMccMnc probe flags)
#   - ICCID 8901410329988776652 (89-prefix, 19-digit, Luhn-valid, NOT in KNOWN_EMULATOR_ICCIDS)
#   - IMEI/serial set on-device; serial is a Pixel-class form (not the "unknown"/all-zero stock default)
SERIAL="2A111FDH2002KQ"
OP_NUMERIC="310410"; OP_ALPHA="AT&T"; OP_ISO="us"
ICCID="8901410329988776652"

mkdir -p "$DATA"

# (1) HARDENED, NON-privileged boot of the ROOTED image. build_hardened_run_argv injects the B4 recipe
#     (cap_drop ALL + bounded caps + device-cgroup-rule + l0b seccomp + no-new-privileges, NO --privileged).
#     Run via python+subprocess so the `c *:* rmw` device-cgroup-rule spaces are not mangled by a shell eval.
docker rm -f "$NAME" >/dev/null 2>&1 || true
python3 -c "
import sys; sys.path.insert(0, '$ORCH')
from container_lifecycle import build_hardened_run_argv
import subprocess
argv = build_hardened_run_argv('$IMG', '$NAME', $PORT, '$DATA')
assert '--privileged' not in argv, 'hardened launcher must never be privileged'
raise SystemExit(subprocess.run(argv).returncode)
"

echo "[l2-l6] waiting for boot_completed (hardened non-privileged rooted)..."
for _ in $(seq 1 24); do
  [ "$(docker exec "$NAME" getprop sys.boot_completed 2>/dev/null)" = "1" ] && break; sleep 5
done

# Confirm root is actually available before attempting any resetprop work.
docker exec "$NAME" su -c id | grep -q 'uid=0' || { echo "[l2-l6] FATAL: no root on $NAME"; exit 1; }

# (2) Root-only property spoof pass (resetprop). One su -c block.
docker exec "$NAME" su -c "
  RP=/sbin/resetprop
  # --- L1 build surface (Pixel 7 panther / Android 13) ---
  \$RP ro.product.brand google
  \$RP ro.product.manufacturer Google
  \$RP ro.product.model 'Pixel 7'
  \$RP ro.product.name panther
  \$RP ro.product.device panther
  \$RP ro.product.board panther
  \$RP ro.board.platform gs201
  \$RP ro.hardware gs201
  \$RP ro.build.fingerprint 'google/panther/panther:13/TQ3A.230805.001/10316531:user/release-keys'
  \$RP ro.build.display.id TQ3A.230805.001
  \$RP ro.build.tags release-keys
  \$RP ro.build.type user
  \$RP ro.bootloader 'slider-1.2-9512283'
  \$RP ro.boot.bootloader 'slider-1.2-9512283'
  \$RP ro.boot.hardware redroid
  \$RP ro.product.locale en-US
  # --- L0b verified-boot / debuggable lock surface (root unlocks ro.debuggable=0) ---
  \$RP ro.debuggable 0
  \$RP ro.boot.vbmeta.device_state green
  \$RP ro.boot.verifiedbootstate green
  \$RP ro.boot.flash.locked 1
  \$RP ro.oem_unlock_supported 0
  \$RP ro.warranty_bit 0
  \$RP ro.boot.warranty_bit 0
  # --- L2 identity: device serial (non-stock, non-zero) ---
  \$RP ro.serialno $SERIAL
  \$RP ro.boot.serialno $SERIAL
  # --- L2/L6 telephony + LTE radio (SIM ready, AT&T 310/410, LTE, valid ICCID) ---
  \$RP gsm.sim.state READY
  \$RP gsm.sim.operator.numeric $OP_NUMERIC
  \$RP gsm.sim.operator.alpha '$OP_ALPHA'
  \$RP gsm.sim.operator.iso-country $OP_ISO
  \$RP gsm.operator.numeric $OP_NUMERIC
  \$RP gsm.operator.alpha '$OP_ALPHA'
  \$RP gsm.operator.iso-country $OP_ISO
  \$RP gsm.network.type LTE
  \$RP gsm.current.phone-type 1
  \$RP persist.radio.multisim.config ss
  \$RP ril.iccid.sim1 $ICCID
"

# (3) Post-boot overlays (need SYS_ADMIN, present in the hardened cap set) + display/tz/dns/settings.
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
docker exec "$NAME" su -c '
  wm size 1080x2400; wm density 420
  setprop persist.sys.timezone America/Los_Angeles
  setprop net.dns1 1.1.1.1; setprop net.dns2 1.0.0.1
  settings put global time_zone America/Los_Angeles 2>/dev/null || true
  settings put global development_settings_enabled 0 2>/dev/null || true
  settings put global adb_enabled 0 2>/dev/null || true
'

PRIV="$(docker inspect -f '{{.HostConfig.Privileged}}' "$NAME")"
echo "[l2-l6] $NAME ready on 127.0.0.1:${PORT} — Privileged=$PRIV root=$(docker exec "$NAME" su -c id | grep -o 'uid=0(root)')"
echo "[l2-l6] L1/L0b/L2/L6 spoofed: model=$(docker exec "$NAME" getprop ro.product.model) op=$(docker exec "$NAME" getprop gsm.operator.alpha)/$(docker exec "$NAME" getprop gsm.operator.numeric) net=$(docker exec "$NAME" getprop gsm.network.type) serial=$(docker exec "$NAME" getprop ro.serialno)"
echo "[l2-l6] L5 sensors BLOCKED (no HAL) — see proof/b2-sensor-lte/BLOCKER-L5.md"
echo "[l2-l6] L2 telephony/serial scored only via telephony-aware capture — see proof/b2-sensor-lte/RESULT.md"
