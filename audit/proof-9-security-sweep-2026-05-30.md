# Proof 9 — Security Sweep (VERIFICATION slice 9 of 100% E2E proof)

**Date:** 2026-05-30
**Scope:** Local, read-only. Prove the repo working tree is credential-clean and secure.
**Agent:** ralph-security
**HEAD:** `1d731fb`

---

## VERDICT

| Check | Result |
|---|---|
| (1) Working-tree secret scan (4 fragments) | **PASS — working tree clean** |
| (2) `.env` is gitignored | **PASS** |
| (3) SSH pw history item | **OPEN — and broader than expected (see Finding 9-A)** |
| (4) New 05-29/05-30 audit + p21 files secret/PII scan | **PASS for secrets; INFO for infra PII (Finding 9-B)** |
| (5) Python + detector-app hygiene (no new exec/eval/network) | **PASS** |

**Overall working-tree status: PASS (clean).** One pre-existing OPEN history item, now confirmed to also live in the current HEAD commit (not just the historical commit). One new INFO-level infra-PII note. No new secret leak.

---

## (1) Full working-tree scan — PASS

Command (exactly as specified):
```
git ls-files --cached --others --exclude-standard -z \
  | xargs -0 grep -lEF -e '<REDACTED-ssh-pw>' -e '<REDACTED-panel-pw>' -e 'k14ln' -e 'sxuh4'
```
Result: **empty** (no file in the working tree — tracked or untracked — contains any of the four fragments).

Reconfirmed with an explicit determination: `WORKING_TREE_CLEAN: no files contain any fragment`.

Fragment presence across git history (pickaxe `git log --all -S`):
- `<REDACTED-ssh-pw>` → present (commit `896cd71`, **and HEAD** — see Finding 9-A)
- `<REDACTED-panel-pw>` → not found anywhere
- `k14ln` → not found anywhere
- `sxuh4` → not found anywhere

## (2) `.env` gitignored — PASS

```
git check-ignore .env  →  .env   (exit 0)
```
`.gitignore:36` = `.env` (and `:37` `.env.local`). A real `.env` (986 bytes) exists on disk and is correctly ignored; it is the documented store for `ROOT_PW_PAR822349_2026_05_19`.

## (3) Known-OPEN SSH-password history item — OPEN (broader than the brief assumed)

The brief asked to confirm the SSH password is **ONLY** in historical commit `896cd71` and not in any current tracked/untracked file. That assumption does **not fully hold** — see Finding 9-A. The working-tree-on-disk grep (check 1) is clean **only because of an uncommitted redaction edit**; the committed HEAD tree still contains the plaintext.

### Finding 9-A — SSH password still plaintext in the CURRENT HEAD commit (HIGH, pre-existing)

- File: `audit/track-a-reinstall-submitted-2026-05-19.md`
- The plaintext value `<REDACTED-ssh-pw>` appears **2 times** in the HEAD blob (`git show HEAD:...` lines 34 and 55: a prose mention and `ROOT_PW_PAR822349_2026_05_19=<REDACTED-ssh-pw>`).
- The on-disk working-tree copy of this file **has been redacted** (replaced with "stored in gitignored `.env`; redacted here"), but that redaction is an **uncommitted** modification (`git status` shows ` M`). It has not been committed.
- Therefore the secret is NOT only in commit `896cd71` — it is reachable in the tip commit `1d731fb` (HEAD) as well, plus all intervening commits.

Why check (1) reported clean: check (1) greps the working-tree files on disk, which carry the not-yet-committed redaction. The secret is invisible to a disk grep but fully present in committed git objects.

**Owner remediation required (escalates the known item):**
1. Commit the existing working-tree redaction so HEAD no longer carries plaintext.
2. Rotate the server password `ROOT_PW_PAR822349_2026_05_19` (it must be assumed compromised — it has been committed).
3. `git filter-repo` (or BFG) to purge `<REDACTED-ssh-pw>` from ALL history (commit `896cd71` through `1d731fb`), then force-push and have collaborators re-clone.
4. Until rotation+filter-repo complete, treat this credential as burned.

This is the single OPEN item. It is pre-existing (not introduced by the 05-29/05-30 work) but is materially worse than "only in 896cd71": the redaction was done on disk but never committed.

## (4) New 2026-05-29 / 2026-05-30 audit + p21 files — PASS (secrets); INFO (infra PII)

Scanned all new dated (`2026-05-29`, `2026-05-30`) and `p21` text files (PNG screenshots excluded from text scan) for: password/api-key/secret/token assignments, private-key headers, AWS/GitHub/OpenAI token formats, and the four target fragments.

- **No plaintext password value** in any new file (explicit search for `<REDACTED-ssh-pw>`, `ROOT_PW...=`, `sshpass -p`, `password: <8+ chars>` → zero hits).
- **No API keys, tokens, or private-key material** found.

### Finding 9-B — Infrastructure identifiers present (INFO / LOW)

Many new audit + p21 files embed the server identity in headers/provenance lines:
- `paris@195.154.209.133` (SSH username + public IP of host PAR822349), e.g. `audit/SESSION-E2E-2026-05-29.md:5`, `audit/proof-5-live-boot-2026-05-30.md:9`, `p21/live-capture-2026-05-29.txt:3`, `p21/redroid-v12-live-booted-2026-05-30.yml:9-10`.
- These are username + public IP (no password). LOW sensitivity, but they widen the attack surface for the host that already has a burned credential (Finding 9-A). Recommend the owner consider scrubbing the IP/username from committed audit headers when the filter-repo pass for 9-A is performed (same operation, marginal extra cost). Not blocking.
- Other matches were benign: private RFC1918 ranges in network-design tables (`172.30.50.0/29`, `172.17.0.0/16`), upstream DNS `8.25.203.30/.31` (public T-Mobile resolvers, not a secret), and `<key>`/`<pw>` placeholders.

## (5) Python + detector-app hygiene — PASS

New / relevant Python:
- `agents/orchestrator/src/aggregator.py` — **clean**: no `eval`/`exec`/`os.system`/`subprocess`/socket/http/`__import__`/`pickle`/`yaml.load`/`shell=True`.
- `agents/orchestrator/src/report_validator.py` — **clean** (same set).
- `apps/detector-lab/scripts/probe_emit.py` — **clean** (same set).
- `scripts/p21/run-all-checks.py` — uses `subprocess` for `adb` orchestration. **All calls are list-form** (`subprocess.run([...])`); **no `shell=True`** anywhere (the only two `shell=True` occurrences are in comments/docstring explicitly documenting its absence). **No network egress** (no socket/requests/urllib/http/connect). Stdlib only.

No new `exec`/`eval`/network primitives introduced. Detector-app and the report_validator/aggregator pair remain consistent with their prior endgate sign-off.

---

## 6 Quality Pillars

| Pillar | Status |
|---|---|
| 1. Threat model | No new trust boundary introduced by p21/audit work; detector harness is local adb only. |
| 2. Code audit | Python clean (no exec/eval/network/shell). |
| 3. Secrets | Working tree clean. One pre-existing secret in committed history+HEAD (Finding 9-A). |
| 4. Dependencies | run-all-checks is stdlib-only; no new third-party deps in scanned files. |
| 5. Plan review | N/A (verification slice, read-only). |
| 6. Hooks integrity | Not in scope of this read-only slice. |

## Bottom line

- Working tree: **PASS / clean** for all four fragments.
- `.env`: **gitignored / PASS**.
- OPEN item (HIGH, pre-existing): SSH password `<REDACTED-ssh-pw>` is in **committed git objects** — both historical commit `896cd71` AND the current HEAD `1d731fb` (`audit/track-a-reinstall-submitted-2026-05-19.md`). The on-disk redaction is uncommitted. Owner must commit the redaction, rotate the password, and `git filter-repo` the full history.
- New finding (INFO/LOW): host username+public IP `paris@195.154.209.133` recurs in new audit/p21 headers (Finding 9-B) — scrub opportunistically during the filter-repo pass.
- No new secret/PII leak beyond the above; no new exec/eval/network in Python or detector-app.
