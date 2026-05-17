#!/usr/bin/env bash
# CLO-141/CLO-199 acceptance test suite for Orchestrator role-lane refusal.
#
# Must exit 0 when the Orchestrator pre-checkout guard refuses cross-lane
# probe implementation work with reason "lane-violation".
set -euo pipefail

PASS=0
FAIL=0

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHECK="${SCRIPT_DIR}/check-role-lane.sh"

_ok() {
  echo "  PASS: $1"
  PASS=$((PASS + 1))
}

_fail() {
  echo "  FAIL: $1"
  FAIL=$((FAIL + 1))
}

_run_check() {
  local role="$1" title="$2" description="$3" out_file="$4"
  set +e
  bash "${CHECK}" --role "${role}" --title "${title}" --description "${description}" >"${out_file}" 2>&1
  local rc=$?
  set -e
  return "${rc}"
}

_assert_refused_lane_violation() {
  local label="$1" role="$2" title="$3" description="$4"
  local out
  out="$(mktemp)"
  if _run_check "${role}" "${title}" "${description}" "${out}"; then
    _fail "${label} (expected refusal, got allow)"
  elif grep -q 'lane-violation' "${out}"; then
    _ok "${label}"
  else
    _fail "${label} (missing lane-violation reason: $(tr '\n' ' ' <"${out}"))"
  fi
  rm -f "${out}"
}

_assert_allowed() {
  local label="$1" role="$2" title="$3" description="$4"
  local out
  out="$(mktemp)"
  if _run_check "${role}" "${title}" "${description}" "${out}"; then
    _ok "${label}"
  else
    _fail "${label} (unexpected refusal: $(tr '\n' ' ' <"${out}"))"
  fi
  rm -f "${out}"
}

echo ""
echo "=== T1: required synthetic Orchestrator probe-impl case ==="
_assert_refused_lane_violation \
  "orchestrator picks probe-impl is rejected with lane-violation" \
  "orchestrator" \
  "G-PROBE-401 :: implement env.location_mock probe" \
  "Add Kotlin implementation under agents/detection/src/probes/env/LocationMockProbe.kt"

echo ""
echo "=== T2: Orchestrator cannot write other-agent src lanes ==="
_assert_refused_lane_violation \
  "orchestrator cannot write agents/detection/src" \
  "orchestrator" \
  "repair ProbeRunner" \
  "Touch agents/detection/src/core/ProbeRunner.kt"

_assert_refused_lane_violation \
  "orchestrator cannot write agents/stability/src" \
  "orchestrator" \
  "implement stability runner" \
  "Add agents/stability/src/container_lifecycle.py"

echo ""
echo "=== T3: Orchestrator-owned work remains allowed ==="
_assert_allowed \
  "orchestrator may work under agents/orchestrator/src" \
  "orchestrator" \
  "implement journal aggregation" \
  "Add agents/orchestrator/src/journal.py"

_assert_allowed \
  "orchestrator may coordinate probe sub-issues without implementing probes" \
  "orchestrator" \
  "open probe-this-container child issue" \
  "Create Detection issue blocked by build-container; do not edit probe code."

echo ""
echo "=== T4: Detection lane accepts probe implementation ==="
_assert_allowed \
  "detection may implement probe code" \
  "detection" \
  "G-PROBE-401 :: implement env.location_mock probe" \
  "Add Kotlin implementation under agents/detection/src/probes/env/LocationMockProbe.kt"

echo ""
echo "role-lane acceptance summary: PASS=${PASS} FAIL=${FAIL}"
if [ "${FAIL}" -ne 0 ]; then
  exit 1
fi
