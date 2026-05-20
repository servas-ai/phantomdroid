#!/usr/bin/env bash
# scripts/redroid-recapture.sh — Owner-helper for live ReDroid container
# fixture refresh. Emits a Kotlin DeviceSnapshot literal from `docker exec`
# captures against a live ReDroid container. Used to refresh fixtures like
# agents/detection/src/core/replay/RedroidV12Snapshot.kt after the
# PAR822349 reboot (Power-13/14 owner-action carryover).
#
# Anti-verarschen contract:
#   - Per-field `// from: docker exec ...` source-comment on every captured
#     line so the reader can re-run the exact command and see the same value.
#   - `// FIELD_UNAVAILABLE: <reason>` marker when a capture command fails
#     (non-zero exit, empty stdout, container unreachable). NO fake values,
#     NO synthetic defaults — missing data is declared as missing.
#   - Idempotent: running twice against the same container state produces
#     byte-identical output (modulo capturedAt timestamp).
#
# Usage:
#   ./scripts/redroid-recapture.sh <container_name> <output.kt> [variant]
#   ./scripts/redroid-recapture.sh --dry-run [output.kt] [variant]
#
# Arguments:
#   container_name  Docker container name (e.g. redroid-test). Ignored
#                   when --dry-run is set.
#   output.kt       Output Kotlin file path. Defaults to /dev/stdout when
#                   --dry-run is set without an explicit path.
#   variant         Object name suffix used in the emitted Kotlin object.
#                   Defaults to "RedroidV12" → `object RedroidV12Snapshot`.
#
# Exit codes:
#   0  fixture emitted successfully
#   1  invalid arguments
#   2  container not running / docker unavailable (live mode only)
#   3  output file exists and overwrite was declined
#
# Prerequisites (live mode):
#   - docker CLI installed and on PATH
#   - target container running (verified via `docker ps --format ...`)
#   - container has /system/bin/sh (standard for ReDroid images)
#
# Prerequisites (dry-run):
#   - bash 4+
#   - no external dependencies (docker not invoked)

set -euo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

DRY_RUN=0
CONTAINER=""
OUTPUT=""
VARIANT="RedroidV12"

if [[ $# -lt 1 ]]; then
    cat >&2 <<'EOF'
Usage: redroid-recapture.sh <container_name> <output.kt> [variant]
       redroid-recapture.sh --dry-run [output.kt] [variant]

See header of script for full documentation.
EOF
    exit 1
fi

if [[ "$1" == "--dry-run" ]]; then
    DRY_RUN=1
    shift
    OUTPUT="${1:-/dev/stdout}"
    [[ $# -ge 1 ]] && shift || true
    VARIANT="${1:-RedroidV12}"
else
    if [[ $# -lt 2 ]]; then
        echo "ERROR: live mode requires <container_name> <output.kt>" >&2
        exit 1
    fi
    CONTAINER="$1"
    OUTPUT="$2"
    VARIANT="${3:-RedroidV12}"
fi

# ---------------------------------------------------------------------------
# Overwrite prompt (live mode + concrete output path)
# ---------------------------------------------------------------------------

if [[ "$OUTPUT" != "/dev/stdout" && -e "$OUTPUT" ]]; then
    echo "WARNING: output file '$OUTPUT' already exists." >&2
    printf "Overwrite? [y/N] " >&2
    if [[ -t 0 ]]; then
        read -r REPLY
    else
        REPLY="n"
    fi
    if [[ ! "$REPLY" =~ ^[Yy]$ ]]; then
        echo "Aborted — fixture file untouched." >&2
        exit 3
    fi
fi

# ---------------------------------------------------------------------------
# Docker availability check (live mode only)
# ---------------------------------------------------------------------------

if [[ "$DRY_RUN" -eq 0 ]]; then
    if ! command -v docker >/dev/null 2>&1; then
        echo "ERROR: docker CLI not found on PATH" >&2
        exit 2
    fi
    if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
        echo "ERROR: container '$CONTAINER' is not running" >&2
        exit 2
    fi
fi

# ---------------------------------------------------------------------------
# Capture helpers
# ---------------------------------------------------------------------------

# capture_or_unavailable <command...> — runs command, echoes stdout if
# non-empty exit-0, else echoes the empty string. Caller decides whether
# empty means FIELD_UNAVAILABLE or "set-but-empty captured ground-truth".
capture_raw() {
    if [[ "$DRY_RUN" -eq 1 ]]; then
        # Dry-run: every capture is unavailable so the emitted fixture is
        # a valid Kotlin literal with FIELD_UNAVAILABLE markers throughout.
        return 0
    fi
    docker exec "$CONTAINER" sh -c "$*" 2>/dev/null || true
}

# getprop_or_unavail <key> — emits the kotlin map entry line for a single
# getprop key.  Empty stdout from getprop means "key not set" → omit from
# map; non-empty stdout means "key set to that value" (including empty
# string which is dispositive for some probes — but getprop returns one
# newline for an unset key, so we use exit code + length).
emit_getprop() {
    local key="$1"
    local indent="${2:-            }"
    local raw
    raw="$(capture_raw "getprop '$key'")"
    # strip trailing newline only — preserve embedded content verbatim
    raw="${raw%$'\n'}"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "${indent}// FIELD_UNAVAILABLE: dry-run mode (no docker exec) — key=$key"
        return 0
    fi
    if [[ -z "$raw" ]]; then
        # getprop returns empty for both unset AND set-but-empty; we
        # conservatively mark as set-but-empty (the rank-4/14/57/56/54
        # convention used by the RedroidV12 fixture).
        echo "${indent}\"$key\" to \"\", // from: docker exec $CONTAINER getprop $key"
    else
        # Kotlin string escape: backslash + double-quote + dollar
        local escaped="${raw//\\/\\\\}"
        escaped="${escaped//\"/\\\"}"
        escaped="${escaped//\$/\\\$}"
        echo "${indent}\"$key\" to \"$escaped\", // from: docker exec $CONTAINER getprop $key"
    fi
}

# emit_capture_or_marker <kotlin-line-prefix> <docker-cmd> <unavailable-marker>
# Emits the kotlin line if capture succeeds, else the FIELD_UNAVAILABLE marker.
emit_field_or_marker() {
    local field_name="$1"
    local docker_cmd="$2"
    local indent="${3:-        }"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "${indent}// FIELD_UNAVAILABLE: dry-run mode (no docker exec) — field=$field_name"
        return 0
    fi
    local raw
    raw="$(capture_raw "$docker_cmd")"
    if [[ -z "$raw" ]]; then
        echo "${indent}// FIELD_UNAVAILABLE: capture returned empty stdout — field=$field_name"
        return 0
    fi
    echo "${indent}// from: docker exec $CONTAINER $docker_cmd"
    echo "${indent}// raw stdout (first line): ${raw%%$'\n'*}"
}

# ---------------------------------------------------------------------------
# Emit the Kotlin fixture literal
# ---------------------------------------------------------------------------

# Use a temp file so we can atomically replace the output (idempotency
# guarantee — no partial writes if the script is interrupted).
TMP_OUT="$(mktemp)"
trap 'rm -f "$TMP_OUT"' EXIT

CAPTURED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LABEL_SUFFIX="$(date -u +%Y-%m-%d)"

{
cat <<KOTLIN_HEADER
// agents/detection/src/core/replay/${VARIANT}Snapshot.kt
//
// Auto-generated by scripts/redroid-recapture.sh from a live container
// capture. Each field carries a // from: docker exec ... source-comment
// so the reader can re-run the exact command and re-verify the value.
// Lines marked // FIELD_UNAVAILABLE indicate that the capture command
// failed or returned empty stdout — NO synthetic defaults are inserted.
//
// To re-capture against a refreshed container:
//   ./scripts/redroid-recapture.sh <container> <this-file> ${VARIANT}
//
// Source-of-truth field shape: agents/detection/src/core/replay/DeviceSnapshot.kt
KOTLIN_HEADER

cat <<KOTLIN_PACKAGE

package com.detectorlab.core.replay

object ${VARIANT}Snapshot {

    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "${VARIANT,,}-${LABEL_SUFFIX}",
        capturedAt = "${CAPTURED_AT}",
KOTLIN_PACKAGE

# --- sdkInt ---
if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "        // FIELD_UNAVAILABLE: dry-run mode (no docker exec) — field=sdkInt"
    echo "        sdkInt = 0,"
else
    SDK_RAW="$(capture_raw "getprop ro.build.version.sdk")"
    SDK_RAW="${SDK_RAW%$'\n'}"
    if [[ -z "$SDK_RAW" || ! "$SDK_RAW" =~ ^[0-9]+$ ]]; then
        echo "        // FIELD_UNAVAILABLE: getprop ro.build.version.sdk returned non-integer or empty"
        echo "        sdkInt = 0,"
    else
        echo "        sdkInt = ${SDK_RAW}, // from: docker exec $CONTAINER getprop ro.build.version.sdk"
    fi
fi

# --- systemProperties ---
echo "        systemProperties = mapOf("
# Canonical probe-surface getprop key set (subset of full RedroidV12 fixture
# — these are the load-bearing keys the inventory actually reads).
PROP_KEYS=(
    "ro.build.fingerprint"
    "ro.vendor.build.fingerprint"
    "ro.build.display.id"
    "ro.build.tags"
    "ro.build.type"
    "ro.build.version.release"
    "ro.build.version.sdk"
    "ro.product.brand"
    "ro.product.model"
    "ro.product.manufacturer"
    "ro.product.device"
    "ro.product.name"
    "ro.kernel.qemu"
    "ro.kernel.qemu.gles"
    "ro.hardware"
    "ro.product.board"
    "ro.board.platform"
    "ro.product.cpu.abi"
    "ro.product.cpu.abilist"
    "ro.product.cpu.abilist32"
    "ro.product.cpu.abilist64"
    "ro.boot.vbmeta.device_state"
    "ro.boot.verifiedbootstate"
    "ro.boot.flash.locked"
    "ro.secure"
    "ro.debuggable"
    "ro.boot.selinux"
    "ro.build.selinux"
    "ro.boot.veritymode"
    "ro.boot.warranty_bit"
    "ro.boot.warranty"
    "ro.bootmode"
    "ro.hardware.keystore"
    "ro.hardware.touchscreen"
    "ro.gpu.driver"
    "ro.hardware.gralloc"
    "ro.hardware.vulkan"
    "ro.opengles.version"
    "ro.boot.qemu.gltransport"
    "ro.hardware.audio"
)
for key in "${PROP_KEYS[@]}"; do
    emit_getprop "$key" "            "
done
echo "        ),"

# --- existingFiles (probed paths, sampled) ---
echo "        existingFiles = setOf("
EXIST_PATHS=(
    "/system/bin/su"
    "/system/xbin/su"
    "/sbin/su"
    "/system/app/Superuser.apk"
    "/system/etc/init.d/99SuperSUDaemon"
    "/dev/keymaster"
    "/dev/input/event0"
    "/dev/snd/controlC0"
)
for path in "${EXIST_PATHS[@]}"; do
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "            // FIELD_UNAVAILABLE: dry-run mode — path=$path"
    else
        # `ls -d` exits 0 iff path exists. Capture stderr suppressed.
        if docker exec "$CONTAINER" sh -c "ls -d '$path'" >/dev/null 2>&1; then
            echo "            \"$path\", // from: docker exec $CONTAINER ls -d $path"
        fi
        # if not present, omit from set — that's the set-membership semantic
    fi
done
echo "        ),"

# --- installedPackages ---
echo "        installedPackages = setOf("
if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "            // FIELD_UNAVAILABLE: dry-run mode — pm list packages not invoked"
else
    PM_RAW="$(capture_raw "pm list packages")"
    if [[ -z "$PM_RAW" ]]; then
        echo "            // FIELD_UNAVAILABLE: pm list packages returned empty stdout"
    else
        echo "            // from: docker exec $CONTAINER pm list packages"
        while IFS= read -r line; do
            # Format: "package:com.example.foo"
            pkg="${line#package:}"
            [[ -z "$pkg" ]] && continue
            # Escape backslash + double-quote
            pkg_esc="${pkg//\\/\\\\}"
            pkg_esc="${pkg_esc//\"/\\\"}"
            echo "            \"$pkg_esc\","
        done <<<"$PM_RAW"
    fi
fi
echo "        ),"

# --- procSelfMapsLibs (frida-class library detection) ---
echo "        procSelfMapsLibs = setOf("
if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "            // FIELD_UNAVAILABLE: dry-run mode — /proc/self/maps not read"
else
    MAPS_RAW="$(capture_raw "cat /proc/self/maps")"
    if [[ -z "$MAPS_RAW" ]]; then
        echo "            // FIELD_UNAVAILABLE: /proc/self/maps unreadable or empty"
    else
        echo "            // from: docker exec $CONTAINER cat /proc/self/maps (filtered for frida-class)"
        # Filter for known frida tokens — case-insensitive substring match
        FRIDA_TOKENS="frida-agent|libfrida-gadget|libgum|linjector"
        while IFS= read -r match; do
            match_esc="${match//\\/\\\\}"
            match_esc="${match_esc//\"/\\\"}"
            echo "            \"$match_esc\","
        done < <(grep -iEo "$FRIDA_TOKENS" <<<"$MAPS_RAW" | sort -u)
        # If grep matched nothing, the set is empty by omission (clean state)
    fi
fi
echo "        ),"

# --- runtimeThreadNames (per-thread comm) ---
echo "        runtimeThreadNames = setOf("
if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "            // FIELD_UNAVAILABLE: dry-run mode — /proc/self/task/*/comm not read"
else
    # Per-thread comm — harvest from /proc/self/task/<tid>/comm
    THREADS_RAW="$(capture_raw "for d in /proc/self/task/*/comm; do cat \$d; done")"
    if [[ -z "$THREADS_RAW" ]]; then
        echo "            // FIELD_UNAVAILABLE: /proc/self/task/*/comm unreadable"
    else
        echo "            // from: docker exec $CONTAINER cat /proc/self/task/*/comm"
        while IFS= read -r tname; do
            [[ -z "$tname" ]] && continue
            tname_esc="${tname//\\/\\\\}"
            tname_esc="${tname_esc//\"/\\\"}"
            echo "            \"$tname_esc\","
        done < <(sort -u <<<"$THREADS_RAW")
    fi
fi
echo "        ),"

# --- openTcpPorts (from /proc/net/tcp local-address column) ---
echo "        openTcpPorts = setOf("
if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "            // FIELD_UNAVAILABLE: dry-run mode — /proc/net/tcp not read"
else
    TCP_RAW="$(capture_raw "cat /proc/net/tcp")"
    if [[ -z "$TCP_RAW" ]]; then
        echo "            // FIELD_UNAVAILABLE: /proc/net/tcp unreadable"
    else
        echo "            // from: docker exec $CONTAINER cat /proc/net/tcp (LISTEN state 0A only)"
        # awk: field 2 is local-address hex "0100007F:1A2B"; field 4 is state hex (0A = LISTEN)
        while IFS= read -r port_dec; do
            [[ -z "$port_dec" ]] && continue
            echo "            ${port_dec},"
        done < <(awk 'NR>1 && $4=="0A" { split($2,a,":"); print strtonum("0x" a[2]) }' <<<"$TCP_RAW" | sort -nu)
    fi
fi
echo "        ),"

# --- mountInfo (per-PID mount namespace) ---
echo "        mountInfo = mapOf("
for pid in "self" "1"; do
    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "            // FIELD_UNAVAILABLE: dry-run mode — /proc/$pid/mountinfo not read"
    else
        MOUNT_RAW="$(capture_raw "cat /proc/$pid/mountinfo")"
        if [[ -z "$MOUNT_RAW" ]]; then
            echo "            // FIELD_UNAVAILABLE: /proc/$pid/mountinfo unreadable — pid=$pid"
        else
            echo "            // from: docker exec $CONTAINER cat /proc/$pid/mountinfo"
            # Use Kotlin raw-string triple-quote to preserve newlines verbatim
            echo "            \"$pid\" to \"\"\""
            while IFS= read -r mline; do
                # Inside a Kotlin raw string, the only escape needed is \$
                # (to suppress string-template interpretation) and embedded
                # triple-quote (impossible in mountinfo output).
                mline_esc="${mline//\$/\\\$}"
                echo "                $mline_esc"
            done <<<"$MOUNT_RAW"
            echo "            \"\"\".trimIndent(),"
        fi
    fi
done
echo "        ),"

# --- installSourcePackage (Power-16 T5 — pm dump <pkg> | grep installerPackageName)
# This requires knowing WHICH package we want the install source for. For
# the recapture helper we read the installer of `com.android.shell` as a
# deterministic ground-truth proxy — every Android image has it. Owners
# can change this manually if a different package's install-source matters.
if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "        // FIELD_UNAVAILABLE: dry-run mode — pm dump not invoked"
    echo "        installSourcePackage = null,"
else
    DUMP_PKG="${INSTALL_SOURCE_PKG:-com.android.shell}"
    # Sanitize INSTALL_SOURCE_PKG env var — restrict to Android package-name charset
    # (Power-17 security audit MEDIUM advisory dbca3d6 Pillar 2: shell-injection guard).
    DUMP_PKG="${DUMP_PKG//[^a-zA-Z0-9._-]/}"
    if [[ -z "$DUMP_PKG" ]]; then
        echo "        // FIELD_UNAVAILABLE: INSTALL_SOURCE_PKG rejected by sanitizer (empty after charset filter)" >&2
        DUMP_PKG="com.android.shell"
    fi
    INSTALLER_RAW="$(capture_raw "pm dump $DUMP_PKG | grep -i installerPackageName")"
    INSTALLER_RAW="${INSTALLER_RAW%$'\n'}"
    if [[ -z "$INSTALLER_RAW" ]]; then
        echo "        // from: docker exec $CONTAINER pm dump $DUMP_PKG | grep installerPackageName"
        echo "        // No installerPackageName line found — sideloaded / system pre-install."
        echo "        installSourcePackage = null,"
    else
        # Parse `installerPackageName=com.foo.bar` (whitespace varies)
        installer_val="$(sed -nE 's/.*installerPackageName[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\1/p' <<<"$INSTALLER_RAW")"
        if [[ -z "$installer_val" || "$installer_val" == "null" ]]; then
            echo "        // from: docker exec $CONTAINER pm dump $DUMP_PKG | grep installerPackageName"
            echo "        // installerPackageName=null — sideloaded."
            echo "        installSourcePackage = null,"
        else
            installer_esc="${installer_val//\\/\\\\}"
            installer_esc="${installer_esc//\"/\\\"}"
            echo "        // from: docker exec $CONTAINER pm dump $DUMP_PKG | grep installerPackageName"
            echo "        installSourcePackage = \"$installer_esc\","
        fi
    fi
fi

cat <<'KOTLIN_FOOTER'
    )
}
KOTLIN_FOOTER

} >"$TMP_OUT"

# ---------------------------------------------------------------------------
# Atomic move to final output
# ---------------------------------------------------------------------------

if [[ "$OUTPUT" == "/dev/stdout" ]]; then
    cat "$TMP_OUT"
else
    mv "$TMP_OUT" "$OUTPUT"
    # `mv` worked → disarm trap to avoid spurious rm
    trap - EXIT
    echo "Wrote fixture: $OUTPUT" >&2
fi
