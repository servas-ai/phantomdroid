#!/usr/bin/env bash
# lint-issue-acceptance.sh — CLO-109 v1 reference implementation
#
# Validates that every open tier-2 / tier-3 issue carries a well-formed
# acceptance block, per acceptance-block-spec.md §3.
#
# Modes:
#   default                   lint all open T2/T3 issues; exit 0 if all
#                             conform, exit 1 if any are missing/malformed.
#   --issue <id>              lint a single issue by identifier (CLO-109)
#                             or UUID; same exit semantics for that issue.
#   --self-test               assert a synthetic T2 issue without an
#                             acceptance block is rejected (exit 1 reason
#                             "no-acceptance-block"). Wraps lint in a tmp.
#
# Requires: paperclipai CLI on PATH, jq, python3 (for YAML parsing).
# Reads:    PAPERCLIP_COMPANY_ID from env (defaults to active CLI context).
#
# Owner: Orchestrator (CLO-109).

set -euo pipefail
umask 077

REQUIRED_FIELDS=(cmd expect_exit runs_on timeout_sec)
ALLOWED_RUNS_ON=(ci-linux-x86_64 ci-linux-arm64 dev-host-only redroid-sandbox online-net-live)

err() { printf '%s\n' "$*" >&2; }

# Extract YAML acceptance block(s) from a markdown description.
# Emits one JSON object per acceptance block found on stdout
# (compact form, one per line). Returns nothing if no block found.
extract_acceptance() {
  local desc="$1"
  PYTHONIOENCODING=utf-8 python3 -c '
import json, re, sys
try:
    import yaml
except ImportError:
    print("FATAL: PyYAML required (apt install python3-yaml)", file=sys.stderr)
    sys.exit(2)

text = sys.stdin.read()
fence_re = re.compile(
    r"^```(?P<info>yaml(?:\s+acceptance)?)\s*\n(?P<body>.*?)^```",
    re.DOTALL | re.MULTILINE,
)
for m in fence_re.finditer(text):
    info = m.group("info").strip()
    body = m.group("body")
    try:
        parsed = yaml.safe_load(body)
    except yaml.YAMLError as e:
        print(json.dumps({"_error": f"yaml_parse_error: {e}", "_info": info}))
        continue
    if info == "yaml acceptance":
        obj = parsed
    elif isinstance(parsed, dict) and "acceptance" in parsed:
        obj = parsed["acceptance"]
    else:
        continue
    if not isinstance(obj, dict):
        print(json.dumps({"_error": "acceptance is not a mapping"}))
        continue
    print(json.dumps(obj))
' <<<"$desc"
}

# Validate a parsed acceptance object against §3 schema.
# Emits one reason string per violation. Empty stdout = conforming.
validate_acceptance_obj() {
  local obj_json="$1"
  python3 -c '
import json, sys
obj = json.loads(sys.argv[1])
if "_error" in obj:
    print(obj["_error"])
    sys.exit(0)

required = ["cmd", "expect_exit", "runs_on", "timeout_sec"]
allowed_runs_on = {
    "ci-linux-x86_64", "ci-linux-arm64", "dev-host-only",
    "redroid-sandbox", "online-net-live",
}

for k in required:
    if k not in obj:
        print(f"missing-required-field:{k}")

cmd = obj.get("cmd")
if cmd is not None:
    if not isinstance(cmd, str):
        print("cmd-not-string")
    elif len(cmd) > 512:
        print(f"cmd-too-long:{len(cmd)}")
    elif not cmd.strip():
        print("cmd-empty")

ex = obj.get("expect_exit")
if ex is not None and not (isinstance(ex, int) and 0 <= ex <= 255):
    print(f"expect_exit-out-of-range:{ex}")

ro = obj.get("runs_on")
if ro is not None and ro not in allowed_runs_on:
    print(f"runs_on-not-in-enum:{ro}")

ts = obj.get("timeout_sec")
if ts is not None and not (isinstance(ts, int) and 1 <= ts <= 3600):
    print(f"timeout_sec-out-of-range:{ts}")

jq = obj.get("expect_json_jq", "_unset")
if jq != "_unset" and jq is not None and not isinstance(jq, str):
    print("expect_json_jq-not-string-or-null")
' "$obj_json"
}

lint_one_issue() {
  local identifier="$1"
  local issue_json
  issue_json=$(paperclipai issue get "$identifier" --json 2>/dev/null) || {
    printf '%s\tfetch-failed\n' "$identifier"
    return 1
  }

  local labels tier description
  labels=$(jq -r '[.labels[]?.name] | join(",")' <<<"$issue_json")
  description=$(jq -r '.description // ""' <<<"$issue_json")

  if   [[ ",$labels," == *,tier-3,* ]]; then tier=tier-3
  elif [[ ",$labels," == *,tier-2,* ]]; then tier=tier-2
  elif [[ ",$labels," == *,tier-1,* ]]; then tier=tier-1
  else tier=untiered
  fi

  if [[ "$tier" != "tier-2" && "$tier" != "tier-3" ]]; then
    return 0
  fi

  local blocks
  blocks=$(extract_acceptance "$description") || true
  if [[ -z "$blocks" ]]; then
    printf '%s\t%s\tno-acceptance-block\n' "$identifier" "$tier"
    return 1
  fi

  local block_count=0 violations=0
  while IFS= read -r obj_json; do
    [[ -z "$obj_json" ]] && continue
    block_count=$((block_count + 1))
    local reasons
    reasons=$(validate_acceptance_obj "$obj_json")
    if [[ -n "$reasons" ]]; then
      while IFS= read -r r; do
        printf '%s\t%s\t%s\n' "$identifier" "$tier" "$r"
        violations=$((violations + 1))
      done <<<"$reasons"
    fi
  done <<<"$blocks"

  if (( block_count > 1 )); then
    printf '%s\t%s\tmultiple-acceptance-blocks:%d\n' "$identifier" "$tier" "$block_count"
    violations=$((violations + 1))
  fi

  return $(( violations > 0 ? 1 : 0 ))
}

lint_all_open() {
  : "${PAPERCLIP_COMPANY_ID:?PAPERCLIP_COMPANY_ID must be set}"
  local list_json
  list_json=$(paperclipai issue list \
    -C "$PAPERCLIP_COMPANY_ID" \
    --status todo,in_progress,in_review,blocked \
    --json)
  local total_violations=0 total_issues=0 conforming=0
  while IFS= read -r identifier; do
    total_issues=$((total_issues + 1))
    if ! lint_one_issue "$identifier"; then
      total_violations=$((total_violations + 1))
    else
      conforming=$((conforming + 1))
    fi
  done < <(jq -r '.[].identifier' <<<"$list_json")
  err "lint-issue-acceptance: scanned=${total_issues} conforming=${conforming} violations=${total_violations}"
  return $(( total_violations > 0 ? 1 : 0 ))
}

self_test() {
  # Synthetic T2 issue with no acceptance block; lint_one_issue uses the
  # Paperclip API, so we stub it via a fake jq pipeline.
  local synthetic
  synthetic=$(cat <<'JSON'
{"identifier":"CLO-FAKE","labels":[{"name":"tier-2"}],"description":"No acceptance here"}
JSON
)
  local blocks
  blocks=$(extract_acceptance "$(jq -r .description <<<"$synthetic")")
  if [[ -n "$blocks" ]]; then
    err "self-test FAIL: expected no acceptance block, found: $blocks"
    return 1
  fi
  err "self-test PASS: synthetic T2 without acceptance block correctly rejected."
  return 0
}

main() {
  case "${1:-}" in
    --self-test) self_test; exit $?;;
    --issue)
      [[ -n "${2:-}" ]] || { err "--issue requires an identifier"; exit 2; }
      lint_one_issue "$2"; exit $?;;
    "") lint_all_open; exit $?;;
    *)  err "unknown arg: $1"; exit 2;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
