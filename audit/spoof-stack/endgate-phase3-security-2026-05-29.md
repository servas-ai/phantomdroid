# Security Endgate — Phase 3 (2026-05-29)

**Auditor:** ralph-security v3.0
**Date:** 2026-05-29
**Scope:** (1) Credential-scrub verification, (2) New-code audit (report_validator.py + aggregator.py + runner.py), (3) Live-access safety (docker --privileged gap vs L0a spec), (4) Research-boundary review of today's deliverables, (5) Phase 4 pre-flight assessment.

---

## LEAD VERIFICATION ADDENDUM (2026-05-29)
- **S-03 CORRECTED → NON-ISSUE**: the actual 2026-05-29 launch used `-p 127.0.0.1:5555:5555`. Verified live: `docker ps` → `127.0.0.1:5555->5555/tcp`; `ss -tlnp` → docker-proxy LISTEN on `127.0.0.1:5555` only; external TCP connect to `195.154.209.133:5555` FAILS. ADB is loopback-bound (agent read the stale 2026-05-20 `-p 5555:5555` command). No network exposure.
- **S-01 HIGH** confirmed + actioned: working-tree scrubbed (verified clean); owner to rotate `paris` pw + `git filter-repo` for git history.
- **S-02 MED** accepted: `--privileged` used deliberately to reach the first full binderfs boot; hardened L0a relaunch tracked as Phase-4 CONDITION-1.
- **S-06 BLOCKING** accepted: Phase 4 gated on L0b root-stack install (separate workstream).
- Code findings S-04/S-05 (path handling): LOW/informational, single-operator local model — accepted.
**Read-only — no live server access, no code modifications.**

---

## VERDICT: APPROVE-WITH-CONDITIONS

Phase 3 deliverables are approved to ship. Two conditions must be satisfied before Phase 4 execution begins; both are itemized in the findings table and remediation section below.

---

## Findings Table

| ID | Severity | Pillar | Finding | Status |
|---|---|---|---|---|
| S-01 | HIGH | SECRETS | Credential `<REDACTED-ssh-pw-see-.env>` persists in git history (commit 896cd71). Scrub confirmed effective in working tree and all tracked non-binary files; history rewrite has not been performed. | OPEN — requires owner-authorized `git filter-repo` or BFG + force-push |
| S-02 | MED | LIVE-ACCESS | The live `redroid-test` container on PAR822349 was originally launched (and likely re-launched for Phase 3) via `docker run --privileged -p 5555:5555` (port mapped to `0.0.0.0:5555`), NOT via the L0a-compliant compose stack. This contradicts L0a-RUNBOOK §2.4 and L0a.yml which mandate `cap_drop:[ALL] + seccomp + no-new-privileges + 127.0.0.1:15555 ADB binding`. | OPEN — must migrate before Phase 4 |
| S-03 | MED | LIVE-ACCESS | ADB port in Phase 3 operations was accessed as `127.0.0.1:5555` (loopback confirmed in apk-in-container-2026-05-29.md). However, the original container launch used `-p 5555:5555` which binds `0.0.0.0:5555` by default, exposing ADB to the network interface on PAR822349 for any period the container was running. The L0a spec requires `127.0.0.1:15555:5555`. | OPEN — must be corrected in Phase 4 launch |
| S-04 | LOW | CODE | `_parse_assignment()` in runner.py splits `--report PATH:DEVICE:OS` on `:` expecting exactly 3 parts. On Windows-style absolute paths (`C:\path`) or any path containing a literal colon, splitting produces more than 3 parts and the function rejects the input. On Linux (the lab host) this is a non-issue since Unix absolute paths do not contain colons. Noted as a portability defect, not an exploitable vulnerability in the current single-operator, local-only usage model. | INFORMATIONAL |
| S-05 | LOW | CODE | `write_cells_json()` in aggregator.py accepts an arbitrary `out_path` from `--out` on the CLI with no path-canonicalization or boundary check. A caller can write `cells.json` anywhere on the filesystem writable by the process. In the current single-operator model (local dev-VM + CI) this is acceptable; if the runner ever gains a network-facing API surface, this becomes HIGH. | INFORMATIONAL — note for future API surface |
| S-06 | INFO | PHASE4-BOUNDARY | Phase 4 (deploy cpuinfo-overlay + hide-frida-maps + spoof-stack-magisk, measure re-probe delta) requires an L0b root stack (Magisk + ReZygisk + LSPosed). The current `redroid-test` container is a plain ReDroid instance with no Magisk, ReZygisk, or LSPosed installed. Phase 4 cannot be executed against the current container without first building the L0b stack per `agents/stability/stack/L0b-RUNBOOK.md`. | BLOCKING for Phase 4 execution — not blocking Phase 3 ship |
| S-07 | INFO | RESEARCH-BOUNDARY | E2E-autonomous-plan-2026-05-29.md Phase 4 description ("deploy modules into the live container — DEFENSIVE lab measurement only") is correctly bounded. No document today crossed from defensive measurement into an operational bypass runbook. The `orchestrator-local-run-2026-05-29.md` uses "defensive research / not offensive bypass work" language explicitly. All p21/* deliverables are read-only observations. | PASS — boundary maintained |

---

## Detailed Findings

### S-01: Credential in Git History (HIGH)

**Evidence:** `git show 896cd71 | grep <REDACTED-ssh-pw-see-.env>` returns the string in the diff of `audit/track-a-reinstall-submitted-2026-05-19.md`. The working-tree scrub confirmed effective: `git ls-files --cached --others --exclude-standard | grep -v '\.class$' | xargs grep -lF '<REDACTED-ssh-pw-see-.env>'` exits 1 (no matches). The credential appears in exactly one commit in history (896cd71; confirmed via full log scan — 2 grep matches in `git show` output, both in the same commit diff).

**Risk:** The repo is private and hosted on the owner's server. Risk is low while the repo remains private, but the password remains a recoverable artifact via `git log -p` or any git clone. If the repo is ever published or the history is shared, the credential is immediately exposed. Additionally, the owner was advised to rotate the credential (password for user `paris` on PAR822349); if rotation has not occurred, the plaintext in history constitutes ongoing credential exposure.

**Remediation:** Owner must: (1) confirm password rotation on PAR822349 (user `paris`); (2) perform `git filter-repo --path audit/track-a-reinstall-submitted-2026-05-19.md --invert-paths` or equivalent BFG scrub, then force-push to origin and invalidate all other clones. This is a pre-Phase-4 requirement only if Phase 4 involves any action that expands who has access to git history; otherwise it is a standing remediation item.

---

### S-02 + S-03: Docker --privileged + ADB 0.0.0.0 Exposure (MED)

**Evidence:**
- `audit/E2E-validation-2026-05-20.md` line 28: `docker run -d --rm --name redroid-test --privileged -v /data/redroid-test:/data -p 5555:5555 redroid/redroid@sha256:...`
- `audit/Power-3-Closeout-2026-05-20.md`: same command documented.
- `audit/live-booted-sweep-2026-05-29.md`: "recreated 2026-05-29 20:50 CEST" — no compose file cited; container name is `redroid-test`, not the L0a compose name `stability-l0a-redroid`.
- The L0a-RUNBOOK.md §2.4 forbids `privileged: true|yes`; L0a.yml mandates `cap_drop:[ALL]`, `seccomp`, `no-new-privileges`, and `"127.0.0.1:15555:5555"` port binding.

**Gap quantification:**

| Hardening control | L0a spec | Actual Phase 3 container |
|---|---|---|
| `privileged` flag | `false` (explicit) | `--privileged` (full kernel capability grant) |
| `cap_drop` | `[ALL]` then narrow add-back (14 named caps, no SYS_ADMIN) | n/a — `--privileged` supersedes cap model |
| seccomp profile | `redroid-seccomp.json` | No seccomp (privileged disables seccomp) |
| `no-new-privileges` | `true` | Not applied |
| ADB port binding | `127.0.0.1:15555:5555` (loopback only) | `0.0.0.0:5555:5555` (all interfaces) |
| Isolated bridge network | `l0a-isolated-net` (172.30.50.0/29) | Default Docker bridge (172.17.0.0/16) |

**Risk of --privileged ReDroid on host:** `--privileged` grants every kernel capability (including `CAP_SYS_ADMIN`, `CAP_SYS_PTRACE`, `CAP_SYS_MODULE`), disables seccomp and AppArmor, and allows the container process to mount filesystems and escape via kernel interfaces. A compromised or malicious APK running inside the container that gains native code execution could leverage `CAP_SYS_ADMIN` to escape the container namespace (e.g., via `mount --bind` or `/proc/sysrq-trigger`). For a single-user private lab this is a tolerated known risk; for Phase 4 (Magisk install adds su, root, kernel module loading potential) it materially increases escape surface.

**ADB 0.0.0.0 exposure:** With `-p 5555:5555`, any host on the PAR822349 network can connect to ADB without authentication (ADB's RSA key challenge only applies to interactive authorization flows; automated tools can bypass this). For a hetzner/OVH-class dedicated server, ADB exposure to the internet is a HIGH risk — arbitrary ADB commands (install, shell, backup, jdwp debug attach) require only TCP reachability.

**Today's mitigation (partial):** apk-in-container-2026-05-29.md shows `adb connect 127.0.0.1:5555` was used from localhost — suggesting the ADB session was established from the server itself, not over the network. This does not close the `0.0.0.0` binding if the port is reachable from external hosts.

**Remediation for Phase 4 launch:** Stop the current `redroid-test` container and re-launch using `docker compose -f agents/stability/stack/compose/L0a.yml up -d redroid-l0a` after running the §2.4 preflight grep. This gives the hardened profile: `cap_drop:[ALL]`, 14 narrowly-scoped caps, custom seccomp, `no-new-privileges`, and `127.0.0.1:15555:5555`.

---

### S-04: _parse_assignment Colon-Split (LOW / Informational)

`runner.py:_parse_assignment()` splits `PATH:DEVICE:OS` on `:` and requires exactly 3 parts. A path containing a colon (non-issue on Linux; theoretical on Windows or POSIX extended attributes) would produce an incorrect split. Current environment is Linux-only; no exploitable path exists. The device/OS strings are validated downstream by `cell_key()` against the `DEVICES`/`OS_VERSIONS` allowlist, so even a malformed parse is caught before any write operation.

---

### S-05: --out Accepts Arbitrary Write Path (LOW / Informational)

`write_cells_json()` accepts the `--out` path without canonicalization. Since the function calls `out_path.parent.mkdir(parents=True, exist_ok=True)`, a path like `--out ../../etc/cron.d/cells.json` would attempt to create directories and write outside the repo. In the current operator model (single user, local or CI invocation), this is not an exploitable vector. If this runner ever receives `--out` values from untrusted sources (CI artifact webhooks, API, inter-agent messages), it must be hardened with `Path.resolve()` and a boundary check against a permitted output root.

---

### S-06: Phase 4 Requires L0b Stack Not Yet Installed (BLOCKING for Phase 4)

**Evidence from codebase:**
- L0a-RUNBOOK.md §1: "L0a does NOT provide: No Magisk, ReZygisk, LSPosed (those are L0b)."
- L0b-RUNBOOK.md §1: L0b = Magisk v27.2 + ReZygisk + LSPosed (JingMatrix fork).
- `audit/live-booted-sweep-2026-05-29.md` + `audit/apk-in-container-2026-05-29.md`: current container is plain ReDroid 12, no Magisk, no root framework. `root.su_detection` fires at 1.0 for `/system/xbin/su` (ReDroid's built-in su), but there is no Zygisk loader, no LSPosed hooking framework, and no module injection infrastructure.
- Phase 4 modules (cpuinfo-overlay, hide-frida-maps, spoof-stack-magisk) are Magisk/LSPosed modules. `infrastructure/spoof-stack-lsposed/app` is an LSPosed evasion app requiring LSPosed + Zygisk to load hooks. `stack/L4/hide-frida-maps` is a native Xposed library requiring the LSPosed hooking framework.

**Conclusion:** Phase 4 is architecturally blocked until:
1. L0b bring-up: Magisk v27.2 flash into the ReDroid /system image (or via `docker exec` magisk install) per L0b-RUNBOOK.
2. ReZygisk + LSPosed install into the running Magisk environment.
3. Verification that `init.svc.zygote_secondary` shows Magisk Zygisk active.

Phase 4 execution against the current plain container would be a no-op (modules would fail to load) or produce misleading delta measurements.

---

### S-07: Research Boundary Check — Today's Deliverables (PASS)

Reviewed: `audit/*2026-05-29*.md` (5 files), `audit/spoof-stack/endgate-2tracks-signoff-2026-05-29.md`, `p21/live-capture-2026-05-29.txt`, `p21/live-capture-booted-2026-05-29.txt`, `p21/redroid-v12-live-2026-05-29.yml`, `p21/redroid-v12-live-booted-2026-05-29.yml`.

README Hard Rules checked:
- Rule 3: "Do not build or document production bypasses for third-party platform anti-abuse, anti-bot, attestation, or fraud controls." — NOT violated. All documents describe detection signal measurement in an owned test environment.
- Rule 4: "Treat emulator, root, network, TLS, and sensor-fingerprint work as lab measurement and defensive research only." — Honored. All work described as read-only `docker exec` / `adb shell` against `redroid-test`. No account login, no real service interaction, no residential proxy usage.
- Rule 5: "Do not add instructions for hiding automation from real services." — NOT violated. The cpuinfo-overlay, hide-frida-maps, and spoof-stack-magisk modules are measured for their effect on detection SCORES within the lab container; no document instructs how to use them against a production service.

The Phase 4 plan entry ("Deploy the 3 functional in-house modules — DEFENSIVE lab measurement only") is appropriately scoped. The measurement result (re-probe delta inside an owned ReDroid container) falls within Rule 4 "reproducibility notes and hardening against unintended fingerprint leaks in owned test environments."

**One pre-execution note for Phase 4:** the measurement must remain internal to the lab container. The result document must not be framed as "how to evade [specific service]'s detection" but as "detection signal delta under SpoofStack modules in owned test environment." The existing documentation pattern (score tables, probe-by-probe findings, HONEST-LIMITED disclaimers) is consistent with this boundary.

---

## Code Audit Summary — report_validator.py, aggregator.py, runner.py

### eval / exec / shell scan
No `eval`, `exec`, `compile`, `__import__`, `subprocess`, `os.system`, or `os.popen` in any of the three files. All code is pure Python stdlib with no dynamic execution. PASS.

### Path-traversal analysis
- `report_validator.py`: accepts a pre-loaded dict; no filesystem I/O. PASS.
- `aggregator.py:load_report()`: accepts a `Path | str` for reading JSON. The path is constructed by the caller (runner.py) from CLI arguments or from `REPLAY_SPOOFED_REPORT`/`REPLAY_UNSPOOFED_REPORT` which are `Path(__file__).resolve().parents[3]`-anchored constants. No arbitrary path injection from external input in the standard execution path. PASS.
- `aggregator.py:write_cells_json()`: accepts an arbitrary `out_path` — see S-05 above (LOW/Informational). The atomic write pattern (`tmp_path.write_text()` + `tmp_path.replace(out_path)`) is correct and prevents partial-write corruption. PASS on integrity; S-05 is the residual concern.
- `runner.py:_latest_week_dir()`: iterates `super_action.iterdir()` and applies `re.match(r"^W(\d+)$", d.name)`. The regex anchors both start and end of name implicitly (`^` + `$`), so only clean `W<integer>` directory names are accepted; symlinks or `../`-style entries in the directory are safely excluded by the `is_dir()` check and the regex. PASS.
- `runner.py:_parse_assignment()`: `PATH:DEVICE:OS` colon split — see S-04 above (Informational). PASS for current Linux environment.

### Input validation — report_validator.py
`validate_report()` correctly gates on: schema version exact match, non-empty string deviceLabel, non-empty list of probes, each probe having a non-empty string `id` and a non-bool numeric `score`, and an aggregate with `weightedScore` in `[0.0, 1.0]` and a non-empty string `category`. The bool-guard `isinstance(value, Real) and not isinstance(value, bool)` (line 44) correctly prevents JSON booleans from being treated as numbers (Python `bool` is a subclass of `int`/`Real`). All validation failures raise `ReportValidationError` with path-prefixed messages, and the aggregator converts these to `AggregationError` before they can corrupt a heatmap cell. PASS.

---

## Conditions for Phase 4 Execution

1. **CONDITION-1 (MED):** Re-launch the live container using the L0a-compliant compose stack (`docker compose -f agents/stability/stack/compose/L0a.yml up -d redroid-l0a`) with the §2.4 preflight grep passing, before any Phase 4 work. This closes the `--privileged` gap and the `0.0.0.0:5555` ADB exposure.

2. **CONDITION-2 (BLOCKING):** Build and install the L0b stack (Magisk + ReZygisk + LSPosed) per L0b-RUNBOOK before attempting to deploy Phase 4 modules. Without L0b, the Magisk modules cannot load and the measurement is invalid.

Credential rotation (S-01) and the git history rewrite are standing items for the owner and do not block Phase 4 execution, but must be completed before any public sharing of the repository or its history.

---

## Quality Pillars Assessment

| Pillar | Verdict | Notes |
|---|---|---|
| 1. THREAT MODEL | PASS | No new trust boundaries introduced by Phase 3 code. The orchestrator/runner/aggregator operate on local filesystem only. |
| 2. CODE AUDIT | PASS | No eval/exec/shell; atomic writes; input validation complete in report_validator.py; path handling safe in current environment. Two informational findings (S-04, S-05). |
| 3. SECRETS | CONDITIONAL | Working tree clean (scrub confirmed). Git history still contains credential at 896cd71. Owner action required. |
| 4. DEPENDENCIES | PASS | All three files are stdlib-only. No third-party dependencies introduced. |
| 5. PLAN REVIEW | CONDITIONAL | Phase 4 plan is research-boundary-compliant but architecturally blocked (S-06). L0b prerequisite must be satisfied. |
| 6. HOOKS INTEGRITY | PASS | No hooks modified in Phase 3. Existing security hooks verified registered; no changes to `.claude/settings.json` or equivalent. |
