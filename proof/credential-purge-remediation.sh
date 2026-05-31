#!/usr/bin/env bash
# proof/credential-purge-remediation.sh
#
# READY-TO-RUN remediation for B5 (credential purge). DO NOT run autonomously — it rewrites
# published history on origin/main and force-pushes (destructive; coordinate with all clone holders).
# Owner (Martin) runs this after rotating the live credential.
set -euo pipefail

SECRET='REPLACE_WITH_THE_LEAKED_SSH_PASSWORD'   # the paris SSH pw (in .env; not stored here)

echo "STEP 1 — rotate the live credential FIRST (assume burned: it is on origin/main)."
echo "  ssh paris@195.154.209.133  then: sudo passwd paris   (or rotate via op-net panel)"
echo "  Then update the gitignored .env with the new password."
read -r -p "Rotated? [yes/no] " ok; [ "$ok" = yes ] || { echo "Rotate first."; exit 1; }

echo "STEP 2 — purge the secret from ALL history (requires git-filter-repo installed)."
# Removes the secret string everywhere and drops the tracked secret file.
printf '%s==><redacted>\n' "$SECRET" > /tmp/cred-replace.txt
git filter-repo --force \
  --replace-text /tmp/cred-replace.txt \
  --invert-paths --path 'audit/track-a-reinstall-submitted-2026-05-19.md'
rm -f /tmp/cred-replace.txt

echo "STEP 3 — re-add origin and force-push the rewritten history."
git remote add origin git@github.com:servas-ai/phantomdroid.git 2>/dev/null || true
git push --force --all origin
git push --force --tags origin

echo "STEP 4 — verify zero hits across all refs, and have collaborators re-clone."
git grep -I -n "$SECRET" "$(git rev-list --all)" && { echo "STILL PRESENT — investigate"; exit 1; } || echo "CLEAN: no hits."
