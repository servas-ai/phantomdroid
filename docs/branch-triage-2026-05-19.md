# Branch Triage — 2026-05-19

**Working branch**: `report/CLO-143-weekly-W20`
**Reference branches**: `main`, `report/CLO-143-weekly-W20`
**Triage rule**:
- **`merged`** — branch HEAD already reachable from both `main` and `report/CLO-143-weekly-W20` → delete locally
- **`wip-keep`** — has unique commits ahead of HEAD → keep (work in progress, owner decides next ramp)
- **`archive`** — historical checkpoint without ongoing work → tag-and-delete

## Triage table

| Branch | Ahead | Behind | Merged main | Merged W20 | Last commit | Status |
|---|---:|---:|:---:|:---:|---|---|
| `feat/CLO-109-acceptance-block` | 4 | 15 | no | no | `f431a91` 2026-05-16 — orchestrator goal-to-test acceptance block spec | **wip-keep** |
| `feat/CLO-11-teesimulator-default` | 3 | 15 | no | no | `e3ef12f` 2026-05-16 — paperclip worker-adapter + BuildCommit v1.0 | **wip-keep** |
| `feat/CLO-114-cpuinfo-tensor-g2` | 0 | 14 | yes | yes | `676d6c1` 2026-05-16 — Tensor-G2 profile, persistent Serial | **merged** → delete |
| `feat/CLO-115-gradle-wiring` | 1 | 14 | no | no | `3cbe9fc` 2026-05-16 — CLO-115 test pass fix | **wip-keep** |
| `feat/CLO-12-trickystore-spoofstack` | 1 | 27 | no | no | `34a4226` 2026-05-16 — META-25 weekly-report template + driver | **wip-keep** |
| `feat/CLO-129-location-mock-probe` | 0 | 13 | yes | yes | `303b97d` 2026-05-16 — env.location_mock probe (rank 39) | **merged** → delete |
| `feat/CLO-132-props-overlay` | 1 | 15 | no | no | `b9c16ec` 2026-05-16 — Pixel-7 fingerprint Magisk module | **wip-keep** |
| `sandbox/019e2f10-37cb-7c8b-bbfb-90e573cfe302` | 3 | 33 | no | no | `a6c314b` 2026-05-16 — app.tiktok_argus_signing probe sandbox | **wip-keep** |
| `backup-pre-legal-removal-2026-05-15` | 0 | 42 | yes | yes | `3660277` 2026-05-15 — checkpoint before legal/ethics removal | **archive** → tag `archive/backup-pre-legal-removal-2026-05-15` then delete |

## Actions taken (this commit)

- Deleted local: `feat/CLO-114-cpuinfo-tensor-g2`, `feat/CLO-129-location-mock-probe`
- Tagged + deleted: `backup-pre-legal-removal-2026-05-15` → tag `archive/backup-pre-legal-removal-2026-05-15`

## wip-keep — owner attention

These six branches each carry unique work not yet merged. They are 13–33 commits behind HEAD, so they will need a rebase or cherry-pick before further integration. Owner decides next ramp; none should be deleted without explicit go.

- `feat/CLO-109-acceptance-block` — 4 unique commits (orchestrator goal-to-test acceptance block; lint)
- `feat/CLO-11-teesimulator-default` — 3 unique commits (paperclip worker-adapter contracts; BuildCommit v1.0; META-25 template driver)
- `feat/CLO-115-gradle-wiring` — 1 unique commit (test pass fix on top of CLO-115 already merged)
- `feat/CLO-12-trickystore-spoofstack` — 1 unique commit (META-25 weekly-report template + driver — likely orphan that overlaps with `feat/CLO-11`'s)
- `feat/CLO-132-props-overlay` — 1 unique commit (Pixel-7 fingerprint Magisk module)
- `sandbox/019e2f10-...` — 3 unique commits (CLO-19 TikTokArgus probe sandbox iteration; superseded by the merged probe but kept until probe stabilizes)

## Out of scope (this triage)

- Remote tracking branches and PRs on GitHub: not enumerated here; owner-side check
- `main` branch state: untouched per plan
- Any new branch creation: deferred to Track F if needed for tag
