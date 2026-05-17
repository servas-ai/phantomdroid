#!/usr/bin/env bash
# Pre-checkout role-lane guard for Paperclip issue assignments.
#
# Refuses work whose title/description requires an agent to write outside its
# lane, and specifically keeps probe implementation work out of Orchestrator.
set -euo pipefail

ROLE=""
TITLE=""
DESCRIPTION=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --role)
      ROLE="${2:-}"
      shift 2
      ;;
    --title)
      TITLE="${2:-}"
      shift 2
      ;;
    --description)
      DESCRIPTION="${2:-}"
      shift 2
      ;;
    *)
      echo "usage: $0 --role <role> --title <issue-title> --description <issue-description>" >&2
      exit 64
      ;;
  esac
done

if [ -z "${ROLE}" ]; then
  echo "role-lane: missing --role" >&2
  exit 64
fi

ROLE="$(printf '%s' "${ROLE}" | tr '[:upper:]' '[:lower:]' | tr '_' '-')"
TEXT="$(printf '%s\n%s\n' "${TITLE}" "${DESCRIPTION}")"
LOWER_TEXT="$(printf '%s' "${TEXT}" | tr '[:upper:]' '[:lower:]')"

_lane_violation() {
  local detail="$1"
  echo "lane-violation: ${detail}" >&2
  exit 78
}

if printf '%s' "${LOWER_TEXT}" | grep -Eq 'agents/[^[:space:]/]+/src/'; then
  while IFS= read -r path; do
    [ -n "${path}" ] || continue
    owner="$(printf '%s' "${path}" | cut -d/ -f2)"
    if [ "${owner}" != "${ROLE}" ]; then
      _lane_violation "${ROLE} cannot accept write-scope ${path}; route to ${owner}"
    fi
  done <<EOF
$(printf '%s' "${LOWER_TEXT}" | grep -Eo 'agents/[^[:space:]/]+/src/[^[:space:])},;"]*' || true)
EOF
fi

if [ "${ROLE}" = "orchestrator" ]; then
  if printf '%s' "${LOWER_TEXT}" | grep -Eq '(^|[^a-z0-9])g-probe-[0-9]+'; then
    _lane_violation "probe implementation issues must route to detection"
  fi

  if printf '%s' "${LOWER_TEXT}" | grep -Eq '\b(implement|add|write|repair|fix|create|modify)[^.\n]{0,80}\bprobe\b'; then
    _lane_violation "probe implementation issues must route to detection"
  fi

  if printf '%s' "${LOWER_TEXT}" | grep -Eq '\bprobe implementation\b'; then
    _lane_violation "probe implementation issues must route to detection"
  fi
fi

echo "role-lane: allow"
