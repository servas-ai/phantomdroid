#!/usr/bin/env bash
# install-apps-ext.sh — Power-21 EXTENSION installer
#
# Reads scripts/p21/app-inventory-ext.json (18 apps that P21-A1 marked
# AURORA-OPEN or install-failed) and installs them via APKPure direct
# (d.apkpure.net) or vendor-direct URLs found by p21-ext-researcher.
#
# Owner explicitly waived the strict primary-source-only filter from
# P21-A1 ("die apk gibt eh auf wsbtien").
#
# Safety:
#   - umask 077; set -euo pipefail; quoted vars; atomic mv writes
#   - User-Agent set to Android-Chrome to satisfy APKPure CDN
#   - --max-redirect=10 follows 302→winudf.com
#   - XAPK entries: unzip + adb install-multiple (or base-only fallback)
umask 077
set -euo pipefail

INVENTORY="${INVENTORY:-scripts/p21/app-inventory-ext.json}"
APK_DIR="${APK_DIR:-p21/apks}"
REPORT="${REPORT:-p21/install-report-ext.json}"
ADB_SERIAL="${ADB_SERIAL:-172.17.0.2:5555}"
UA="${UA:-Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36}"
[ -f "$INVENTORY" ] || { echo "ERROR: $INVENTORY not found" >&2; exit 1; }
mkdir -p "$APK_DIR"

adb connect "$ADB_SERIAL" >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" wait-for-device

started=$(date -Iseconds)
TMP_REPORT=$(mktemp -p p21 install-report-ext.json.XXXXXX)
printf '[\n  {"_meta":"adb-sanity","serial":"%s","started":"%s"}' "$ADB_SERIAL" "$started" > "$TMP_REPORT"

installed=0; failed=0; xapk_installed=0

while IFS= read -r entry; do
    pkg=$(echo "$entry" | jq -r '.pkg')
    name=$(echo "$entry" | jq -r '.name')
    source_tier=$(echo "$entry" | jq -r '.source')
    url=$(echo "$entry" | jq -r '.url')
    is_xapk="no"
    case "$url" in
        *"/b/XAPK/"*) is_xapk="yes" ;;
    esac

    out_file="$APK_DIR/${pkg}.${is_xapk//yes/xapk}"
    [ "$is_xapk" = "no" ] && out_file="$APK_DIR/${pkg}.apk"

    echo "=== $pkg ($source_tier${is_xapk:+ +xapk=$is_xapk}) ===" >&2

    # download
    if wget --quiet --max-redirect=10 -U "$UA" --timeout=120 --tries=2 -O "$out_file" "$url" 2>/dev/null; then
        actual_sha=$(sha256sum "$out_file" | awk '{print $1}')
        echo "$actual_sha  $out_file" > "$APK_DIR/${pkg}.sha256"
        actual_size=$(stat -c%s "$out_file")

        # detect HTML interstitial
        if [ "$actual_size" -lt 100000 ] && head -c 200 "$out_file" | grep -q "<html\|<!DOCTYPE" 2>/dev/null; then
            printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"html-interstitial","url":"%s","actual_sha256":"%s","size":%s,"reason":"downloaded HTML page, not APK"}' \
                "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
            failed=$((failed+1))
            rm -f "$out_file"
            continue
        fi

        # install — XAPK = extract + install-multiple; APK = direct
        if [ "$is_xapk" = "yes" ]; then
            xapk_dir=$(mktemp -d)
            if unzip -q "$out_file" -d "$xapk_dir" 2>&1; then
                # find all .apk inside (base + config splits)
                apk_files=("$xapk_dir"/*.apk)
                if [ -e "${apk_files[0]}" ]; then
                    # push to device and install-multiple
                    if adb_out=$(adb -s "$ADB_SERIAL" install-multiple "${apk_files[@]}" 2>&1); then
                        printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"installed","url":"%s","actual_sha256":"%s","size":%s,"variant":"xapk-multi"}' \
                            "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
                        installed=$((installed+1)); xapk_installed=$((xapk_installed+1))
                    else
                        # fallback: base-only
                        base_apk="$xapk_dir/${pkg}.apk"
                        if [ ! -f "$base_apk" ]; then
                            base_apk=$(find "$xapk_dir" -name "*.apk" ! -name "config.*" | head -1)
                        fi
                        if [ -n "$base_apk" ] && [ -f "$base_apk" ]; then
                            if adb_out=$(adb -s "$ADB_SERIAL" install -r "$base_apk" 2>&1); then
                                printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"installed","url":"%s","actual_sha256":"%s","size":%s,"variant":"xapk-base-only","fallback":true}' \
                                    "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
                                installed=$((installed+1)); xapk_installed=$((xapk_installed+1))
                            else
                                escaped_adb_out=$(echo "$adb_out" | jq -Rs .)
                                printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"install-failed","url":"%s","actual_sha256":"%s","size":%s,"variant":"xapk-base-only","reason":%s}' \
                                    "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" "$escaped_adb_out" >> "$TMP_REPORT"
                                failed=$((failed+1))
                            fi
                        else
                            printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"install-failed","url":"%s","actual_sha256":"%s","size":%s,"reason":"no .apk inside .xapk"}' \
                                "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
                            failed=$((failed+1))
                        fi
                    fi
                else
                    printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"install-failed","url":"%s","actual_sha256":"%s","size":%s,"reason":"xapk contained no .apk files"}' \
                        "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
                    failed=$((failed+1))
                fi
            else
                printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"install-failed","url":"%s","actual_sha256":"%s","size":%s,"reason":"xapk unzip failed"}' \
                    "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
                failed=$((failed+1))
            fi
            rm -rf "$xapk_dir"
        else
            # standard APK install
            if adb_out=$(adb -s "$ADB_SERIAL" install -r "$out_file" 2>&1); then
                printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"installed","url":"%s","actual_sha256":"%s","size":%s}' \
                    "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" >> "$TMP_REPORT"
                installed=$((installed+1))
            else
                escaped_adb_out=$(echo "$adb_out" | jq -Rs .)
                printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"install-failed","url":"%s","actual_sha256":"%s","size":%s,"reason":%s}' \
                    "$pkg" "$name" "$source_tier" "$url" "$actual_sha" "$actual_size" "$escaped_adb_out" >> "$TMP_REPORT"
                failed=$((failed+1))
            fi
        fi
    else
        printf ',\n  {"pkg":"%s","name":"%s","source":"%s","status":"download-failed","url":"%s","reason":"wget non-zero exit"}' \
            "$pkg" "$name" "$source_tier" "$url" >> "$TMP_REPORT"
        failed=$((failed+1))
    fi
done < <(jq -c '.[]' "$INVENTORY")

finished=$(date -Iseconds)
printf ',\n  {"_meta":"summary","installed":%d,"failed":%d,"xapk_installed":%d,"finished":"%s"}\n]\n' \
    "$installed" "$failed" "$xapk_installed" "$finished" >> "$TMP_REPORT"

mv "$TMP_REPORT" "$REPORT"
echo "summary: installed=$installed failed=$failed (xapk_installed=$xapk_installed)" >&2
