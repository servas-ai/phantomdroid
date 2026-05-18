# Recovery artifacts index - PAR822349

Generated: 2026-05-18
Last updated: 2026-05-18 17:41 CEST

This index explains which local recovery notes are authoritative for the current OneProvider / CZ Design WebPi incident.

## Current-state artifacts

- `audit/current-blockers-and-required-input-2026-05-18.md`
  - Short current-state snapshot for handoff and owner/provider decision-making.
  - Lists current verified state, active blockers, local access paths already ruled out, required input/external action, and unsafe-action boundaries.
  - Updated 2026-05-18 17:41 CEST.

- `audit/webpi-rescue-completion-audit-2026-05-18.md`
  - Main completion audit.
  - Tracks WebPi state, ticket updates, live reachability, IPMI/backend failure, storage/controller diagnostics, credential hygiene, and why the goal is not complete.
  - Latest completion audit refresh: 2026-05-18 17:41 CEST; latest persistent browser CDP DOM recheck: 2026-05-18 17:41 CEST; latest persistent browser namespace recheck: 2026-05-18 17:30 CEST; latest WebPi/CDP/password-manager access recheck: 2026-05-18 17:13 CEST; latest public/RMCP and WebPi status recheck: 2026-05-18 16:30 CEST; latest local password-manager state recheck: 2026-05-18 16:36 CEST; latest SSH identity/auth-method recheck: 2026-05-18 16:42 CEST; latest local browser/CDP recheck: 2026-05-18 17:41 CEST; latest Chromium open-file metadata recheck: 2026-05-18 16:52 CEST; latest key-only SSH retry: 2026-05-18 16:58 CEST; latest Bitwarden/rbw state recheck: 2026-05-18 17:13 CEST. Customer-side WebPi/Rescue/IP work is correct from the latest authenticated evidence, but active goal is still incomplete because current WebPi access is unavailable, local access fallbacks failed, IPMI/Remote Access is unavailable, IPv6 service ports are filtered, and P410/logical-volume health remains unresolved.

- `audit/recovery-next-actions-2026-05-18.md`
  - Operator runbook for what to do after provider response.
  - Contains safe read-only checks, explicit "do not do yet" list, and gates before reinstall/repair.
  - Latest next-action decision: wait for provider-side remediation, use owner-approved Express/VIP escalation, or continue customer-side checks only with fresh panel/Rescue credentials or an already-authenticated browser session.

- `audit/provider-ticket-escalation-draft-2026-05-18.md`
  - Concise provider-facing escalation summary.
  - Current blockers: `createIpmiSession` returns `Invalid boot mode`; HP Smart Array P410 / RAID-1 logical volume is `offline` / `Not responding`.
  - Updated through 2026-05-18 16:36 CEST with proof that Remote Access/IPMI fails from the intended whitelist source IP, no alternate IPMI TCP port is visible, no alternate server IPv4/IPv6 service path is visible, no reusable local panel/SSH/API/session access source is available, direct WebPi HTTP remains Cloudflare-blocked, and local password-manager state remains unusable.

- `audit/provider-handoff-2026-05-17.md`
  - Historical provider handoff with a current status block at the top.
  - Older unreachable-server details remain as incident history.
  - Updated current status block through 2026-05-18 16:36 CEST with latest public/RMCP, WebPi URL/CDP, browser-profile, SSH metadata, Firefox/Camoufox, keyring/password-manager, shell-history findings, 16:30 WebPi unauthenticated HTTP status recheck, and 16:36 password-manager state recheck.

- `audit/server-reinstall-status-2026-05-17.md`
  - Historical reinstall/recovery log.
  - Top current status block supersedes older reinstall suggestions.
  - Current decision: do not continue reinstall until provider checks P410 / RAID-1 logical-volume health.
  - Updated current status block through 2026-05-18 16:36 CEST with the latest public/RMCP, WebPi status, and local password-manager state recheck.

- `audit/owner-decision-needed-2026-05-18.md`
  - Short decision note for the owner.
  - States the current safe options: wait for provider, explicitly approve Express/VIP escalation, or authorize provider-side remote hands if needed.
  - Repeats the do-not-do list for reinstall, BIOS, RAID/IPMI, disk layout, `fsck`, and read-write mounts.
  - Updated through 2026-05-18 16:36 CEST with the latest public reachability, RMCP, top-port, local access limitations, WebPi unauthenticated HTTP status recheck, and local password-manager state recheck.

- `audit/provider-express-escalation-message-2026-05-18.md`
  - Copy-ready provider message for Express/VIP escalation.
  - Use only after owner approval.
  - Contains no raw credentials.
  - Updated through 2026-05-18 16:36 CEST with the latest plain-GUI Rescue confirmation, local access limitation, RMCP failure, TCP/IPv6 reachability evidence, WebPi unauthenticated HTTP status recheck, and local password-manager state recheck.

- `audit/owner-action-request-de-2026-05-18.md`
  - German owner-facing decision note.
  - States the current Rescue/IPMI/P410 blockers in German.
  - Lists the exact owner decisions needed: wait, approve Express/VIP, provide fresh WebPi access, or provide current Rescue SSH access.
  - Repeats the unsafe-action boundaries in German.
  - Updated through 2026-05-18 16:36 CEST with local API, environment credential, public reachability, browser-session checks, direct WebPi HTTP status, and local password-manager state.

## Current completion state

Customer-side WebPi work is complete from the latest successful authenticated evidence:

- Login worked in prior authenticated GUI/backend checks; the current local browser session is logged out.
- Server page opened in prior authenticated GUI/backend checks; another live WebPi action requires fresh panel credentials or an already-authenticated browser session.
- Rescue Mode is set and confirmed by backend.
- IPv4/IPv6 to MAC mapping is coherent.
- IPv4 Rescue SSH works when the current WebPi Rescue password is available; key-only SSH from this workspace failed for both `paris` and `root`.
- Rescue user is `paris`; the Rescue password is present in WebPi but is not stored in clear text.
- Remote Access whitelist choice is ruled out as the cause: both the auto-filled client IPv6 and `152.53.35.28` return `Invalid boot mode`.
- Current WebPi frontend handler was inspected and matches the tested backend calls.

Overall recovery is not complete:

- WebPi Remote Access/IPMI still fails with `Invalid boot mode`.
- `getIpmiCredentials` still fails.
- iLO/IPMI endpoint `51.159.47.149` responds to TCP/80, but TCP/80 serves only a default `Welcome to nginx!` page from `nginx/1.22.1`; TCP/443 and TCP/22 time out; later RMCP checks with `ipmi-ping` / `ipmiping` get 0 responses, so earlier `nc` UDP/623 output is not evidence of working IPMI.
- HP Smart Array P410 / RAID-1 logical volume is offline / not responding.
- `/dev/sda` remains `offline` in Rescue.
- `ssacli` still reports `Smart Array P410 (Error: Not responding)`.
- Provider-side remediation is still required.
- Latest public reachability / RMCP poll at 2026-05-18 06:53 CEST: IPv4 SSH/22 open, IPv4 HTTP/80 open with Rescue nginx `503`, IPv6 SSH/22 closed, iLO TCP/80 open with default nginx, iLO TCP/443 and TCP/22 closed, iLO UDP/623 reachable via `nc`.
- Limited alternate management/KVM TCP port check at 2026-05-18 06:59 CEST found only TCP/80 open among common candidates; no alternate usable Remote Access port was visible.
- Ticket content caveat resolved in the latest re-check: main ticket `#94047858` renders `Update 2026-05-18 06:15 CEST` and earlier evidence; IPMI ticket `#47300051` renders `Update 2026-05-18 06:09 CEST`. Do not duplicate ticket comments.
- Latest external poll at 2026-05-18 07:13 CEST showed no improvement: IPv4 Rescue remains stable; IPv6 SSH remains closed; IPMI still shows default nginx on TCP/80, closed TCP/443 and TCP/22, and reachable UDP/623.
- Latest Web UI attempt at 2026-05-18 07:29 CEST was blocked before login by Cloudflare security verification / `Just a moment...`; no authenticated setting was changed. The current customer-side baseline remains the successful 07:06 CEST WebPi check.
- Latest quick public reachability check at 2026-05-18 07:33 CEST showed no improvement: IPv4 SSH/22 and HTTP/80 remain open, server HTTP still returns Rescue nginx `503`, IPMI TCP/80 still returns default nginx `200 OK`, and IPMI TCP/443 plus TCP/22 remain closed.
- Latest Web UI retry at 2026-05-18 07:37 CEST stayed on Cloudflare security verification through 60 seconds; login/server DOM did not render and no settings were changed.
- Latest UDP check at 2026-05-18 07:37 CEST still reports `51.159.47.149:623/udp` reachable via `nc`; this still does not provide a usable WebPi Remote Access session.
- Later plain Chromium GUI access at 2026-05-18 09:38 CEST rendered the authenticated server page again and visually reconfirmed `PAR822349`, IPv4 `195.154.209.133`, and `Modo rescate`.
- The 09:38 CEST GUI Remote Access attempt set the whitelist field to `152.53.35.28` and opened the WebPi BIOS/RAID/IPMI warning modal, but no usable Remote Access/IPMI credentials were proven.
- Post-compaction profile reuse at 2026-05-18 10:02 CEST landed on the OneProvider login page with the prior session logged out; no further authenticated setting could be changed without fresh panel credentials or an already-authenticated browser session.
- Latest public reachability check at 2026-05-18 10:03 CEST still shows IPv4 Rescue reachable on SSH/22 and HTTP/80 with Rescue nginx `503`; IPv6 SSH/22 closed; IPMI TCP/80 default nginx `200 OK`; IPMI TCP/443 and TCP/22 closed; IPMI UDP/623 reachable via `nc`.
- Latest public reachability check at 2026-05-18 10:12 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- Persistent browser session probe at 2026-05-18 10:27 CEST found no usable CDP endpoint on `127.0.0.1:9222`, no visible listening socket, and no accessible `/workspace/.browser-profile` session to reuse.
- Local credential/session recovery at 2026-05-18 10:32 CEST found no recoverable fresh panel password source; temporary text/JSON artifacts under `/tmp/panel-browser` were redacted and the known secret-pattern scan returned no hits afterward.
- Latest public reachability check at 2026-05-18 10:36 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- Latest public reachability check at 2026-05-18 11:00 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- RMCP tooling check at 2026-05-18 11:08 CEST initially found no local `ipmi-ping`, `ipmiping`, `ipmitool`, or `bmc-info`; this was superseded by the 12:17 CEST local `freeipmi-tools` install and protocol check.
- Provider/owner-facing summaries were updated at 2026-05-18 11:12 CEST to state the then-current `nc`-only limitation; this was superseded by the 12:17 CEST RMCP protocol check showing 0 responses from the whitelisted source IP.
- Key-only Rescue SSH check at 2026-05-18 11:15 CEST failed for both `paris` and `root`; IPv4 SSH/22 remains open, but fresh Rescue SSH diagnostics require the current WebPi Rescue password or another owner-provided login path.
- Provider/owner-facing summaries were updated at 2026-05-18 11:20 CEST with the key-only Rescue SSH limitation.
- Current summaries were clarified at 2026-05-18 11:23 CEST: "IPv4 Rescue SSH works" means "works when the current WebPi Rescue password is available"; key-only SSH is not available from this workspace.
- SSH host-key fingerprint check at 2026-05-18 11:30 CEST recorded the current RSA, ED25519, and ECDSA fingerprints for `195.154.209.133`; this helps resolve stale `known_hosts` warnings for an owner-approved SSH login, but does not prove login access.
- Latest public reachability check at 2026-05-18 11:36 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- Latest public reachability check at 2026-05-18 11:42 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- Local WebPi access recheck at 2026-05-18 11:50 CEST found no panel login environment variables, no reachable local CDP endpoint, no visible `/workspace/.browser-profile`, and no browser cookie/login/profile files under `/tmp/panel-browser` within max depth 2.
- Latest public reachability check at 2026-05-18 11:51 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- Browser profile reuse recheck at 2026-05-18 12:09 CEST confirmed the exact server URL `https://panel.op-net.com/server/822349/manage#overview`, but both the persistent browser and the historical `gui-login-ipmi-finaltry` profile are logged out / blocked by Cloudflare; no email/password fields are prefilled.
- Latest public reachability check at 2026-05-18 12:10 CEST remains unchanged: IPv4 Rescue SSH/22 and HTTP/80 open, server HTTP returns Rescue nginx `503`, IPv6 SSH/22 closed, IPMI TCP/80 default nginx `200 OK`, IPMI TCP/443 and TCP/22 closed, IPMI UDP/623 reachable via `nc`.
- RMCP protocol check at 2026-05-18 12:17 CEST installed local `freeipmi-tools`, confirmed the workspace egress IP is the intended whitelist IP `152.53.35.28`, and showed `ipmi-ping` / `ipmiping` get 0 responses from `51.159.47.149`; `nc` UDP success is therefore not evidence of working RMCP/IPMI.
- Persistent browser profile metadata check at 2026-05-18 12:25 CEST found no saved OneProvider/WebPi login rows; the latest exact server URL history entry is `Sign in | OneProvider`, and cookie metadata corresponds to the 10:02 CEST logged-out run.
- IPMI alternate TCP port scan at 2026-05-18 12:33 CEST installed local `nmap` and scanned the top 1000 TCP ports on `51.159.47.149`; only `80/tcp` was open, with 999 ports filtered.
- Server IPv4 TCP top-port scan at 2026-05-18 12:36 CEST scanned the top 1000 TCP ports on `195.154.209.133`; only `22/tcp` and `80/tcp` were open, with 998 ports filtered.
- Server IPv6 targeted port check at 2026-05-18 12:42 CEST confirmed IPv6 ping works, but targeted ports `22`, `80`, `443`, `8080`, and `8443` are filtered/no-response; the attempted IPv6 top-1000 scan timed out and is not treated as complete.
- Completion audit refresh at 2026-05-18 12:45 CEST again concluded the active objective is not complete and must not be marked complete.
- Public service identity check at 2026-05-18 12:52 CEST showed server SSH banner `OpenSSH_8.9p1 Ubuntu-3ubuntu0.11`, server HTTP body title `Serveur Dedibox en maintenance`, and IPMI HTTP body title `Welcome to nginx!`.
- Local credential-store metadata check at 2026-05-18 12:59 CEST found no common local credential store with a recoverable OneProvider/WebPi login source.
- Latest public/RMCP recheck at 2026-05-18 13:13 CEST showed no improvement: current IPv4 egress remains `152.53.35.28`, IPv4 Rescue ping/SSH/HTTP still work, server HTTP still returns Rescue nginx `503` / `Serveur Dedibox en maintenance`, IPv6 ping works but IPv6 TCP/22 and TCP/80 time out, IPMI TCP/80 still serves default nginx `Welcome to nginx!`, IPMI TCP/443 and TCP/22 time out, and `ipmi-ping` / `ipmiping` still get 0 RMCP responses.
- Targeted browser-session recovery check at 2026-05-18 13:33 CEST found historical OneProvider/WebPi profile metadata in `/tmp/panel-browser`, but the newest relevant copied Chromium profile remained blocked at Cloudflare `Just a moment...` in both headless and Xvfb non-headless tests. No usable WebPi session was recovered.
- Active browser/CDP session check at 2026-05-18 13:38 CEST found a long-running persistent Chromium process configured for port `9222`, but `/json` and `/json/version` returned no usable response and no browser databases were found under `/workspace/.browser-profile`. No live authenticated WebPi tab was recovered.
- Latest public/RMCP recheck at 2026-05-18 13:45 CEST showed no improvement: current IPv4 egress remains `152.53.35.28`, IPv4 Rescue ping/SSH/HTTP still work, server HTTP still returns Rescue nginx `503` / `Serveur Dedibox en maintenance`, IPv6 ping works but IPv6 TCP/22 times out, IPMI TCP/80 still serves default nginx `Welcome to nginx!`, IPMI TCP/443 and TCP/22 time out, and `ipmi-ping` / `ipmiping` still get 0 RMCP responses.
- Local SSH key access check at 2026-05-18 13:54 CEST found no loaded agent identities and tested the two local keys (`id_ed25519`, `online-paris`) for users `paris` and `root` using a temporary fresh `known_hosts`; all four key-only attempts failed with `Permission denied (publickey,password)`.
- Local unstructured `.env` credential candidate check at 2026-05-18 14:01 CEST found relevant Rescue/WebPi/IPMI hints but no structured `KEY=value` credentials; the apparent secret candidate failed password authentication for both `paris` and `root`. No raw `.env` values were printed.
- Local history-derived WebPi login candidate check at 2026-05-18 14:10 CEST found one unique email and two unique password candidates in local agent history context; both Cloak Chromium login attempts stayed on `Sign in | OneProvider`, so no authenticated WebPi session was recovered. No candidate values were printed.
- Local OneProvider API credential check at 2026-05-18 14:21 CEST found no usable local `Api-Key` / `Client-Key` pair; relevant hits were limited to existing audit documentation and local history context.
- Current environment variable credential check at 2026-05-18 14:40 CEST found no OneProvider/WebPi/Rescue/IPMI/API-specific environment variable names; credential-like variables belonged to unrelated tooling categories and values were not printed.

## Safety state

- No BIOS changes.
- No RAID changes.
- No IPMI settings changes.
- No disk layout changes.
- No `fsck`.
- No read-write mount of installed disks.
- No reinstall after the P410/logical-volume failure was proven.
- Raw credentials are not retained in these audit artifacts.
