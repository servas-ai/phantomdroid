#!/usr/bin/env bash
# profile-check.sh — CLO-132 acceptance gate for props-overlay Pixel-7 matrix.
#
# Verifies:
#   1. module.prop exists with id=props-overlay and a non-empty version.
#   2. service.sh exists, is executable (or readable), passes `bash -n` syntax
#      check, and is idempotent when re-invoked against the same prop store
#      (a stubbed resetprop that records writes shows zero writes on the
#      second invocation when the store already matches).
#   3. system/build.prop contains all five required fields with valid
#      Pixel-7 values:
#        - ro.product.brand        = google
#        - ro.product.manufacturer = Google
#        - ro.product.model        = Pixel 7
#        - ro.build.id             = matches /^[A-Z]{2}[0-9][A-Z]\.[0-9]+\.[0-9]+$/
#        - ro.build.fingerprint    = matches google/panther/panther:14/<build-id>/<inc>:user/release-keys
#
# Exit codes: 0 = pass, non-zero = fail with diagnostic on stderr.
umask 077
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODULE_PROP="$MODULE_DIR/module.prop"
SERVICE_SH="$MODULE_DIR/service.sh"
BUILD_PROP="$MODULE_DIR/system/build.prop"

fail() { echo "FAIL: $*" >&2; exit "${2:-1}"; }

# ---------------------------------------------------------------------------
# 1. module.prop present, parseable.
# ---------------------------------------------------------------------------
[ -f "$MODULE_PROP" ] || fail "module.prop missing at $MODULE_PROP" 2
mp_id="$(awk -F= '/^id=/{print $2; exit}' "$MODULE_PROP")"
mp_version="$(awk -F= '/^version=/{print $2; exit}' "$MODULE_PROP")"
[ "$mp_id" = "props-overlay" ] || fail "module.prop id='$mp_id' (want 'props-overlay')" 3
[ -n "$mp_version" ] || fail "module.prop version empty" 4

# ---------------------------------------------------------------------------
# 2a. service.sh present, valid bash syntax.
# ---------------------------------------------------------------------------
[ -f "$SERVICE_SH" ] || fail "service.sh missing at $SERVICE_SH" 5
bash -n "$SERVICE_SH" 2>/dev/null || fail "service.sh failed bash -n syntax check" 6

# ---------------------------------------------------------------------------
# 2b. Idempotency: stub `resetprop`, run service.sh twice, assert second
#     pass performs zero `resetprop --persist` writes (everything already set).
# ---------------------------------------------------------------------------
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT
STORE="$TMP_ROOT/store"
WRITES="$TMP_ROOT/writes"
LOG_FILE="$TMP_ROOT/props-overlay.log"
: > "$STORE" "$WRITES"
mkdir -p "$TMP_ROOT/data/adb/modules/props-overlay"

# Stub resetprop:
#   resetprop KEY                 -> echo current value (or empty)
#   resetprop --persist KEY VAL   -> upsert, record one write
cat > "$TMP_ROOT/resetprop" <<'EOF'
#!/usr/bin/env bash
set -eu
STORE="${PROPS_OVERLAY_TEST_STORE}"
WRITES="${PROPS_OVERLAY_TEST_WRITES}"
if [ "$1" = "--persist" ]; then
  key="$2"; val="$3"
  grep -v "^$key=" "$STORE" 2>/dev/null > "$STORE.tmp" || true
  printf '%s=%s\n' "$key" "$val" >> "$STORE.tmp"
  mv "$STORE.tmp" "$STORE"
  printf '%s=%s\n' "$key" "$val" >> "$WRITES"
  exit 0
fi
key="$1"
awk -F= -v k="$key" '$1==k{print substr($0, index($0,"=")+1); found=1; exit} END{if(!found) exit 0}' "$STORE"
EOF
chmod +x "$TMP_ROOT/resetprop"

# Stub /data/adb log path + redirect LOG via env override-friendly run.
# service.sh has hard-coded LOG=/data/adb/props-overlay.log; run with a
# wrapper that intercepts that path via PATH+sed copy into the temp tree.
WORK_SCRIPT="$TMP_ROOT/service.sh"
sed -e "s|^LOG=.*|LOG=$LOG_FILE|" "$SERVICE_SH" > "$WORK_SCRIPT"
chmod +x "$WORK_SCRIPT"

run_service() {
  PATH="$TMP_ROOT:$PATH" \
  PROPS_OVERLAY_TEST_STORE="$STORE" \
  PROPS_OVERLAY_TEST_WRITES="$WRITES" \
  MODULE_DIR="$TMP_ROOT/data/adb/modules/props-overlay" \
    bash "$WORK_SCRIPT"
}

# First invocation: should set all 5 props.
run_service
writes_first=$(wc -l < "$WRITES" | tr -d ' ')
if [ "$writes_first" -ne 5 ]; then
  fail "first invocation wrote $writes_first props (want 5); store=$(cat "$STORE"); writes=$(cat "$WRITES")" 7
fi

# Second invocation: store already matches → expect zero new writes.
: > "$WRITES"
run_service
writes_second=$(wc -l < "$WRITES" | tr -d ' ')
if [ "$writes_second" -ne 0 ]; then
  fail "second invocation wrote $writes_second props (want 0 — not idempotent); writes=$(cat "$WRITES")" 8
fi

# ---------------------------------------------------------------------------
# 3. build.prop has all five fields with valid Pixel-7 values.
# ---------------------------------------------------------------------------
[ -f "$BUILD_PROP" ] || fail "system/build.prop missing at $BUILD_PROP" 9

get_prop() {
  awk -F= -v k="$1" '$1==k{
    sub(/^[^=]*=/,"",$0); print; found=1; exit
  } END{if(!found) exit 0}' "$BUILD_PROP"
}

bp_brand="$(get_prop ro.product.brand)"
bp_mfg="$(get_prop ro.product.manufacturer)"
bp_model="$(get_prop ro.product.model)"
bp_id="$(get_prop ro.build.id)"
bp_fp="$(get_prop ro.build.fingerprint)"

[ "$bp_brand" = "google" ] \
  || fail "ro.product.brand='$bp_brand' (want 'google')" 10
[ "$bp_mfg" = "Google" ] \
  || fail "ro.product.manufacturer='$bp_mfg' (want 'Google')" 11
[ "$bp_model" = "Pixel 7" ] \
  || fail "ro.product.model='$bp_model' (want 'Pixel 7')" 12

# Pixel build-id pattern: 2 uppercase letters, digit, uppercase letter, dot,
# 6-digit YYMMDD-ish, dot, 3-digit revision (e.g. UQ1A.240205.004).
echo "$bp_id" | grep -Eq '^[A-Z]{2}[0-9][A-Z]\.[0-9]{6}\.[0-9]{3}$' \
  || fail "ro.build.id='$bp_id' does not match Pixel build-id pattern" 13

# Fingerprint pattern: google/panther/panther:14/<build-id>/<incremental>:user/release-keys
echo "$bp_fp" \
  | grep -Eq '^google/panther/panther:14/[A-Z]{2}[0-9][A-Z]\.[0-9]{6}\.[0-9]{3}/[0-9]+:user/release-keys$' \
  || fail "ro.build.fingerprint='$bp_fp' does not match Pixel-7/panther/Android-14 pattern" 14

# Fingerprint must reference the same build id we asserted above.
echo "$bp_fp" | grep -qF "/$bp_id/" \
  || fail "ro.build.fingerprint does not reference ro.build.id='$bp_id'" 15

cat <<RESULT
PASS: module.prop id=props-overlay version=$mp_version
PASS: service.sh syntax ok; idempotent (5 writes first run, 0 writes second run)
PASS: build.prop fields:
        ro.product.brand        = $bp_brand
        ro.product.manufacturer = $bp_mfg
        ro.product.model        = $bp_model
        ro.build.id             = $bp_id
        ro.build.fingerprint    = $bp_fp
RESULT
exit 0
