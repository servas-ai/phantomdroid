# Power-17 Phase-C Security Audit

**Date**: 2026-05-21
**Auditor**: claude-sonnet-4-6 (security-audit role)
**Scope**: Branch `report/CLO-143-weekly-W20`, commits `c425280..b04f73c` (4 commits)
**Files audited**: `agents/`, `scripts/redroid-recapture.sh`, `audit/spoof-stack/`
**Verdict**: APPROVE

---

## Commit Map

| SHA | Tag | Description |
|---|---|---|
| `6fb4ad1` | C3 | `feat(scripts): Power-17 C3 — redroid-recapture.sh owner-helper` |
| `2bf9f09` | C2 | `docs(audit): Power-17 C2 — FP-rate analysis (23 FP-on-Clean classified)` |
| `c202ee8` | C1 | `test(detection): Power-17 C1 — MasterCompositeDetectorReplayTest (6-detector OR-union)` |
| `b04f73c` | C4 | `docs(audit): Power-17 C4 — production-hooks-spec P-12 audit` |

---

## Pillar 1 — Credentials Sweep

**Scope**: `git diff c425280..b04f73c -- agents/ scripts/ audit/`

**Method**: Regex scan for API keys (AKIA*, sk-*, ghp_*, glpat-*, xoxb-*), bearer tokens, hardcoded passwords, BEGIN RSA/EC PRIVATE KEY headers, credential-bearing URLs (scheme://user:pass@host), and internal hostnames.

**Findings**: None.

The only pattern matched was `"frida-agent"`, `"libfrida-gadget"`, `"libgum"`, `"linjector"` — these are detection-target token strings used as Frida-library pattern-match literals in `redroid-recapture.sh` lines 344/349. They are probe evidence-keys, not credentials. Also matched was `"com.koushikdutta.rommanager.license"` — a known package name used as a root-indicator, not a license string containing credentials.

No URL-embedded credentials found. No private key material found.

**Result**: PASS — 0 credential blockers.

---

## Pillar 2 — Shell Injection Audit (C3: redroid-recapture.sh)

**File**: `/home/coder/vk-repos/cloud-phone-research-planner/scripts/redroid-recapture.sh`

### 2.1 — set -euo pipefail

Line 44: `set -euo pipefail` — present, first executable line after the comment block.

PASS.

### 2.2 — Positional argument quoting ($1, $2, $3)

| Assignment | Line | Quoted? |
|---|---|---|
| `CONTAINER="$1"` | 76 | Yes — `"$1"` |
| `OUTPUT="$2"` | 77 | Yes — `"$2"` |
| `VARIANT="${3:-RedroidV12}"` | 78 | Yes — `"${3:-...}"` |

All three positional args are stored into named variables with double-quote enclosure at assignment. No bare `$1`/`$2`/`$3` appears in any execution context after the argument-parsing block.

PASS.

### 2.3 — docker exec invocations

Two `docker exec` call sites exist:

- Line 127: `docker exec "$CONTAINER" sh -c "$*" 2>/dev/null || true`
- Line 302: `docker exec "$CONTAINER" sh -c "ls -d '$path'" >/dev/null 2>&1`

In both, `"$CONTAINER"` is double-quoted — no word-splitting or glob-expansion risk on the container name argument to `docker exec`.

`$path` on line 302 comes from the hardcoded `EXIST_PATHS` array (lines 288–296): all values are static string literals. No user-provided path data reaches this variable.

PASS.

### 2.4 — eval / bash -c with user input

No `eval` statement found anywhere in the script.

`sh -c "$*"` on line 127 is used inside `capture_raw()`. All call sites to `capture_raw` pass hardcoded string literals:

| Call site | Argument | User-controlled? |
|---|---|---|
| Line 139 | `"getprop '$key'"` — $key from hardcoded PROP_KEYS array | No |
| Line 224 | `"getprop ro.build.version.sdk"` | No |
| Line 315 | `"pm list packages"` | No |
| Line 338 | `"cat /proc/self/maps"` | No |
| Line 361 | `"for d in /proc/self/task/*/comm; do cat \$d; done"` | No |
| Line 381 | `"cat /proc/net/tcp"` | No |
| Line 401 | `"cat /proc/$pid/mountinfo"` — $pid is loop var `"self"/"1"` | No |
| Line 431 | `"pm dump $DUMP_PKG \| grep -i installerPackageName"` | Partial — see 2.5 |

PASS (with one advisory, see 2.5).

### 2.5 — INSTALL_SOURCE_PKG environment variable (MEDIUM advisory, non-blocking)

Line 430: `DUMP_PKG="${INSTALL_SOURCE_PKG:-com.android.shell}"`
Line 431: `capture_raw "pm dump $DUMP_PKG | grep -i installerPackageName"`

`INSTALL_SOURCE_PKG` is an undocumented environment variable accepted by the script. It is not a positional argument and not mentioned in the usage block. Its value flows into `sh -c "pm dump $DUMP_PKG | grep ..."` without sanitization. A caller who sets `INSTALL_SOURCE_PKG='foo; rm -rf /tmp'` in their shell environment would achieve arbitrary command execution inside the container.

**Severity**: MEDIUM — requires local shell access to set environment variables; this is a developer-facing tool not exposed over a network boundary. The risk is local privilege-escalation within the container context.

**Recommended fix** (non-blocking, one line):
```bash
# After line 430, add:
DUMP_PKG="${DUMP_PKG//[^a-zA-Z0-9._-]/}"
```
Or use `printf '%q'` to quote the value before interpolation into `sh -c`.

This does not block the commit. It is documented as a WARN-level finding requiring a follow-up fix before the script is advertised as safe for use in CI contexts where `INSTALL_SOURCE_PKG` could be set by external callers.

### 2.6 — Output file path validation

`OUTPUT="$2"` (line 77) is validated in two ways:

1. Line 85: `[[ "$OUTPUT" != "/dev/stdout" && -e "$OUTPUT" ]]` — existence check triggers overwrite prompt
2. Line 186–188: `TMP_OUT="$(mktemp)"` with `trap 'rm -f "$TMP_OUT"' EXIT` — atomic write via temp file then `mv`

No directory traversal vector identified. `OUTPUT` is not used in any `docker exec` context — it is only used as the target of `mv "$TMP_OUT" "$OUTPUT"` (line 467). The `mv` target being user-supplied is expected and intentional.

PASS.

**Shell Injection Result**: WARN (1 medium advisory — INSTALL_SOURCE_PKG). No blockers.

---

## Pillar 3 — Test Fixture Leak (C1)

**Question**: Does `MasterCompositeDetectorReplayTest` appear in `agents/detection/src/main/`?

**Search result**: The class `MasterCompositeDetectorReplayTest` exists exclusively at:

```
agents/detection/src/test/kotlin/com/detectorlab/replay/detectorapps/MasterCompositeDetectorReplayTest.kt
```

The search across `agents/detection/src/main/` returned zero results. The class also appears in build-generated artifact paths (`build/reports/`, `build/test-results/`) which are build outputs, not source files.

Package `com.detectorlab.replay.detectorapps` confirms test namespace.

**Result**: PASS — test fixture correctly isolated to `src/test/`.

---

## Pillar 4 — Plan Immutability (C4)

**Question**: Does `git log --oneline c425280..b04f73c -- audit/spoof-stack/production-hooks-spec.md` show any commits?

**Result**: Command returned empty output — zero commits modified `production-hooks-spec.md` in the audited range.

C4 commit (`b04f73c`) commit message explicitly states: `"plan-immutability honored: spec NOT modified."` The only file changed by C4 is `audit/spoof-stack/power-17-production-hooks-audit.md` (a new audit artifact, not the spec itself).

**Result**: PASS — production-hooks-spec.md untouched. Plan immutability honored.

---

## Pillar 5 — fp-rate-analysis Probe ID Cross-Reference

**Question**: Do all 13 probe IDs named in `audit/spoof-stack/fp-rate-analysis.md` §1 exist in `shared/probes/inventory.yml`?

**Inventory size**: 82 probes (71 baseline + 11 A17 RASP additions, schemaVersion 2.0).

**Cross-reference results**:

| Probe ID | In inventory.yml? |
|---|---|
| `integrity.keystore_attestation` | FOUND (rank 6, severity: critical) |
| `runtime.debugger_tracerpid` | FOUND |
| `identity.android_id` | FOUND (rank 11, severity: high) |
| `identity.imei_serial` | FOUND |
| `identity.wifi_mac` | FOUND |
| `env.timezone_locale_mismatch` | FOUND |
| `identity.sim_iccid` | FOUND |
| `ui.screen_resolution` | FOUND |
| `env.language_country` | FOUND |
| `network.dns_server` | FOUND |
| `env.location_mock_rasp` | FOUND |
| `ui.system_fonts` | FOUND |
| `ui.input_method` | FOUND |

**Result**: PASS — 13/13 probe IDs verified against inventory. Zero fabricated identifiers.

---

## Pillar 6 — License Compliance

**Scope**: `scripts/redroid-recapture.sh` and all new files in `audit/spoof-stack/`.

**Method**: Scan diff for copyright headers, "All rights reserved", proprietary/confidential markers, or code blocks that could constitute reproduction beyond fair use.

**Findings**:

- `redroid-recapture.sh`: No copyright header. Script is original — uses standard POSIX/bash constructs (`docker exec`, `getprop`, `pm list packages`, `awk`, `mktemp`). These are standard system commands, not reproduced copyrighted code.
- `audit/spoof-stack/fp-rate-analysis.md`: Research document, original authorship. References `full-coverage-matrix.md` and `power-15-reviewer-signoff.md` (internal artifacts). No third-party copyrighted content reproduced.
- `audit/spoof-stack/power-17-production-hooks-audit.md`: Audit document, original authorship.
- One matched line (`"com.koushikdutta.rommanager.license"`) is a package identifier string used as a root-detection signal — a well-established fair-use pattern in security tooling.

**Result**: PASS — no license compliance issues identified.

---

## Summary

| Pillar | Status | Detail |
|---|---|---|
| 1 — Credentials sweep | PASS | 0 blockers |
| 2 — Shell injection (C3) | WARN | 1 medium: INSTALL_SOURCE_PKG env-var unvalidated before sh -c interpolation |
| 3 — Test fixture leak (C1) | PASS | MasterCompositeDetectorReplayTest correctly in src/test/ only |
| 4 — Plan immutability (C4) | PASS | production-hooks-spec.md unmodified in commit range |
| 5 — Probe ID fabrication | PASS | 13/13 probe IDs verified in inventory.yml |
| 6 — License compliance | PASS | No violations |

**CRITICAL blockers**: 0
**HIGH blockers**: 0
**MEDIUM advisories**: 1 (INSTALL_SOURCE_PKG — follow-up fix required, non-blocking)
**LOW / INFO**: 0

**Overall verdict**: APPROVE

The single MEDIUM advisory (Pillar 2.5) must be addressed in a follow-up commit before `redroid-recapture.sh` is used in any CI context where environment variables can be injected by external callers. It does not block the current branch because the script is a developer-local fixture-refresh tool and the risk requires pre-existing local shell access.
