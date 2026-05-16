#!/usr/bin/env bash
# Auto-fill weekly-report.md from heatmap CSV + ceiling-status YAML.
# Usage: fill-weekly-report.sh <YYYY-WW> <PR_URL>
# Invoked as step 10 of the weekly-heatmap routine.
# Writes output to docs/super-action/W<N+1>/weekly-report.md.
umask 077

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ISO_WEEK="${1:?Usage: $0 <YYYY-WW> <PR_URL>}"
PR_URL="${2:?Usage: $0 <YYYY-WW> <PR_URL>}"

TEMPLATE="${REPO_ROOT}/docs/super-action/W7/skills/weekly-report.template.md"
CSV="${REPO_ROOT}/results/aggregate/all-configs.csv"
CEILING_STATUS="${REPO_ROOT}/docs/super-action/W7/skills/ceiling-status.yml"

if [[ ! -f "${TEMPLATE}" ]]; then
  echo "ERROR: template not found: ${TEMPLATE}" >&2
  exit 1
fi

# Derive next week number for output path
YEAR="${ISO_WEEK%%-W*}"
WEEK_NUM="${ISO_WEEK#*-W}"
NEXT_WEEK=$(( 10#${WEEK_NUM} + 1 ))
NEXT_WEEK_DIR="W${NEXT_WEEK}"
OUTPUT_DIR="${REPO_ROOT}/docs/super-action/${NEXT_WEEK_DIR}"
OUTPUT_FILE="${OUTPUT_DIR}/weekly-report.md"
mkdir -p "${OUTPUT_DIR}"

GENERATED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

# --- Extract metrics from CSV ---
CELLS_RUN=0
REGRESSIONS=0
NEW_CEILINGS_HIT=0
KPI_TABLE="_(no CSV found — run runner aggregate --all first)_"

if [[ -f "${CSV}" ]]; then
  # Count non-header rows as cells run
  CELLS_RUN=$(( $(wc -l < "${CSV}") - 1 ))
  [[ ${CELLS_RUN} -lt 0 ]] && CELLS_RUN=0

  # Regressions: rows where score decreased vs previous run (column "regression"=1 if present)
  if head -1 "${CSV}" | grep -qi "regression"; then
    REGRESSIONS=$(awk -F',' 'NR>1 && tolower($0) ~ /,1,|,1$/ {count++} END {print count+0}' "${CSV}")
  fi

  # New ceilings hit: rows with ceiling_hit=1 and first_seen=this week
  if head -1 "${CSV}" | grep -qi "ceiling_hit"; then
    NEW_CEILINGS_HIT=$(awk -F',' -v w="${ISO_WEEK}" \
      'NR>1 && $0 ~ w && tolower($0) ~ /ceiling_hit.*1|1.*ceiling_hit/ {count++} END {print count+0}' "${CSV}")
  fi

  # Build KPI markdown table from CSV header + last 5 rows (summary view)
  HEADER=$(head -1 "${CSV}")
  KPI_BODY=$(tail -5 "${CSV}")
  KPI_TABLE="$(printf '```\n%s\n%s\n```' "${HEADER}" "${KPI_BODY}")"
fi

# --- Read ceiling statuses ---
read_ceiling_status() {
  local num="$1"
  local default="open"
  if [[ -f "${CEILING_STATUS}" ]]; then
    local val
    val=$(grep -E "^  ${num}:" "${CEILING_STATUS}" | awk '{print $2}' | tr -d '"' | head -1)
    echo "${val:-${default}}"
  else
    echo "${default}"
  fi
}

read_ceiling_notes() {
  local num="$1"
  if [[ -f "${CEILING_STATUS}" ]]; then
    local val
    val=$(grep -A1 "^  ${num}:" "${CEILING_STATUS}" | grep "notes:" | sed 's/.*notes: //' | tr -d '"' | head -1)
    echo "${val:-}"
  fi
}

C1_STATUS=$(read_ceiling_status 1)
C2_STATUS=$(read_ceiling_status 2)
C3_STATUS=$(read_ceiling_status 3)
C4_STATUS=$(read_ceiling_status 4)
C5_STATUS=$(read_ceiling_status 5)
C6_STATUS=$(read_ceiling_status 6)
C7_STATUS=$(read_ceiling_status 7)
C8_STATUS=$(read_ceiling_status 8)
C9_STATUS=$(read_ceiling_status 9)

C1_NOTES=$(read_ceiling_notes 1)
C2_NOTES=$(read_ceiling_notes 2)
C3_NOTES=$(read_ceiling_notes 3)
C4_NOTES=$(read_ceiling_notes 4)
C5_NOTES=$(read_ceiling_notes 5)
C6_NOTES=$(read_ceiling_notes 6)
C7_NOTES=$(read_ceiling_notes 7)
C8_NOTES=$(read_ceiling_notes 8)
C9_NOTES=$(read_ceiling_notes 9)

# --- Render template (sed substitution) ---
sed \
  -e "s|{{iso_week}}|${ISO_WEEK}|g" \
  -e "s|{{generated_at}}|${GENERATED_AT}|g" \
  -e "s|{{cells_run}}|${CELLS_RUN}|g" \
  -e "s|{{regressions}}|${REGRESSIONS}|g" \
  -e "s|{{new_ceilings_hit}}|${NEW_CEILINGS_HIT}|g" \
  -e "s|{{pr_url}}|${PR_URL}|g" \
  -e "s|{{ceiling_1_status}}|${C1_STATUS}|g" \
  -e "s|{{ceiling_2_status}}|${C2_STATUS}|g" \
  -e "s|{{ceiling_3_status}}|${C3_STATUS}|g" \
  -e "s|{{ceiling_4_status}}|${C4_STATUS}|g" \
  -e "s|{{ceiling_5_status}}|${C5_STATUS}|g" \
  -e "s|{{ceiling_6_status}}|${C6_STATUS}|g" \
  -e "s|{{ceiling_7_status}}|${C7_STATUS}|g" \
  -e "s|{{ceiling_8_status}}|${C8_STATUS}|g" \
  -e "s|{{ceiling_9_status}}|${C9_STATUS}|g" \
  -e "s|{{ceiling_1_notes}}|${C1_NOTES}|g" \
  -e "s|{{ceiling_2_notes}}|${C2_NOTES}|g" \
  -e "s|{{ceiling_3_notes}}|${C3_NOTES}|g" \
  -e "s|{{ceiling_4_notes}}|${C4_NOTES}|g" \
  -e "s|{{ceiling_5_notes}}|${C5_NOTES}|g" \
  -e "s|{{ceiling_6_notes}}|${C6_NOTES}|g" \
  -e "s|{{ceiling_7_notes}}|${C7_NOTES}|g" \
  -e "s|{{ceiling_8_notes}}|${C8_NOTES}|g" \
  -e "s|{{ceiling_9_notes}}|${C9_NOTES}|g" \
  -e "s|{{notes}}||g" \
  "${TEMPLATE}" > "${OUTPUT_FILE}.tmp"

# kpi_table contains newlines — handle separately via python one-liner
python3 - <<PYEOF
import re, sys

with open("${OUTPUT_FILE}.tmp") as f:
    content = f.read()

kpi = """${KPI_TABLE}"""
content = content.replace("{{kpi_table}}", kpi)

with open("${OUTPUT_FILE}", "w") as f:
    f.write(content)
PYEOF

rm -f "${OUTPUT_FILE}.tmp"

echo "Written: ${OUTPUT_FILE}"
echo "  iso_week=${ISO_WEEK}  cells_run=${CELLS_RUN}  regressions=${REGRESSIONS}  new_ceilings_hit=${NEW_CEILINGS_HIT}"
