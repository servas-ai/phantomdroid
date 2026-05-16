# Goal-to-Test Binding (CLO-109) — Acceptance Block Spec v1

**Source**: CLO-109 "G-INT-100.2 :: Goal-to-Test Binding — every Tier-2/3 issue requires acceptance.cmd"
**Owner**: Orchestrator (`d5ee730e-637a-4ccb-85fe-de0d10dcd112`)
**Authored**: 2026-05-16
**Status**: DRAFT — spec circulated for review; reference lint script in `lint-issue-acceptance.sh`. Enforcement at `issue.create` time is staged separately (see §6).

## 1. Scope

Every issue classified as **tier-2** or **tier-3** (per CLO-110 tier-routing-policy) MUST carry exactly one fenced acceptance block in its description. tier-1 issues MAY carry one but are not required to. Issues without a tier label are out-of-scope for this lint and surface via a separate `missing-tier-label` lint (CLO-110 §3).

## 2. Block format (canonical)

The block is a fenced code section with `yaml` info-string and the marker `acceptance:` as the top-level key. There is no other valid form.

````markdown
```yaml acceptance
cmd: "bash scripts/check-foo.sh --ref ${ISSUE_ID}"
expect_exit: 0
expect_json_jq: '.passed == true and .findings == 0'
runs_on: "ci-linux-x86_64"
timeout_sec: 180
```
````

Equivalent canonical YAML (parsed):

```yaml
acceptance:
  cmd: "bash scripts/check-foo.sh --ref ${ISSUE_ID}"
  expect_exit: 0
  expect_json_jq: ".passed == true and .findings == 0"
  runs_on: "ci-linux-x86_64"
  timeout_sec: 180
```

## 3. Field reference

| Field | Type | Required | Constraints |
|---|---|---|---|
| `cmd` | string | yes | Shell command, ≤ 512 chars. MUST be deterministic & idempotent (re-runnable). MAY reference `${ISSUE_ID}` and `${COMMIT_SHA}`. MUST NOT reference live network resources outside the runs_on allow-list. |
| `expect_exit` | int | yes | 0..255. The acceptance gate passes only if `$?` equals this value. |
| `expect_json_jq` | string | conditional | jq expression that MUST evaluate to `true` against `cmd`'s stdout (parsed as JSON). Required when `cmd` produces structured output. Use literal `null` to skip JSON validation. |
| `runs_on` | string | yes | Enum: `ci-linux-x86_64`, `ci-linux-arm64`, `dev-host-only`, `redroid-sandbox`, `online-net-live`. Sets the runner environment and capability ceiling. |
| `timeout_sec` | int | yes | 1..3600. Hard kill at this duration; FAIL on timeout regardless of `cmd` state. |

### Optional fields (v1, may be required in v2)

| Field | Type | Notes |
|---|---|---|
| `expect_artifacts` | string[] | List of paths the cmd must produce (existence-check). Each path may use `${ISSUE_ID}` interpolation. |
| `forbid_secrets_in_logs` | bool | Default `true`. If true, lint scans stdout/stderr for `AKIA…`, `gh[ps]_…`, `sk-live-…`, etc. |
| `record_artifact_sha` | bool | Default `false`. If true, runner publishes sha256 of each `expect_artifacts` item to the issue as a comment. |

## 4. Examples

### tier-1 example (optional — doc-only change)

````markdown
```yaml acceptance
cmd: "bash scripts/lint-issue-acceptance.sh --issue ${ISSUE_ID}"
expect_exit: 0
expect_json_jq: null
runs_on: "ci-linux-x86_64"
timeout_sec: 30
```
````

### tier-2 example (probe code path)

````markdown
```yaml acceptance
cmd: "./gradlew :detection:test --tests com.detectorlab.probes.env.TimeSpoofingProbeTest"
expect_exit: 0
expect_json_jq: null
runs_on: "ci-linux-x86_64"
timeout_sec: 600
```
````

### tier-3 example (RASP-sensitive — must also pass verifier + human-approved per CLO-110)

````markdown
```yaml acceptance
cmd: "bash agents/stability/scripts/preflight-sandbox.sh --cell ${ISSUE_ID} --skill-version v7"
expect_exit: 0
expect_json_jq: '.preflight_gates_passed == 6 and .privileged == false'
runs_on: "redroid-sandbox"
timeout_sec: 1800
```
````

## 5. Lint script (`lint-issue-acceptance.sh`)

Reference implementation lives next to this spec in the same workspace as
`lint-issue-acceptance.sh`. Behavior contract:

1. Fetch all open issues from Paperclip API (`paperclipai issue list -C $PAPERCLIP_COMPANY_ID --status todo,in_progress,in_review,blocked --json`).
2. Filter to tier-2 / tier-3 by label.
3. Extract any fenced block with info-string starting with `yaml acceptance` OR containing top-level key `acceptance:`.
4. Validate the parsed YAML against §3 schema (required fields present, enum values valid, integer ranges).
5. Exit 0 if all sampled issues conform; exit 1 if any T2/T3 issue is missing or malformed, printing `IDENTIFIER\tREASON` for each.
6. `--issue <id>` mode lints a single issue (used by the `issue.create` hook in v2).
7. `--self-test` mode generates a synthetic T2 issue object lacking the block and asserts exit 1 with reason "no-acceptance-block".

Acceptance per CLO-109:
- `bash scripts/lint-issue-acceptance.sh` exits 0 on all open T2/T3 issues that conform.
- `bash scripts/lint-issue-acceptance.sh --self-test` exits 1 on the synthetic counter-example.

## 6. Enforcement at `issue.create` (separate work — out of scope for v1)

The CLO-109 description says "Orchestrator must reject Tier-2/3 issue.create calls without acceptance block". This is a coordination-layer change that depends on:

- Paperclip's pre-create hook mechanism (TBD whether it exists; if not, filed as a separate orchestrator-side wrapper).
- The orchestrator wrapping `paperclipai issue create` calls in its own routines to validate the description before POST.

This work is **deliberately deferred** to a v2 amendment (filed as a follow-up issue when the lint is proven and a representative sample of T2/T3 issues has been backfilled with acceptance blocks). v1 is the lint + spec — sufficient for retroactive enforcement and as the schema-of-record.

## 7. Roll-out plan (this issue → done)

1. Spec landed (this document).
2. Lint script landed (`lint-issue-acceptance.sh`).
3. Run lint against current 31 T2/T3 open issues (per the 2026-05-16T19:59Z snapshot). Output: per-issue conformance table.
4. Backfill: for each non-conforming T2/T3 issue, file a tier-1 child to add the block.
5. Add `lint-issue-acceptance` invocation to the orchestrator heartbeat (read-only at v1; rejects new T2/T3 issues at v2).
6. Comment on CLO-109 with `[LINT-V1-LANDED]` + spec path + lint script path + non-conformant-issue manifest. Transition CLO-109 to `in_review`.

## 8. Anti-rationalization

| Excuse | Rebuttal |
|---|---|
| "The acceptance block duplicates the description text." | Description is human prose; acceptance block is machine-checkable. Both have to exist; they are not substitutes. |
| "My T2 issue has no obvious acceptance check." | Then it is not actually T2-ready. File the missing test as a precondition and reclassify pending. |
| "I'll add the block later when the implementation is done." | Block is the spec, not the proof. Author the block first so the implementation can be verified against it. |
| "Some T3 issues are purely escalations — no cmd applies." | Escalations have an acceptance criterion (board decision rendered + label applied). Encode that as a no-op `cmd: "true"` with `expect_json_jq: null` ONLY when documented as an escalation issue, not for code-bearing T3. |

— Orchestrator (CLO-109 v1)
