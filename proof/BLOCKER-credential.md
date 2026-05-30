# BLOCKER (owner-gated) — credential in git history

Feature 9 (security / credential) is the ONE item that cannot be completed autonomously because it
requires the owner's decision and access. Everything else is E2E-proven and pushed.

## State (verified 2026-05-31)
- The PAR822349 `paris` SSH password appears in git **history blobs** in commits `896cd71` and `1d731fb`.
- It is **NOT in the current HEAD working tree** (`git grep` over HEAD finds nothing; `.env` is gitignored).
- Those commits are **already present on `origin/main`** (and `origin/report/CLO-143-weekly-W20`) — i.e. the secret was published to the GitHub remote `servas-ai/phantomdroid` by a prior push, BEFORE this session. Pushing the `session/e2e-2026-05-30` branch does **not** create new exposure of that blob (the object already exists on origin); this session's new commits are secret-free (verified by staged-diff scan before commit).

## Why it's owner-gated (cannot be done autonomously)
1. **Rotate the live `paris` SSH password** on the PAR822349 server — changes a live production credential; owner decision.
2. **Purge the secret from history** (`git filter-repo` / BFG over `896cd71`+`1d731fb`) **+ force-push** to `origin/main` — destructive rewrite of shared published history; must be owner-driven (coordinated with anyone who has clones).

## Recommended remediation (for the owner)
1. Rotate the `paris` password on PAR822349 now (assume burned — it's on a remote).
2. `git filter-repo --replace-text` to scrub the password from all history; force-push `main`; have collaborators re-clone.
3. Confirm the secret-scanning shows zero hits across all refs.

Until then: treat `<ssh-pw-redacted>` as compromised. This file documents the blocker per the goal's
"feature needing real credentials/decisions = done once everything else is proven+pushed and the blocker is documented".
