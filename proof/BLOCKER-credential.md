# BLOCKER (owner-gated) — credential in git history

Feature 9 (security / credential) is the ONE item that cannot be completed autonomously because it
requires the owner's decision and access. Everything else is E2E-proven and pushed.

## State (verified 2026-05-31)
- The PAR822349 `paris` SSH password is present on the remote **`origin/main` in a TRACKED file**: `audit/track-a-reinstall-submitted-2026-05-19.md` (line `ROOT_PW_PAR822349_2026_05_19=...`). It is also in history blobs `896cd71` and `1d731fb`. This is a pre-existing exposure, published to `servas-ai/phantomdroid` BEFORE this session.
- **This branch (`session/e2e-2026-05-30`) is secret-free**: `git grep` over its pushed tree finds zero hits — the offending file/lines are absent here, and every new file added this session was scanned and redacted before commit (`<ssh-pw-redacted>` / `<panel-login-redacted>`). The diff `origin/main..this-branch` shows the secret only as REMOVED (`-`) lines.
- Therefore pushing this branch does NOT add new exposure; it is actually cleaner than `main`.

## Why it's owner-gated (cannot be done autonomously)
1. **Rotate the live `paris` SSH password** on the PAR822349 server — changes a live production credential; owner decision.
2. **Purge the secret from history** (`git filter-repo` / BFG over `896cd71`+`1d731fb`) **+ force-push** to `origin/main` — destructive rewrite of shared published history; must be owner-driven (coordinated with anyone who has clones).

## Recommended remediation (for the owner)
1. Rotate the `paris` password on PAR822349 now (assume burned — it's on a remote).
2. `git filter-repo --replace-text` to scrub the password from all history; force-push `main`; have collaborators re-clone.
3. Confirm the secret-scanning shows zero hits across all refs.

Until then: treat `<ssh-pw-redacted>` as compromised. This file documents the blocker per the goal's
"feature needing real credentials/decisions = done once everything else is proven+pushed and the blocker is documented".
