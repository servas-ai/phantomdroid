# Track A — Reinstall Submitted (PAR822349)

**Submission time**: 2026-05-19 (during 8h autonomous run)
**Method**: WebPi panel via CDP browser session (kortix container, persistent Chromium with `cf_clearance`)
**Owner authorization on record**: "Mach bitte, dass du einen Reinstall machst. Den Reinstall kannst du gerne starten. Der Server hat nichts Wichtiges offen"

## Form state at submission

| Field | Value | Notes |
|---|---|---|
| `distribution-type` | `server` | |
| `distribution` | `Ubuntu` | |
| `os-version` | `373` (Ubuntu **18.04 LTS**) | **Deviation from plan default (22.04 LTS)** — see below |
| `hostname` | `PAR822349` | pre-filled |
| `username` | `paris` | matches prior rescue convention; `root` was forbidden |
| `password` | 14-char alphanumeric (stored in `.env`) | provider rule: 8–15 chars, no special, must have number+upper+lower |
| `panel-password` | _(not visible / not required)_ | |
| `partitioning-type` | `default-partitioning-choice` (value `0`) | "Particiones por defecto" |

## Plan deviation: Ubuntu 18.04 LTS instead of 22.04 LTS

The WebPi reinstall menu only offers Ubuntu **18.04 LTS** for this server hardware (HP server, Intel Xeon E3-1220, 16 GB, 2x 2 TB). Options for 20.04 / 22.04 are present in the DOM but hidden via `display:none` based on per-server `data-distribution`/`data-version` filtering. 18.04 was also the server's prior OS, so reinstall returns it to the same baseline. Upgrade path to 22.04 LTS via `do-release-upgrade` can be done after the reinstall completes and SSH is reachable.

Other available options for this server were CentOS 7, Arch Linux, ESXi 6.7 U3 — none were preferable for the Kotlin/Docker/Detection workload.

## Submission flow (chronological)

1. Tab already on `https://panel.op-net.com/server/822349/manage#reinstall` from earlier login.
2. Set `distribution-type` = `server` via React-style setter on `HTMLSelectElement.value`.
3. After AJAX, set `distribution` = `Ubuntu`.
4. Set `os-version` = `373` (Ubuntu 18.04 LTS).
5. Click `Next` → advanced to Step 2 / 3.
6. First password attempt (30 chars) rejected: provider rule **max 15 characters, no special character**.
7. Regenerated password to 14-char compliant value (`5GUAkuH7j7x5hJ`).
8. First username attempt `root` rejected: "Only lowercase letter, Minimum 4, Maximum 19 characters, The following strings are forbidden (root, ...)".
9. Set `username` = `paris` → validation passed.
10. Click `Next` → advanced to Step 3 / 3 (Particionamiento). `default-partitioning-choice` checked, "El disco está completamente particionado".
11. Click `Reinstall` button (`.launch-reinstall.destructive` action) → opened alertify confirmation: *"¿Estás seguro de querer borrar todos los datos e instalar un nuevo sistema operativo en este servidor?"*
12. Clicked `Confirmar`.
13. **Panel response**: heading changed to *"Una solicitud de reinstalación está actualmente pendiente en este servidor"*.

## Risk note: hardware

HP Smart Array P410 RAID controller reported `Not responding` and `/dev/sda offline` from Rescue mode (see `audit/recovery-2026-05-19-FINAL.md`). The reinstall may fail at the storage-allocation step. If it does, the failure will be logged provider-side and the WebPi will surface an error — that becomes the hard evidence to attach to ticket `#94047858` for hardware replacement.

If reinstall succeeds, the server will come back online with:
- Username: `paris`
- Password: see `.env` line `ROOT_PW_PAR822349_2026_05_19=...`
- SSH on port 22 (provider standard)

## Credentials (sensitive — handle with care)

Stored at `/home/coder/vk-repos/cloud-phone-research-planner/.env`:
```
ROOT_PW_PAR822349_2026_05_19=5GUAkuH7j7x5hJ
```
This file is git-ignored (`.gitignore:32`). Do not commit. Do not paste into chat unless explicitly requested.

## Next polling steps

- 2026-05-19 +30 min: re-check WebPi for reinstall progress
- 2026-05-19 +60 min: ping `195.154.209.133`; if alive, attempt SSH `paris@195.154.209.133`
- 2026-05-19 +2h: if no progress, capture WebPi status banner and update ticket `#94047858`
- On failure: capture full WebPi error, update ticket with logs as new reply
