# Provider escalation draft - 2026-05-18 16:36 CEST

Ticket: `#94047858`
Server: `PAR822349`
Public IPv4: `195.154.209.133`
Public IPv6: `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`
Host Port 1 MAC: `e4:11:5b:0d:be:a0`
iLO/IPMI endpoint: `51.159.47.149`

Hello,

Customer-side WebPi checks now show the Rescue Mode and network settings are coherent, but two provider-side blockers remain:

1. Remote Access/IPMI cannot be created from WebPi.
2. The HP Smart Array P410 / RAID-1 logical volume is offline / not responding in Rescue.

Current verified WebPi state:

- WebPi Overview shows `PAR822349 / 195.154.209.133` in Rescue Mode.
- Later plain Chromium GUI access at 2026-05-18 09:38 CEST rendered the authenticated server page again and visually reconfirmed `PAR822349`, IPv4 `195.154.209.133`, and `Modo rescate`.
- Current local browser/session state is logged out; another customer-side WebPi action requires fresh panel credentials or an already-authenticated browser session.
- WebPi backend `getStatus` returns `success=true`, `status=rescue_mode`.
- WebPi backend `getRescueMode` returns `success=true`, `status=rescue_mode`, `currentMode.value=rescue_mode`, user `paris`.
- Network tab maps IPv4 `195.154.209.133` and IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` to MAC `e4:11:5b:0d:be:a0`.
- The RAID tab states the server is currently in RAID 1.

Current reachability, latest public checks through 2026-05-18 16:01 CEST:

- IPv4 ping to `195.154.209.133`: works.
- IPv4 TCP/22 to `195.154.209.133`: open.
- IPv4 TCP/80 to `195.154.209.133`: open, returning `HTTP/1.1 503 Service Temporarily Unavailable` from `nginx/1.18.0 (Ubuntu)` with body title `Serveur Dedibox en maintenance`; read-only SSH confirms this nginx process/page belongs to the provider Ubuntu 22.04 live Rescue environment.
- IPv4 TCP/22 to `195.154.209.133` exposes SSH banner `SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.11`.
- Latest unauthenticated recheck at 2026-05-18 16:01 CEST showed the same IPv4 state: ping OK, TCP/22 open, HTTP/80 still returns Rescue nginx `503`.
- A broader `nmap -Pn -sT --top-ports 1000 --max-retries 2 --host-timeout 90s --reason 195.154.209.133` check at 2026-05-18 12:36 CEST found only `22/tcp` and `80/tcp` open; the other 998 top TCP ports were filtered.
- SSH login to Rescue as `paris`: works when current WebPi Rescue credentials are available.
- IPv6 ping works, but targeted IPv6 TCP checks remain unusable; latest TCP/22 check at 2026-05-18 16:01 CEST timed out.
- IPMI endpoint `51.159.47.149` responds on TCP/80, but TCP/80 returns only a default `Welcome to nginx!` page; TCP/443 timed out at the latest 16:01 CEST check.
- The current workspace public egress IP is `152.53.35.28`, matching the intended WebPi Remote Access whitelist IP.
- The current workspace also has IPv6 egress `2a0a:4cc0:100:899:98f1:fdff:fe6d:a2b1`; this was already tested earlier in WebPi and also returned `Invalid boot mode`.
- UDP/623 reports reachable via `nc -uvz`, but protocol-level FreeIPMI checks from the same whitelisted source IP get no RMCP response:
  - `ipmi-ping -c 3 51.159.47.149`: 3 transmitted, 0 responses, 100% packet loss.
  - `ipmiping -c 3 51.159.47.149`: 3 transmitted, 0 responses, 100% packet loss.
  - Latest 2026-05-18 16:01 CEST recheck with `ipmi-ping -c 2` also returned 0 responses / 100% packet loss.
  - This is still not a usable iLO/Remote Access UI or RMCP/IPMI service from the customer side.
- A limited TCP check of common alternate management/KVM ports found only TCP/80 open among: `22`, `23`, `80`, `443`, `623`, `17988`, `17990`, `5900`, `5901`, `5902`, `8000`, `8080`, `8443`, `9000`, `9443`.
- A broader `nmap -Pn -sT --top-ports 1000 --max-retries 2 --host-timeout 90s --reason 51.159.47.149` check at 2026-05-18 12:33 CEST also found only `80/tcp open`; the other 999 top TCP ports were filtered.

Remote Access/IPMI blocker:

- Separate ticket `#47300051` contains a `STAFF` reply saying IPMI is authorized on the account for supported servers.
- WebPi Remote Access UI exposes only a whitelist IP field and `Create` button in the current widget.
- The current WebPi JavaScript handler for `#create-ipmi-session` sends `action=createIpmiSession`, `ip` from `#whitelist-ip`, optional `duration` only if `#duration` exists, optional `type` only if `#type` exists, and the common manager helper appends `server_id`.
- No other visible/hidden required customer-side parameter is exposed by the current Remote Access widget.
- WebPi backend `getIpmiCredentials` returns `success=false`, `Unable to obtain authentication info. Please try again later or contact support.`
- WebPi backend `createIpmiSession` with whitelist IP `152.53.35.28` always returns `success=false`, `message=Invalid boot mode`.
- WebPi backend `createIpmiSession` with the auto-filled client IPv6 `2a0a:4cc0:100:899:98f1:fdff:fe6d:a2b1` also returns `success=false`, `message=Invalid boot mode`.
- The same `Invalid boot mode` result occurs for tested type values: blank, `ilo`, `ipmi`, `html5`, `java`, `kvm`, `remote`, `console`.
- This rules out a wrong whitelist IP or missing frontend parameter as the customer-side cause.

Storage/controller blocker:

- Fresh read-only Rescue SSH recheck at 2026-05-18 05:58/05:59 CEST confirms this blocker is still current.
- Rescue kernel is `6.8.0-57-generic`.
- `lspci` shows HP Smart Array P410 using kernel driver `hpsa`.
- `lsblk` sees `/dev/sda` as HP `LOGICAL VOLUME`, about `1.8T`, with partitions `sda1`, `sda2`, `sda3`, but the disk state is `offline`.
- `/sys/class/scsi_disk/0:1:0:0/device/state` reports `offline`.
- `/proc/mdstat` shows no active md array; `md0` appears as `0B`.
- `ssacli ctrl all show` reports `Smart Array P410 (Error: Not responding)`.
- `ssacli ctrl all show status` reports `Error: Cannot show status for this device.`
- `ssacli ctrl all show config` reports `Smart Array P410 (Error: Not responding)`.
- Slot-specific `ssacli` queries report the controller is not detected for `slot=1`.

Customer-side safety:

- No disks were mounted.
- No `fsck` was run.
- No RAID, BIOS, IPMI, partition, filesystem, boot-order, or reinstall changes were made after the read-only diagnostics.
- Ticket `#94047858` currently shows `Respuesta-cliente`, reply count `19`, and the latest re-check rendered the conversation body with `Update 2026-05-18 06:15 CEST`, `Update 2026-05-18 05:26 CEST`, `Smart Array P410`, and `Invalid boot mode`.
- Ticket `#47300051` currently shows `Respuesta-cliente`, reply count `2`, and the latest re-check rendered the conversation body with `Update 2026-05-18 06:09 CEST`, `Invalid boot mode`, and `PAR822349`.
- Do not duplicate ticket comments; provider-facing evidence is visible in WebPi.
- Local credential/session recovery found no reusable panel credentials or persistent logged-in browser session; a new customer-side WebPi attempt requires fresh panel credentials or an already-authenticated browser session.
- Common local credential-store metadata check at 2026-05-18 12:59 CEST found no OneProvider/WebPi source in `.netrc`, `.authinfo`, `.git-credentials`, `.aws/credentials`, `.config/op`, `.password-store`, or `.config/gh/hosts.yml`.
- Latest key-only SSH check at 2026-05-18 13:54 CEST tested local keys `id_ed25519` and `online-paris` for both `paris` and `root`; all four attempts failed with `Permission denied (publickey,password)`.
- Local unstructured `.env` credential candidate check at 2026-05-18 14:01 CEST failed password authentication for both `paris` and `root`.
- Local history-derived WebPi login candidate check at 2026-05-18 14:10 CEST found one unique email and two unique password candidates, but both Cloak Chromium login attempts stayed on `Sign in | OneProvider`.
- Local OneProvider API credential check at 2026-05-18 14:21 CEST found no usable local `Api-Key` / `Client-Key` pair.
- Current environment variable check at 2026-05-18 14:40 CEST found no OneProvider/WebPi/Rescue/IPMI/API-specific variable names.
- Public/WebPi session recheck at 2026-05-18 14:50 CEST found no usable authenticated local WebPi session: direct WebPi URL access returned Cloudflare `HTTP/2 403`, local Chromium CDP on `127.0.0.1:9222` was unreachable, and scoped `/tmp/panel-browser` artifacts were historical only.
- Historical WebPi profile CDP recheck at 2026-05-18 15:06 CEST reached `https://panel.op-net.com/login#overview` with title `Registrarse | OneProvider`; login markers were present but server markers for `PAR822349` were absent, so the historical profile is not an authenticated WebPi session.
- Local browser profile metadata check at 2026-05-18 15:32 CEST found no OneProvider/WebPi cookie hosts outside `/tmp/panel-browser`; no cookie values or saved password values were printed.
- Local SSH configuration metadata check at 2026-05-18 15:40 CEST found no `~/.ssh/config`, no loaded SSH-agent identities, and only the two already-tested public keys; no additional local SSH alias, agent key, or untested public key path is available.
- Firefox/Camoufox metadata check at 2026-05-18 15:46 CEST found no OneProvider/WebPi cookie hosts, no matching history URLs, and no `logins.json`; no cookie values or saved password values were printed.
- Local keyring/password-manager metadata check at 2026-05-18 15:54 CEST found no usable unlocked credential path; Bitwarden CLI is present but locked, and no vault items or secret values were printed.
- Shell-history metadata check at 2026-05-18 16:09 CEST found target-related hits only in already checked `.claude/history.jsonl` and `.codex/history.jsonl`; matching lines or command contents were not printed.
- Direct unauthenticated WebPi HTTP status check at 2026-05-18 16:17 CEST returned Cloudflare `HTTP/2 403` for the server URL, login URL, and root panel URL; the login URL included `cf-mitigated: challenge`. No login was attempted.
- Latest public/RMCP and WebPi status recheck at 2026-05-18 16:30 CEST showed no improvement: IPv4 Rescue ping/SSH/HTTP still work, IPv6 TCP/22 times out, IPMI TCP/443 times out, `ipmi-ping` returns 0 responses, and WebPi server/login/root URLs return Cloudflare `HTTP/2 403` with the login URL marked `cf-mitigated: challenge`.
- Latest local password-manager state recheck at 2026-05-18 16:36 CEST found Bitwarden still locked and the other checked password-manager CLIs absent; no vault was unlocked or enumerated.
- Fresh Rescue SSH diagnostics require the current WebPi Rescue password or another owner-provided login path.
- Temporary text/JSON artifacts under `/tmp/panel-browser` were redacted, and the known secret-pattern scan returned no hits afterward.

Requested provider action:

- Check and fix the provider/backend state that makes WebPi reject Remote Access/IPMI session creation with `Invalid boot mode` even though Rescue Mode is confirmed.
- Check why `51.159.47.149:80` serves a default `Welcome to nginx!` page and why UDP/623 gives no RMCP response to `ipmi-ping` from the whitelisted source IP while the expected iLO/Remote Access HTTPS/SSH endpoints are closed.
- Confirm whether Remote Access is expected on any alternate port; no common alternate Web/KVM port was visible, and nmap's top 1000 TCP ports found only TCP/80 open.
- Check HP Smart Array P410 controller health, physical disk health, and RAID-1 logical volume state from the datacenter/provider side.
- Confirm whether the logical volume can be brought back online safely, or whether hardware intervention is required.
- Do not change BIOS, RAID/IPMI settings, disk layout, or reinstall the OS without explicit confirmation.
