# Recovery — PAR822349 — FINAL (2026-05-19)

Server: `PAR822349`
Public IPv4: `195.154.209.133`
Public IPv6: `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`
Host Port 1 MAC: `e4:11:5b:0d:be:a0`
iLO/IPMI endpoint: `51.159.47.149`
Provider: OneProvider / CZ Design WebPi
Workspace whitelist egress IPv4: `152.53.35.28`

This file consolidates the nine 2026-05-17/18 recovery artifacts plus the two then-in-flight modified files into one authoritative record. Source artifacts are now archived under `audit/archive/2026-05-18/`.

## TL;DR

Server PAR822349 (HP ProLiant DL120 G7, RAID-1 on HP Smart Array P410) is functionally dead from the customer side: the P410 RAID controller reports `Not responding`, `/dev/sda` is `offline` in Rescue, and WebPi `createIpmiSession` rejects every attempt with `Invalid boot mode` even though Rescue Mode is correctly set. Provider tickets `#94047858` and `#47300051` are open in `Respuesta-cliente` state with no provider remediation visible through 2026-05-18 17:41 CEST. The owner has explicitly approved a full reinstall ("Server hat nichts Wichtiges offen"); the Track A 8h plan targets a WebPi reinstall to Ubuntu 22.04 amd64 starting ~2026-05-19 01:30 CEST. Express/VIP escalation, BIOS/RAID/IPMI changes, and disk repair remain NOT approved.

## Timeline

- **2026-05-07**: iLO SEL records `POST Error: 1719 - A controller failure event occurred prior to this power-up` (`record44`). This is the only relevant SEL entry; `record45+` are still empty/invalid.
- **2026-05-17 ~14:00 CEST**: Custom Ubuntu 24.04.4 autoinstall ISO built and served via iLO Virtual Media. iLO never fetched it. Virtual Media reset to safe state afterward.
- **2026-05-17 18:15 CEST**: BrowserScan 100%, Cloudflare blocks automated panel access; manual X11/Chromium reaches WebPi. Reinstall wizard only exposes Ubuntu 18.04 LTS.
- **2026-05-17 19:45 CEST**: Panel reinstall to Ubuntu 18.04 succeeds; SSH restored as `paris`; baseline saved under `/root/recovery-baseline`. 16 GB RAM, ~1.7 TB free root, `vmx` + `/dev/kvm` present.
- **2026-05-17 ~20:30 CEST**: In-place upgrade 18.04 → 20.04 finishes `UPGRADE_RC:0`. Mandatory reboot makes the server unreachable. `ping`, TCP/22, and SSH all timeout.
- **2026-05-17 23:08–23:55 CEST**: Multiple read-only iLO/WebPi rechecks; ticket `#94047858` opened/maintained. Repeated `Server reset.` records in iLO log; VSP shows no boot output.
- **2026-05-18 00:00–00:51 CEST**: Bounded SSH/ping polls all fail. TTL-probe shows packets reach Scaleway/Online edge (`195.154.2.103`, `51.158.8.73`, `51.158.0.11`) then disappear. New ticket reply posted (`Update 2026-05-18 00:45 CEST`) requesting datacenter-side rescue/PXE/switch checks.
- **2026-05-18 01:20 CEST**: WebPi Remote Access for `152.53.35.28` returns `Invalid boot mode.` after the BIOS/RAID/IPMI warning modal. Boot mode left unchanged.
- **2026-05-18 04:02–04:26 CEST**: Authenticated WebPi confirms Rescue Mode in UI and backend (`getStatus` / `getRescueMode` = `rescue_mode`, user `paris`); RAID tab states `RAID 1`. `createIpmiSession` re-tested with type values `blank`, `ilo`, `ipmi`, `html5`, `java`, `kvm`, `remote`, `console` — all `Invalid boot mode`. Separate IPMI ticket `#47300051` has STAFF reply confirming IPMI is authorized on the account.
- **2026-05-18 04:17 CEST**: Live Rescue SSH (`paris@195.154.209.133`) read-only diagnostics, kernel `6.8.0-57-generic`, Ubuntu 22.04 live Rescue: `/dev/sda` is HP `LOGICAL VOLUME` ~1.8T but state `offline`; `/proc/mdstat` empty; `ssacli ctrl all show` → `Smart Array P410 (Error: Not responding)`; `lspci` confirms P410 + driver `hpsa`.
- **2026-05-18 05:26–07:06 CEST**: Repeated WebPi/backend/ticket rechecks. Main ticket `#94047858` rendered at `Respuesta-cliente`, 19 replies, contains `Update 2026-05-18 06:15 CEST`, `Smart Array P410`, `Invalid boot mode`. IPMI ticket `#47300051` rendered at `Respuesta-cliente`, 2 replies, contains `Update 2026-05-18 06:09 CEST`, `Invalid boot mode`, `PAR822349`.
- **2026-05-18 09:38 CEST**: Plain Chromium/Xvfb authenticated WebPi run visually reconfirms `PAR822349`, IPv4 `195.154.209.133`, `Modo rescate`. Remote Access whitelist set to `152.53.35.28`; warning modal opened; no usable IPMI credentials produced.
- **2026-05-18 10:02 CEST**: After context compaction, panel credentials no longer available; persistent browser session logged out at `Sign in | OneProvider`. No further authenticated WebPi action possible from this workspace.
- **2026-05-18 12:17 CEST**: `freeipmi-tools` installed locally. From workspace egress `152.53.35.28`, `ipmi-ping`/`ipmiping` to `51.159.47.149` return 0/100% packet loss. `nc -uvz` UDP/623 success is NOT evidence of working RMCP.
- **2026-05-18 12:33–12:42 CEST**: `nmap` top-1000 TCP scans: IPMI `51.159.47.149` has only TCP/80 open; server `195.154.209.133` has only TCP/22 and TCP/80 open. IPv6 ports 22/80/443/8080/8443 all filtered.
- **2026-05-18 13:54–14:21 CEST**: Local credential recovery exhaustively ruled out: SSH keys (`id_ed25519`, `online-paris`) fail for `paris` and `root`; `.env` candidate fails; agent-history-derived login candidates fail; no usable OneProvider `Api-Key`/`Client-Key` found locally.
- **2026-05-18 15:32–16:09 CEST**: Browser profile / Firefox-Camoufox / keyring / shell-history metadata scans — no usable WebPi session source. Bitwarden CLI present but locked; `rbw` agent not running; `secret-tool`, `pass`, `op`, `gnome-keyring-daemon`, `kwallet-query` absent.
- **2026-05-18 16:17–17:13 CEST**: Direct unauthenticated WebPi HTTP returns Cloudflare `HTTP/2 403` with `cf-mitigated: challenge`. Persistent Chromium CDP unreachable from host namespace.
- **2026-05-18 17:30 CEST**: Persistent Chromium CDP found reachable only inside Chromium's own network namespace (Chrome 146.0.7680.177). Active tab is `https://panel.op-net.com/login#overview`, title `Sign in | OneProvider`. History records the exact server URL `https://panel.op-net.com/server/822349/manage#overview` last hit `2026-05-18 10:02:26 UTC`, also logged out. `Login Data` has no saved OneProvider/WebPi origins.
- **2026-05-18 17:41 CEST**: CDP DOM recheck: login page only, 0 filled visible inputs, 1 empty email-like input, 1 empty password input, no server/Rescue/IP markers, no auth/session-style storage keys or cookie names. Final consolidation snapshot for the 17:41 CEST audit refresh.
- **2026-05-19 ~01:30 CEST**: Owner approval recorded for reinstall ("Server hat nichts Wichtiges offen"). 8h plan Track A targets WebPi REINSTALACIÓN → Ubuntu 22.04 server amd64, provider-default partitioning, provider-generated root password (read via panel "Mostrar"). At consolidation time this is documented as the next action; actual reinstall submission belongs to Track A.

## What is broken

- **Hardware**: HP Smart Array P410 RAID-1 controller. `ssacli ctrl all show` and `ssacli ctrl all show config` both report `Smart Array P410 (Error: Not responding)`. `ssacli ctrl all show status` returns `Error: Cannot show status for this device.` `lspci` shows the controller with driver `hpsa` but the device is unresponsive.
- **Storage**: `/dev/sda` (HP `LOGICAL VOLUME`, ~1.8T) is `offline` in Rescue. `/sys/class/scsi_disk/0:1:0:0/device/state` reports `offline`. `/proc/mdstat` has no active md array; `md0` shown as `0B`. Partitions `sda1/sda2/sda3` visible structurally but inaccessible.
- **Out-of-band management**: WebPi `createIpmiSession` always returns `Invalid boot mode` regardless of whitelist IP (`152.53.35.28` and auto-filled IPv6 both tested) and regardless of `type` value (8 values tested). `getIpmiCredentials` returns `Unable to obtain authentication info. Please try again later or contact support.` iLO endpoint `51.159.47.149` serves only default `Welcome to nginx!` (nginx/1.22.1) on TCP/80; TCP/443 and TCP/22 closed/timeout; UDP/623 reachable to `nc` but `ipmi-ping`/`ipmiping` return 0 RMCP responses.
- **IPv6 service ports**: ICMP works to `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`, but TCP/22, TCP/80, TCP/443, TCP/8080, TCP/8443 all filtered/no-response.
- **Customer-side WebPi access (since 10:02 CEST)**: Persistent browser session logged out; Cloudflare blocks unauthenticated direct WebPi URL fetches with `HTTP/2 403 cf-mitigated: challenge`; no local credential source (browser profiles, keyrings, `.env`, agent-history, env vars, common credential stores) yields a usable login.
- **Customer-side Rescue SSH from this workspace**: TCP/22 reachable and banners `SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.11`, but key-only auth fails for `paris` and `root` with both local keys; needs the current WebPi-displayed Rescue password.

## What works

- Rescue SSH over IPv4 (TCP/22) as user `paris` when the current WebPi-displayed Rescue password is supplied — last confirmed working in the 2026-05-18 05:58–06:00 CEST and earlier 04:17 CEST authenticated sessions.
- IPv4 reachability: ping, TCP/22 (OpenSSH banner), TCP/80 (Rescue nginx 1.18.0 returns HTTP 503 with body title `Serveur Dedibox en maintenance`).
- IPv6 ICMP.
- WebPi panel via authenticated browser (when credentials are entered live by the operator) — confirmed 04:02 and 09:38 CEST.
- iLO 3 read-only SSH/SMASH path (when Remote Access session is live): shows power, fan, PSU, temperature, NIC MAC, management log, boot order numerics, VSP.
- Ticket system: `#94047858` (19 replies, `Respuesta-cliente`) and `#47300051` (2 replies, `Respuesta-cliente`) both render content. Provider can see all customer evidence.
- IPv4 → MAC mapping confirmed coherent end-to-end: WebPi maps `195.154.209.133` and `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` to `e4:11:5b:0d:be:a0`; iLO confirms `Port1NIC_MACAddress=e4:11:5b:0d:be:a0`.

## Provider state

- **Ticket #94047858 (main)**: `Respuesta-cliente`, 19 replies. Latest customer push verified: `Update 2026-05-18 06:15 CEST` containing the Smart Array P410 / `Invalid boot mode` evidence. Earlier visible updates include `Update 2026-05-18 05:26 CEST`, `Update 2026-05-18 04:17 CEST`, `Update 2026-05-18 04:07 CEST`, `Update 2026-05-18 00:45 CEST`. Last visible provider reply on file: **Jhonathan ack 2026-05-18 16:37 CEST** (referenced in the goal/plan; no remediation content). No technical remediation reply visible through 17:41 CEST.
- **Ticket #47300051 (IPMI)**: `Respuesta-cliente`, 2 replies. Latest customer push: `Update 2026-05-18 06:09 CEST` containing `Invalid boot mode` + `PAR822349`. A STAFF reply earlier confirmed IPMI is authorized on the account for supported servers — this rules out account-level IPMI permission as the cause.
- **TTL probe evidence**: packets from this workspace reach `195.154.2.103`, `51.158.8.73`, `51.158.0.11` (Online/Scaleway side) before the target-side path absorbs them — consistent with provider-side rescue/PXE/switch/boot issue, not a local routing failure.

## Owner approvals on record

- **2026-05-19 ~01:30 CEST** — **APPROVED**: Reinstall. Owner statement (verbatim): _"Server hat nichts Wichtiges offen"_. Plan target: WebPi → REINSTALACIÓN → Step 1 Software: distribution-type=`server`, distribution=`ubuntu`, version=`22.04`; provider-default partitioning; provider-generated root password (read via panel "Mostrar"). See `audit/8h-autonomous-plan-2026-05-19.md` and `GOAL-8h.md`.
- **NOT approved** (do not perform without explicit owner confirmation):
  - Express/VIP escalation (`escalate-vip` form, `Escalar este ticket a Express`) — may consume voucher/account credit.
  - Cancellation of the server.
  - BIOS settings change.
  - RAID level change (would delete data, requires reinstall).
  - Persistent IPMI/iLO configuration change.
  - Disk layout change.
  - `fsck` on installed disks.
  - Read-write mount of installed disks.
  - Boot to normal mode as a "test".

## Open actions

- **A1 (Track A)**: Execute WebPi reinstall as approved (Ubuntu 22.04 server amd64, default partitioning). If the reinstall fails at the storage step due to P410, capture the WebPi reinstall-status hard logs and post them as a new reply to ticket `#94047858`.
- **A2**: ScheduleWakeup +30min after reinstall submit for first status poll.
- **Provider-side asks still open on `#94047858`** (do not duplicate; evidence already visible to provider):
  1. Explain/fix why WebPi `createIpmiSession` returns `Invalid boot mode` while Rescue Mode is confirmed by the same backend.
  2. Explain/fix why `51.159.47.149` exposes only default nginx on TCP/80, no RMCP on UDP/623 from the whitelisted source IP, and closed TCP/443 + TCP/22; confirm whether Remote Access is exposed on any alternate port (none of the top-1000 TCP ports are open).
  3. Check HP Smart Array P410 controller health, physical disk health, and RAID-1 logical volume state from datacenter-side; confirm whether the logical volume can be brought online safely or whether hardware/remote-hands intervention is required.

## Closed actions

- WebPi UI/IP/MAC verification: complete and coherent (last visual reconfirm 2026-05-18 09:38 CEST).
- Rescue Mode verification: complete; backend `getStatus` and `getRescueMode` both report `rescue_mode`, user `paris`.
- Whitelist-IP root-cause check: ruled out — both `152.53.35.28` and the auto-filled IPv6 yield `Invalid boot mode`.
- `createIpmiSession` `type` parameter sweep: ruled out — 8 values tested, all `Invalid boot mode`.
- WebPi Remote Access frontend handler inspection: complete; UI sends only `action=createIpmiSession`, `ip`, optional `duration`/`type`, `server_id`. No hidden required field missing.
- Account-level IPMI authorization: ruled out — STAFF reply on `#47300051` confirms IPMI authorized.
- TCP port-surface enumeration: complete (top-1000 on both server and iLO; targeted IPv6 ports).
- RMCP protocol-level reachability from the intended whitelist source IP: confirmed failing (0 responses).
- Local credential recovery (workspace-side): exhausted across SSH keys, agent history, `.env`, env vars, Chromium profiles, Firefox/Camoufox, keyrings (Bitwarden/rbw/secret-tool/gnome-keyring/kwallet/pass/op), shell history, OneProvider API keys.
- Ticket evidence rendering verified at 2026-05-18 07:06 CEST for both `#94047858` and `#47300051`; do not duplicate ticket comments.
- Custom Ubuntu 24.04 autoinstall ISO path (2026-05-17): abandoned — iLO never fetched the ISO; Virtual Media reset to safe state; temporary HTTP server stopped.
- 18.04 → 20.04 in-place upgrade (2026-05-17): performed and bricked the host on the kernel reboot; abandoned in favor of the current Rescue-and-reinstall path.

## Artifacts (now archived under `audit/archive/2026-05-18/`)

- `current-blockers-and-required-input-2026-05-18.md` — short current-state snapshot (last refresh 17:41 CEST).
- `owner-action-request-de-2026-05-18.md` — German owner-facing decision note.
- `owner-decision-needed-2026-05-18.md` — English decision options (A wait / B Express / C remote-hands / D fresh access).
- `provider-express-escalation-message-2026-05-18.md` — copy-ready Express message, use only after owner approval.
- `provider-handoff-2026-05-17.md` — historical handoff with current-status header through 16:36 CEST.
- `provider-ticket-escalation-draft-2026-05-18.md` — concise provider-facing escalation summary.
- `recovery-artifacts-index-2026-05-18.md` — prior index of recovery artifacts.
- `recovery-next-actions-2026-05-18.md` — operator runbook (safe checks, gates, do-not-do list).
- `webpi-rescue-completion-audit-2026-05-18.md` — main completion audit (final refresh 17:41 CEST).

Still tracked in `audit/` (mid-flight modifications handled by Track B/F):

- `audit/server-reinstall-status-2026-05-17.md`
- `audit/consolidation-summary-2026-05-17.md`

## References

- Original plan: `audit/8h-autonomous-plan-2026-05-19.md`
- Overall goal: `GOAL-8h.md`
- Provider tickets: `#94047858` (main), `#47300051` (IPMI)
- WebPi server URL: `https://panel.op-net.com/server/822349/manage#overview`
- WebPi login URL: `https://panel.op-net.com/login#overview`
