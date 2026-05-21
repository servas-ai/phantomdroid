#!/usr/bin/env bash
# Power-18 D2 Gate 2 — WeightedScore invariant on RedroidSpoofed.
#
# Asserts that `detection-cli replay-snapshot RedroidSpoofed` emits a JSON
# report whose `aggregate.weightedScore` field formats to exactly "0.0000"
# (the documented spoof-stack invariant: RedroidSpoofed must classify as
# CLEAN with zero critical failures across all 6 detector families).
#
# D1 DEPENDENCY:
#   The `replay-snapshot` subcommand is the Power-18 D1 deliverable
#   (detection-cli replay-snapshot SNAPSHOT_NAME). As of 2026-05-21 on
#   branch `report/CLO-143-weekly-W20` the CLI exposes:
#       run | validate | version | replay-snapshot
#   so this gate is ACTIVE. The skip-path below remains in place to
#   guard PRs that pre-date the D1 merge (e.g. branched from main
#   before D1 landed).
#
# Skip-condition (documented):
#   If `./gradlew :detection-cli:installDist` succeeds AND
#   `detection-cli --help` lists `replay-snapshot` as a subcommand, the
#   gate runs. Otherwise the gate emits a clear SKIP message naming D1
#   as the missing dependency and exits 0 so PRs are not blocked while
#   D1 is in flight. This is NOT a silent skip — the SKIP line is emitted
#   on stderr AND stdout with a stable token (`GATE-2-SKIP:D1-PENDING`)
#   so grep-based monitoring can alert.
#
# Exit codes:
#   0  PASS  weightedScore == 0.0000 OR D1-dependency missing (documented skip)
#   1  FAIL  weightedScore != 0.0000 (spoof-stack invariant broken)
#   2  ERROR replay-snapshot exists but failed to run / parse

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

CLI_BIN="${DETECTION_CLI_BIN:-./agents/detection-cli/build/install/detection-cli/bin/detection-cli}"

echo "Gate 2 — WeightedScore invariant (RedroidSpoofed aggregate.weightedScore == 0.0000)"
echo

# Probe for the replay-snapshot subcommand. We accept ANY of:
#   - Pre-installed binary at $CLI_BIN with `replay-snapshot` in its help
#   - `./gradlew :detection-cli:run --args="replay-snapshot --help"` succeeds
#
# If neither produces a `replay-snapshot` reference, emit SKIP.

probe_help() {
    if [ -x "$CLI_BIN" ] && "$CLI_BIN" --help 2>&1 | grep -q "replay-snapshot"; then
        return 0
    fi
    return 1
}

# Build the CLI install image if the binary is missing (CI path) — the
# `installDist` task is fast and deterministic; we prefer the installed
# binary over `gradle run` because the latter mixes gradle progress noise
# into stdout/stderr which breaks JSON parsing downstream.
if [ ! -x "$CLI_BIN" ]; then
    echo "detection-cli binary not present at $CLI_BIN; running :detection-cli:installDist..."
    if ! ./gradlew --no-daemon :detection-cli:installDist > /dev/null 2>&1; then
        echo "ERROR: ./gradlew :detection-cli:installDist failed; cannot run gate 2." >&2
        exit 2
    fi
fi

if ! probe_help; then
    # D1 not landed yet — replay-snapshot subcommand missing.
    echo "GATE-2-SKIP:D1-PENDING — detection-cli does not expose 'replay-snapshot' subcommand yet."
    echo "  D1 (Power-18) is the dependency that adds the subcommand. Gate 2 will activate"
    echo "  automatically once the subcommand ships and the CLI is rebuilt."
    echo "  Probed: $CLI_BIN"
    echo "  Exiting 0 (documented skip — NOT a silent pass)."
    exit 0
fi

# replay-snapshot exists — gate is live.
echo "detection-cli exposes 'replay-snapshot'; gate is active."
echo

JSON_OUT="$(mktemp)"
STDERR_OUT="$(mktemp)"
trap 'rm -f "$JSON_OUT" "$STDERR_OUT"' EXIT

# IMPORTANT: replay-snapshot writes a one-line human summary to stderr after
# the JSON body. We MUST keep stdout and stderr separate so jq parses only
# the JSON body. Merging streams with `2>&1` would corrupt the JSON.
if ! "$CLI_BIN" replay-snapshot RedroidSpoofed \
        > "$JSON_OUT" 2> "$STDERR_OUT"; then
    echo "ERROR: detection-cli replay-snapshot RedroidSpoofed exited non-zero." >&2
    echo "--- stderr ---" >&2
    cat "$STDERR_OUT" >&2
    echo "--- stdout (first 50 lines) ---" >&2
    head -50 "$JSON_OUT" >&2
    exit 2
fi

# Extract aggregate.weightedScore with jq.
if ! command -v jq > /dev/null 2>&1; then
    echo "ERROR: jq is required (apt-get install jq)." >&2
    exit 2
fi

SCORE_RAW="$(jq -r '.aggregate.weightedScore' "$JSON_OUT")"
if [ "$SCORE_RAW" = "null" ] || [ -z "$SCORE_RAW" ]; then
    echo "ERROR: .aggregate.weightedScore not found in JSON output." >&2
    head -50 "$JSON_OUT" >&2
    exit 2
fi

# Format to 4 decimal places for exact comparison (the invariant is "0.0000").
SCORE_FMT="$(printf '%.4f' "$SCORE_RAW")"
echo "RedroidSpoofed aggregate.weightedScore = $SCORE_FMT (raw: $SCORE_RAW)"

if [ "$SCORE_FMT" = "0.0000" ]; then
    echo "PASS"
    exit 0
fi

echo "FAIL: spoof-stack invariant broken — RedroidSpoofed weightedScore is $SCORE_FMT, expected 0.0000." >&2
echo "  This means at least one of the 6 detector families fired on the RedroidSpoofed fixture." >&2
echo "  See audit/spoof-stack/power-17-reviewer-signoff.md §2.6 for the test invariant." >&2
exit 1
