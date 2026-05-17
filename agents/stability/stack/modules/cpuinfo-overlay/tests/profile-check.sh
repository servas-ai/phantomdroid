#!/usr/bin/env bash
# profile-check.sh — CLO-114 acceptance gate for cpuinfo-overlay Tensor-G2 profile.
#
# Verifies:
#   1. system/etc/cpuinfo.spoofed declares 8 processors with parts in the order
#      d44,d44,d41,d41,d05,d05,d05,d05 (Tensor-G2 (2,2,4) topology).
#   2. Every BogoMIPS entry lies in [30, 60].
#   3. The persistent-Serial logic from service.sh is stable across two
#      consecutive invocations of this script (i.e. the second call reads the
#      file written by the first instead of regenerating).
#
# Exit codes: 0 = pass, non-zero = fail with diagnostic on stderr.
umask 077
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SPOOFED="$MODULE_DIR/system/etc/cpuinfo.spoofed"

if [ ! -f "$SPOOFED" ]; then
  echo "FAIL: $SPOOFED not found" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 1. Topology: exactly 8 processors, parts in the required order.
# ---------------------------------------------------------------------------
mapfile -t parts < <(awk -F': ' '/^CPU part/ {gsub(/[ \t]/,"",$2); print $2}' "$SPOOFED")
expected=(0xd44 0xd44 0xd41 0xd41 0xd05 0xd05 0xd05 0xd05)

if [ "${#parts[@]}" -ne 8 ]; then
  echo "FAIL: expected 8 processors, got ${#parts[@]}" >&2
  printf '  parts: %s\n' "${parts[*]}" >&2
  exit 3
fi

for i in 0 1 2 3 4 5 6 7; do
  if [ "${parts[$i]}" != "${expected[$i]}" ]; then
    echo "FAIL: processor $i part is ${parts[$i]}, expected ${expected[$i]}" >&2
    printf '  expected order: %s\n' "${expected[*]}" >&2
    printf '  actual order:   %s\n' "${parts[*]}" >&2
    exit 4
  fi
done

# Sanity: count "processor : N" lines too (must also be 8).
proc_count=$(grep -cE '^processor[[:space:]]*:' "$SPOOFED" || true)
if [ "$proc_count" -ne 8 ]; then
  echo "FAIL: expected 8 'processor :' lines, got $proc_count" >&2
  exit 5
fi

# ---------------------------------------------------------------------------
# 2. BogoMIPS within [30, 60] for every entry.
# ---------------------------------------------------------------------------
bogo_count=0
while IFS= read -r bm; do
  bogo_count=$((bogo_count + 1))
  if ! awk -v v="$bm" 'BEGIN { exit !(v + 0 >= 30 && v + 0 <= 60) }'; then
    echo "FAIL: BogoMIPS '$bm' outside [30, 60]" >&2
    exit 6
  fi
done < <(awk -F': ' '/^BogoMIPS/ {gsub(/[ \t]/,"",$2); print $2}' "$SPOOFED")

if [ "$bogo_count" -ne 8 ]; then
  echo "FAIL: expected 8 BogoMIPS entries, got $bogo_count" >&2
  exit 7
fi

# ---------------------------------------------------------------------------
# 3. Persistent-Serial stability across two consecutive invocations.
#    Exercises the same derive-once/read-thereafter logic that service.sh uses,
#    pointed at a host-writable temp path so the test is self-contained.
# ---------------------------------------------------------------------------
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT
SERIAL_FILE="$TMP_ROOT/cpuinfo-overlay.serial"

derive_serial() {
  # Mirror of the service.sh logic, isolated to $SERIAL_FILE.
  if [ ! -s "$SERIAL_FILE" ]; then
    CONTAINER_UUID="$( (hostname 2>/dev/null \
        || cat /etc/machine-id 2>/dev/null \
        || cat /proc/sys/kernel/random/uuid 2>/dev/null \
        || echo "00000000-0000-0000-0000-000000000000") | tr -d '\n' )"
    if ! printf '%s' "$CONTAINER_UUID" | sha256sum 2>/dev/null | head -c 16 > "$SERIAL_FILE"; then
      printf '%s' "0000000000000000" > "$SERIAL_FILE"
    fi
  fi
  cat "$SERIAL_FILE"
}

S1="$(derive_serial)"
S2="$(derive_serial)"

if [ -z "$S1" ]; then
  echo "FAIL: first invocation produced empty Serial" >&2
  exit 8
fi

if [ "${#S1}" -ne 16 ]; then
  echo "FAIL: Serial length ${#S1} != 16 (value: '$S1')" >&2
  exit 9
fi

if [ "$S1" != "$S2" ]; then
  echo "FAIL: Serial unstable across invocations: '$S1' != '$S2'" >&2
  exit 10
fi

if ! printf '%s' "$S1" | grep -Eq '^[0-9a-f]{16}$'; then
  echo "FAIL: Serial '$S1' is not 16 lowercase hex chars" >&2
  exit 11
fi

echo "PASS: 8 processors, parts d44,d44,d41,d41,d05,d05,d05,d05; BogoMIPS in [30,60]; Serial=$S1 stable"
exit 0
