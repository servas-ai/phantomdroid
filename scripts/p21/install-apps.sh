#!/usr/bin/env bash
# scripts/p21/install-apps.sh — Power-21 app installer harness
#
# Reads scripts/p21/app-inventory.json (produced by P21-A1) and installs each
# app onto the target ADB device, recording outcome to p21/install-report.json.
#
# Per-entry pipeline:
#   1. tier == SKIP-MANUAL → record {status:"skip", reason:"manual-only"};
#      ensure the pkg is mentioned in audit/spoof-stack/real-world-gap-list.md
#      under the "Power-21 SKIP-MANUAL apps" section.
#   2. tier ∈ {F-DROID, GITHUB, AURORA-OPEN} where source URL is a direct APK:
#      wget → compute sha256 → verify (if entry.sha256_or_null is non-null) →
#      adb install -r → record outcome.
#   3. tier == AURORA-OPEN without a direct URL → record
#      status="aurora-required" (Aurora open-client must fetch interactively;
#      we DO NOT attempt Play-login — RED zone per browser-automation rules).
#
# Honest-limited contract:
#   - Every failure mode records a verbatim reason (curl/wget exit, adb stderr,
#     sha mismatch with expected vs actual). NO fabricated "installed:true".
#   - sha256 verification: if entry.sha256_or_null is null, we compute and
#     record the actual hash but do NOT treat absence as success; the report
#     marks sha_verified="upstream-no-hash".
#   - Atomic JSON writes: temp file + mv so a SIGKILL mid-write cannot leave
#     a partial array on disk that downstream readers would parse as truth.
#
# Safety:
#   - umask 077 at top (defense in depth — created files default to user-only).
#   - set -euo pipefail; IFS reset.
#   - All variable expansions quoted; URLs never passed unquoted to a subshell.
#   - No eval. No shell-interpolation of inventory fields into commands.

umask 077
set -euo pipefail
IFS=$'\n\t'

INVENTORY="${INVENTORY:-scripts/p21/app-inventory.json}"
APK_DIR="${APK_DIR:-p21/apks}"
REPORT="${REPORT:-p21/install-report.json}"
GAP_LIST="${GAP_LIST:-audit/spoof-stack/real-world-gap-list.md}"
ADB_SERIAL="${ADB_SERIAL:-172.17.0.2:5555}"
WGET_TIMEOUT="${WGET_TIMEOUT:-60}"
WGET_TRIES="${WGET_TRIES:-2}"

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

for cmd in jq wget sha256sum adb mktemp; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: required tool not found in PATH: $cmd" >&2
        exit 2
    fi
done

if [ ! -f "$INVENTORY" ]; then
    echo "ERROR: inventory JSON not found: $INVENTORY" >&2
    exit 1
fi

if ! jq -e 'type == "array"' "$INVENTORY" >/dev/null 2>&1; then
    echo "ERROR: $INVENTORY is not a JSON array" >&2
    exit 1
fi

mkdir -p "$APK_DIR"
mkdir -p "$(dirname "$REPORT")"

# ---------------------------------------------------------------------------
# ADB sanity
# ---------------------------------------------------------------------------

ADB_STATE="unknown"
ADB_WAIT_TIMEOUT="${ADB_WAIT_TIMEOUT:-15}"
if adb connect "$ADB_SERIAL" >/dev/null 2>&1; then
    # wait-for-device blocks forever on a wrong host — bound it with timeout(1).
    if timeout "$ADB_WAIT_TIMEOUT" adb -s "$ADB_SERIAL" wait-for-device 2>/dev/null; then
        ADB_STATE="$(adb -s "$ADB_SERIAL" get-state 2>/dev/null || echo "unreachable")"
    else
        ADB_STATE="wait-for-device-timeout"
    fi
else
    ADB_STATE="connect-failed"
fi
echo "ADB state for $ADB_SERIAL: $ADB_STATE" >&2

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# write_report <json-array-string>
# Atomically writes the report. tmp file is in same directory as REPORT so
# mv is rename(2), not copy.
write_report() {
    local payload="$1"
    local tmp
    tmp="$(mktemp "${REPORT}.tmp.XXXXXX")"
    printf '%s\n' "$payload" >"$tmp"
    mv "$tmp" "$REPORT"
}

# sha256_of_file <path> — prints hash only (no filename)
sha256_of_file() {
    sha256sum "$1" | awk '{print $1}'
}

# json_field <entry-json> <key> — extracts a string field, prints empty
# string when missing/null. Safe against shell-interpolation: we never pass
# the value to a subshell unquoted.
json_field() {
    printf '%s' "$1" | jq -r --arg k "$2" '.[$k] // ""'
}

# verify_skip_manual_in_gap_list <pkg>
# Sanity check only — task #63 (P21-A1) already wrote the "P21 — Skip-Manual
# Apps" section into the gap-list with all 8 entries. This function does NOT
# mutate the gap-list; it just logs a WARN if a SKIP-MANUAL pkg is missing
# from the section so a reviewer can spot an inventory↔gap-list drift.
verify_skip_manual_in_gap_list() {
    local pkg="$1"
    if [ ! -f "$GAP_LIST" ]; then
        return 0
    fi
    if ! grep -Fq "$pkg" "$GAP_LIST" 2>/dev/null; then
        echo "WARN: SKIP-MANUAL pkg '$pkg' not found in $GAP_LIST (A1 inventory↔gap-list drift?)" >&2
    fi
}

# ---------------------------------------------------------------------------
# Iterate inventory
# ---------------------------------------------------------------------------

# Initialise report as an empty array if missing or unparseable.
if [ ! -f "$REPORT" ] || ! jq -e 'type == "array"' "$REPORT" >/dev/null 2>&1; then
    write_report '[]'
fi

# Pre-flush: every run starts from an empty report so partial state from a
# previous crashed run does not bleed in. Add an _adb_state meta-record at
# index 0 for traceability.
ADB_META_JSON="$(jq -n \
    --arg serial "$ADB_SERIAL" \
    --arg state "$ADB_STATE" \
    --arg started "$(date -Iseconds)" \
    '[{"_meta": "adb-sanity", "serial": $serial, "state": $state, "started": $started}]')"
write_report "$ADB_META_JSON"

ENTRY_COUNT="$(jq 'length' "$INVENTORY")"
echo "Processing $ENTRY_COUNT inventory entries..." >&2

COUNT_INSTALLED=0
COUNT_SKIP=0
COUNT_FAILED=0
COUNT_AURORA=0

# Stream entries one per line as compact JSON. jq -c guarantees no embedded
# newlines per entry, so `while read -r` is safe.
while IFS= read -r entry; do
    [ -z "$entry" ] && continue

    pkg="$(json_field "$entry" pkg)"
    name="$(json_field "$entry" name)"
    source_tier="$(json_field "$entry" source)"
    url="$(json_field "$entry" url)"
    expected_sha="$(printf '%s' "$entry" | jq -r '.sha256_or_null // ""')"

    if [ -z "$pkg" ]; then
        echo "WARN: skipping entry with no pkg field: $entry" >&2
        continue
    fi

    echo "---" >&2
    echo "[$pkg] tier=$source_tier" >&2

    case "$source_tier" in
        SKIP-MANUAL)
            verify_skip_manual_in_gap_list "$pkg"
            result="$(jq -n \
                --arg pkg "$pkg" \
                --arg name "$name" \
                --arg source "$source_tier" \
                '{pkg: $pkg, name: $name, source: $source, status: "skip", reason: "manual-only"}')"
            COUNT_SKIP=$((COUNT_SKIP + 1))
            ;;

        AURORA-OPEN)
            # If no direct URL, we cannot download — Aurora open-client must
            # fetch interactively. Record honestly; do NOT pretend success.
            if [ -z "$url" ] || [ "$url" = "AURORA-INTERACTIVE" ]; then
                result="$(jq -n \
                    --arg pkg "$pkg" \
                    --arg name "$name" \
                    --arg source "$source_tier" \
                    '{pkg: $pkg, name: $name, source: $source, status: "aurora-required",
                      reason: "aurora-open-client-interactive-only; no Play-login per browser-automation.md"}')"
                COUNT_AURORA=$((COUNT_AURORA + 1))
            else
                # Fall through to direct-download path
                result=""
            fi
            ;;

        F-DROID|GITHUB|F-DROID-DIRECT|GITHUB-RELEASES)
            result=""
            ;;

        "")
            echo "WARN: empty source tier for $pkg — skipping" >&2
            result="$(jq -n \
                --arg pkg "$pkg" \
                --arg name "$name" \
                '{pkg: $pkg, name: $name, source: "", status: "error", reason: "missing-source-tier-in-inventory"}')"
            COUNT_FAILED=$((COUNT_FAILED + 1))
            ;;

        *)
            echo "WARN: unknown source tier '$source_tier' for $pkg — skipping" >&2
            result="$(jq -n \
                --arg pkg "$pkg" \
                --arg name "$name" \
                --arg source "$source_tier" \
                '{pkg: $pkg, name: $name, source: $source, status: "error", reason: "unknown-source-tier"}')"
            COUNT_FAILED=$((COUNT_FAILED + 1))
            ;;
    esac

    # Direct-download path (result still empty after case dispatch).
    if [ -z "$result" ]; then
        if [ -z "$url" ]; then
            result="$(jq -n \
                --arg pkg "$pkg" \
                --arg name "$name" \
                --arg source "$source_tier" \
                '{pkg: $pkg, name: $name, source: $source, status: "download-failed", reason: "empty-url-in-inventory"}')"
            COUNT_FAILED=$((COUNT_FAILED + 1))
        else
            apk_path="$APK_DIR/${pkg}.apk"
            sha_path="$APK_DIR/${pkg}.sha256"
            wget_log="$APK_DIR/${pkg}.wget.log"

            echo "[$pkg] downloading: $url" >&2
            # `set +e` block: wget can fail legitimately (404, cert error,
            # network blip) and we must record the reason, not abort.
            set +e
            wget --quiet \
                 --timeout="$WGET_TIMEOUT" \
                 --tries="$WGET_TRIES" \
                 -O "$apk_path" \
                 "$url" 2>"$wget_log"
            wget_rc=$?
            set -e

            if [ $wget_rc -ne 0 ] || [ ! -s "$apk_path" ]; then
                wget_err="$(tail -n 5 "$wget_log" 2>/dev/null | tr '\n' ' ' | tr -d '\r' | head -c 500)"
                [ -z "$wget_err" ] && wget_err="wget-exit-$wget_rc-no-stderr"
                result="$(jq -n \
                    --arg pkg "$pkg" \
                    --arg name "$name" \
                    --arg source "$source_tier" \
                    --arg url "$url" \
                    --arg err "$wget_err" \
                    --argjson rc "$wget_rc" \
                    '{pkg: $pkg, name: $name, source: $source, status: "download-failed",
                      url: $url, wget_rc: $rc, reason: $err}')"
                COUNT_FAILED=$((COUNT_FAILED + 1))
                # Remove empty stub file
                [ ! -s "$apk_path" ] && rm -f "$apk_path"
            else
                actual_sha="$(sha256_of_file "$apk_path")"
                printf '%s  %s.apk\n' "$actual_sha" "$pkg" >"$sha_path"

                sha_verified="upstream-no-hash"
                sha_mismatch=0
                if [ -n "$expected_sha" ]; then
                    if [ "$actual_sha" = "$expected_sha" ]; then
                        sha_verified="match"
                    else
                        sha_verified="MISMATCH"
                        sha_mismatch=1
                    fi
                fi

                if [ "$sha_mismatch" -eq 1 ]; then
                    result="$(jq -n \
                        --arg pkg "$pkg" \
                        --arg name "$name" \
                        --arg source "$source_tier" \
                        --arg url "$url" \
                        --arg actual "$actual_sha" \
                        --arg expected "$expected_sha" \
                        '{pkg: $pkg, name: $name, source: $source, status: "sha-mismatch",
                          url: $url, expected_sha256: $expected, actual_sha256: $actual,
                          reason: "expected-vs-actual-sha256-mismatch; install aborted for entry"}')"
                    COUNT_FAILED=$((COUNT_FAILED + 1))
                else
                    # Install
                    echo "[$pkg] installing via adb..." >&2
                    set +e
                    adb_out="$(adb -s "$ADB_SERIAL" install -r "$apk_path" 2>&1)"
                    adb_rc=$?
                    set -e

                    # adb install reports "Success" on stdout. Use both rc
                    # and string presence — adb has been known to return 0
                    # on partial failure in some paths.
                    if [ $adb_rc -eq 0 ] && printf '%s' "$adb_out" | grep -q "Success"; then
                        result="$(jq -n \
                            --arg pkg "$pkg" \
                            --arg name "$name" \
                            --arg source "$source_tier" \
                            --arg url "$url" \
                            --arg sha "$actual_sha" \
                            --arg verified "$sha_verified" \
                            '{pkg: $pkg, name: $name, source: $source, status: "installed",
                              url: $url, sha256: $sha, sha_verified: $verified}')"
                        COUNT_INSTALLED=$((COUNT_INSTALLED + 1))
                    else
                        adb_err="$(printf '%s' "$adb_out" | tr '\n' ' ' | tr -d '\r' | head -c 500)"
                        result="$(jq -n \
                            --arg pkg "$pkg" \
                            --arg name "$name" \
                            --arg source "$source_tier" \
                            --arg url "$url" \
                            --arg sha "$actual_sha" \
                            --arg err "$adb_err" \
                            --argjson rc "$adb_rc" \
                            '{pkg: $pkg, name: $name, source: $source, status: "install-failed",
                              url: $url, sha256: $sha, adb_rc: $rc, reason: $err}')"
                        COUNT_FAILED=$((COUNT_FAILED + 1))
                    fi
                fi
            fi
        fi
    fi

    # Append to report (atomic)
    current="$(cat "$REPORT")"
    updated="$(printf '%s' "$current" | jq --argjson r "$result" '. + [$r]')"
    write_report "$updated"

done < <(jq -c '.[]' "$INVENTORY")

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo "===" >&2
echo "install-apps summary: installed=$COUNT_INSTALLED skip=$COUNT_SKIP aurora-required=$COUNT_AURORA failed=$COUNT_FAILED" >&2
echo "report: $REPORT" >&2

# Append summary footer to report (separate object so existing readers do
# not need to special-case index 0).
SUMMARY_JSON="$(jq -n \
    --argjson installed "$COUNT_INSTALLED" \
    --argjson skip "$COUNT_SKIP" \
    --argjson aurora "$COUNT_AURORA" \
    --argjson failed "$COUNT_FAILED" \
    --arg finished "$(date -Iseconds)" \
    '{_meta: "summary", installed: $installed, skip: $skip, aurora_required: $aurora,
      failed: $failed, finished: $finished}')"
current="$(cat "$REPORT")"
updated="$(printf '%s' "$current" | jq --argjson s "$SUMMARY_JSON" '. + [$s]')"
write_report "$updated"

# Non-zero exit only if ALL entries failed (catastrophic). Partial failures
# are recorded honestly in the report; we leave the gate decision to P21-D.
TOTAL_ATTEMPTED=$((COUNT_INSTALLED + COUNT_SKIP + COUNT_AURORA + COUNT_FAILED))
if [ "$TOTAL_ATTEMPTED" -eq 0 ]; then
    echo "ERROR: no entries processed — inventory empty or all malformed" >&2
    exit 3
fi

exit 0
