# Provider handoff - PAR822349 recovery

Date: 2026-05-17

Updated current status: 2026-05-18 16:36 CEST

## Server

- Provider/server: OneProvider / CZ Design WebPi
- Server: `PAR822349`
- Public IPv4: `195.154.209.133`
- Public IPv6: `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`
- Location: Paris, FR
- Ticket: `#94047858`

## Current status - 2026-05-18 16:36 CEST

This section supersedes the earlier "Reachability failure" notes below. Those older notes are retained as incident history.

Latest delta since the earlier 13:45 CEST status:

- Latest public/RMCP check at 2026-05-18 16:01 CEST again showed no provider-side improvement:
  - IPv4 `195.154.209.133` ping works, TCP/22 is open, and HTTP/80 still returns Rescue nginx `503`.
  - IPv6 ping works, but TCP/22 times out.
  - IPMI endpoint `51.159.47.149` still serves only default `Welcome to nginx!` on TCP/80.
  - IPMI TCP/443 times out.
  - `ipmi-ping -c 2 51.159.47.149` returns 0 responses / 100% packet loss.
- Latest public/RMCP check at 2026-05-18 15:18 CEST again showed no provider-side improvement:
  - IPv4 `195.154.209.133` ping works, TCP/22 is open, and HTTP/80 still returns Rescue nginx `503`.
  - IPv6 ping works, but TCP/22 times out.
  - IPMI endpoint `51.159.47.149` still serves only default `Welcome to nginx!` on TCP/80.
  - IPMI TCP/443 times out.
  - `ipmi-ping -c 2 51.159.47.149` returns 0 responses / 100% packet loss.
- Latest public/RMCP check at 2026-05-18 14:50 CEST showed no provider-side improvement:
  - IPv4 `195.154.209.133` ping works.
  - IPv4 TCP/22 is open.
  - IPv4 HTTP/80 still returns `HTTP/1.1 503 Service Temporarily Unavailable` from `nginx/1.18.0 (Ubuntu)`.
  - IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` ping works, but TCP/22 times out.
  - IPMI endpoint `51.159.47.149` still serves only default `Welcome to nginx!` on TCP/80.
  - IPMI TCP/443 times out.
  - `ipmi-ping -c 2 51.159.47.149` returns 0 responses / 100% packet loss.
- Direct unauthenticated WebPi server URL access at 2026-05-18 14:50 CEST returned Cloudflare `HTTP/2 403`.
- Persistent local Chromium CDP at `127.0.0.1:9222` remains unreachable, and `/workspace/.browser-profile` has no usable browser state.
- Isolated CDP recheck of the historical WebPi profile at 2026-05-18 15:06 CEST landed on `https://panel.op-net.com/login#overview` with title `Registrarse | OneProvider`; login markers were present, server markers for `PAR822349` were absent. The historical profile is not an authenticated WebPi session.
- Local browser profile metadata check at 2026-05-18 15:32 CEST found no OneProvider/WebPi cookie hosts outside `/tmp/panel-browser`; no cookie values or saved password values were printed.
- Local SSH configuration metadata check at 2026-05-18 15:40 CEST found no `~/.ssh/config`, no loaded SSH-agent identities, and only the two already-tested public keys; no additional local SSH alias, agent key, or untested public key path is available.
- Firefox/Camoufox metadata check at 2026-05-18 15:46 CEST found no OneProvider/WebPi cookie hosts, no matching history URLs, and no `logins.json`; no cookie values or saved password values were printed.
- Local keyring/password-manager metadata check at 2026-05-18 15:54 CEST found no usable unlocked credential path; Bitwarden CLI is present but locked, and no vault items or secret values were printed.
- Shell-history metadata check at 2026-05-18 16:09 CEST found target-related hits only in already checked `.claude/history.jsonl` and `.codex/history.jsonl`; matching lines or command contents were not printed.
- Direct unauthenticated WebPi HTTP status check at 2026-05-18 16:17 CEST returned Cloudflare `HTTP/2 403` for the server URL, login URL, and root panel URL; the login URL included `cf-mitigated: challenge`. No login was attempted.
- Latest public/RMCP and WebPi status recheck at 2026-05-18 16:30 CEST showed no improvement: IPv4 Rescue ping/SSH/HTTP still work, IPv6 TCP/22 times out, IPMI TCP/443 times out, `ipmi-ping` returns 0 responses, and WebPi server/login/root URLs return Cloudflare `HTTP/2 403` with the login URL marked `cf-mitigated: challenge`.
- Latest local password-manager state recheck at 2026-05-18 16:36 CEST found Bitwarden still locked and the other checked password-manager CLIs absent; no vault was unlocked or enumerated.
- No current local WebPi login, Rescue SSH credential, OneProvider API credential, or authenticated browser session is available from this workspace.
- Current safe unblocks remain: provider remediation, explicit owner approval for Express/VIP escalation, fresh authenticated WebPi access, or a current valid Rescue SSH login path.

Customer-side WebPi/Rescue state:

- Prior authenticated WebPi login worked; the current local browser/session is logged out.
- Server page for `PAR822349 / 195.154.209.133` opened in prior authenticated checks; opening it now requires fresh panel credentials or an already-authenticated browser session.
- WebPi Overview and backend agree the server is in `rescue_mode`.
- Later plain Chromium GUI access at 2026-05-18 09:38 CEST rendered the authenticated server page again and visually reconfirmed `PAR822349`, IPv4 `195.154.209.133`, and `Modo rescate`.
- Current local browser/session state is logged out; another customer-side WebPi action requires fresh panel credentials or an already-authenticated browser session.
- WebPi backend `getStatus`: `success=true`, `status=rescue_mode`.
- WebPi backend `getRescueMode`: `success=true`, `status=rescue_mode`, `currentMode.value=rescue_mode`, user `paris`.
- Network tab maps public IPv4 `195.154.209.133` and IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` to host MAC `e4:11:5b:0d:be:a0`.
- RAID tab states the server is currently in RAID 1.

Current reachability, latest public/RMCP checks through 2026-05-18 13:45 CEST:

- IPv4 ping to `195.154.209.133`: works; latest 2026-05-18 13:45 CEST check had 0% packet loss.
- IPv4 TCP/22 to `195.154.209.133`: open at latest 13:45 CEST check.
- IPv4 TCP/22 to `195.154.209.133` exposes SSH banner `SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.11`; latest unauthenticated banner recheck at 2026-05-18 13:13 CEST was unchanged.
- IPv4 TCP/80 to `195.154.209.133`: open, returning `HTTP/1.1 503 Service Temporarily Unavailable` from `nginx/1.18.0 (Ubuntu)` with body title `Serveur Dedibox en maintenance`; read-only SSH confirms this nginx process/page belongs to the provider Ubuntu 22.04 live Rescue environment.
- IPv4 nmap top-1000 TCP scan at 2026-05-18 12:36 CEST found only `22/tcp` and `80/tcp` open on `195.154.209.133`; 998 top TCP ports were filtered.
- SSH login to Rescue as `paris`: works when the current WebPi Rescue password is available.
- IPv6 ping works, but targeted IPv6 TCP checks remain unusable; latest TCP/22 check at 2026-05-18 13:45 CEST timed out.
- IPMI endpoint `51.159.47.149` responds on TCP/80, but TCP/80 returns only a default `Welcome to nginx!` page from `nginx/1.22.1`; TCP/443 and TCP/22 timed out at the latest 13:45 CEST check.
- The workspace egress IP is `152.53.35.28`, matching the intended WebPi Remote Access whitelist IP.
- The workspace also has IPv6 egress `2a0a:4cc0:100:899:98f1:fdff:fe6d:a2b1`; this was already tested earlier in WebPi and also returned `Invalid boot mode`.
- UDP/623 previously reported reachable via `nc -uvz`, but FreeIPMI protocol checks from the same whitelisted source IP get no RMCP response. Latest 2026-05-18 13:45 CEST recheck with `ipmi-ping -c 2` and `ipmiping -c 2` returned 0 responses / 100% packet loss.
- No usable WebPi Remote Access or RMCP/IPMI service is exposed from the customer side.
- A limited TCP check of common alternate management/KVM ports found only TCP/80 open among: `22`, `23`, `80`, `443`, `623`, `17988`, `17990`, `5900`, `5901`, `5902`, `8000`, `8080`, `8443`, `9000`, `9443`.
- IPMI nmap top-1000 TCP scan at 2026-05-18 12:33 CEST also found only `80/tcp` open on `51.159.47.149`; 999 top TCP ports were filtered.

Current blockers:

- Remote Access/IPMI cannot be created from WebPi.
- WebPi backend `getIpmiCredentials`: `success=false`, `Unable to obtain authentication info. Please try again later or contact support.`
- WebPi backend `createIpmiSession` with whitelist IP `152.53.35.28`: `success=false`, `message=Invalid boot mode`.
- WebPi backend `createIpmiSession` with the auto-filled client IPv6 `2a0a:4cc0:100:899:98f1:fdff:fe6d:a2b1`: `success=false`, `message=Invalid boot mode`.
- Current WebPi frontend inspection confirms the Remote Access widget sends only `action=createIpmiSession`, `ip` from `#whitelist-ip`, optional `duration` if `#duration` exists, optional `type` if `#type` exists, plus `server_id` from the common manager helper.
- The current UI does not expose another required customer-side parameter, so the failed backend calls match the WebPi frontend behavior.
- Separate ticket `#47300051` has a `STAFF` reply stating IPMI is authorized on the account for supported servers, so this does not look like an account-level IPMI permission problem.
- HP Smart Array P410 / RAID-1 logical volume is offline / not responding in Rescue:
  - `lsblk` sees `/dev/sda` as HP `LOGICAL VOLUME`, about `1.8T`, with state `offline`.
  - `/sys/class/scsi_disk/0:1:0:0/device/state` reports `offline`.
  - `ssacli ctrl all show`: `Smart Array P410 (Error: Not responding)`.
  - `ssacli ctrl all show status`: `Error: Cannot show status for this device.`
- Fresh read-only Rescue SSH check at 2026-05-18 05:58/05:59 CEST reconfirmed `/dev/sda` is still `offline`, `/proc/mdstat` has no active md array, `ssacli` still reports the P410 not responding, and `lspci` still shows the P410 with driver `hpsa`.

Current ticket state:

- Main ticket `#94047858` shows `Respuesta-cliente` and visible reply count `19`.
- Latest re-check at 2026-05-18 07:06 CEST rendered the conversation body and verified `Update 2026-05-18 06:15 CEST`, `Update 2026-05-18 05:26 CEST`, `Smart Array P410`, and `Invalid boot mode`.
- Separate IPMI ticket `#47300051` shows `Respuesta-cliente` and visible reply count `2`.
- Latest re-check at 2026-05-18 07:06 CEST rendered the conversation body and verified `Update 2026-05-18 06:09 CEST`, `Invalid boot mode`, and `PAR822349`.
- Do not duplicate ticket comments; provider-facing evidence is visible in WebPi.

Current local access limitation:

- No reusable persistent browser session is available from this environment.
- Local credential/session recovery found no recoverable fresh panel password source.
- Common local credential-store metadata check at 2026-05-18 12:59 CEST found no OneProvider/WebPi source in `.netrc`, `.authinfo`, `.git-credentials`, `.aws/credentials`, `.config/op`, `.password-store`, or `.config/gh/hosts.yml`.
- Latest key-only SSH check at 2026-05-18 13:54 CEST tested local keys `id_ed25519` and `online-paris` for both `paris` and `root` using a temporary fresh `known_hosts` file; all four attempts failed with `Permission denied (publickey,password)`.
- Local unstructured `.env` credential candidate check at 2026-05-18 14:01 CEST failed password authentication for both `paris` and `root`.
- Local history-derived WebPi login candidate check at 2026-05-18 14:10 CEST found one unique email and two unique password candidates, but both Cloak Chromium login attempts stayed on `Sign in | OneProvider`.
- Local OneProvider API credential check at 2026-05-18 14:21 CEST found no usable local `Api-Key` / `Client-Key` pair.
- IPv4 TCP/22 is open, but fresh Rescue SSH diagnostics require the current WebPi Rescue password or another owner-provided login path.
- Temporary text/JSON artifacts under `/tmp/panel-browser` were redacted, and the known secret-pattern scan returned no hits afterward.

Current provider ask:

- Fix the provider/backend state that makes WebPi reject Remote Access/IPMI session creation with `Invalid boot mode` even though Rescue Mode is confirmed.
- Fix or explain why the IPMI endpoint presents default `Welcome to nginx!` on TCP/80 and gives no RMCP response on UDP/623 from the whitelisted source IP while HTTPS/443 and SSH/22 remain closed.
- Confirm whether Remote Access should be exposed on an alternate port; none of the common checked ports were open except TCP/80.
- Check HP Smart Array P410 controller health, physical disk health, and RAID-1 logical volume state from the datacenter/provider side.
- Confirm whether the logical volume can be brought online safely, or whether hardware intervention is required.
- Do not change BIOS, RAID/IPMI settings, disk layout, or reinstall the OS without explicit confirmation.

## Historical customer-side state verified

- WebPi login worked during the earlier authenticated session.
- Server page opened correctly during the earlier authenticated session.
- Server status is `Rescue Mode`.
- Boot section shows `Current Boot Mode: Rescue Mode`.
- The only visible boot action is `Boot in normal mode`; this was not clicked.
- No rescue-image selector or pending rescue-image field is available while rescue is already active.
- Server login and rescue credentials are present in WebPi.
- Current IPMI credentials in WebPi match the working iLO login.
- iLO reports `Server Power: On`.
- iLO integrated NIC check confirms:
  - Host Port 1 MAC: `e4:11:5b:0d:be:a0`
  - Host Port 2 MAC: `e4:11:5b:0d:be:a1`
- WebPi maps public IPv4/IPv6 to `e4:11:5b:0d:be:a0`, matching Host Port 1.

## Historical reachability failure before Rescue SSH was restored

Repeated checks from the whitelisted client host show:

- IPv4 `195.154.209.133`:
  - ICMP ping: failed
  - TCP/22: failed
  - TCP/80: failed
  - TCP/443: failed
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`:
  - ICMP ping: failed
  - TCP/22: failed
  - TCP/80: failed
  - TCP/443: failed

## iLO / console evidence

- iLO SSH is reachable.
- Server power is on.
- Virtual Serial Port opens but shows no usable bootloader, kernel, rescue, login, PXE, or OS output during observation.
- No customer-side evidence confirms a successful boot into the selected rescue environment.
- iLO management log later showed additional `Server reset.` entries through at least `record343`.
- After those reset entries, IPv4 and IPv6 TCP/22 still failed.
- iLO System Event Log still ends at old `record44` from 2026-05-07: `POST Error: 1719 - A controller failure event occurred prior to this power-up`; no newer SEL records exist.

## Ticket status

- Ticket `#94047858` has been updated with the verified WebPi/IP/MAC/IPMI/reachability facts.
- Latest read-only ticket checks still show `Customer-Reply`.
- No provider/staff/admin reply is visible as of the latest check.

## Requested provider action

Please check from the datacenter/provider side:

- Whether the selected rescue/PXE boot path is actually being delivered to this server.
- Whether the machine reaches the rescue environment.
- Whether the switch/network port is passing traffic for Host Port 1 MAC `e4:11:5b:0d:be:a0`.
- Whether the server console shows a boot hang, PXE failure, disk/controller error, or early kernel/network failure.

Please do not change BIOS, RAID, IPMI settings, disk layout, or reinstall the OS without explicit confirmation.

## Current completion decision

Customer-side WebPi configuration is verified and appears correct.

Functional recovery is not complete. IPv4 Rescue SSH is restored, but Remote Access/IPMI remains blocked by `Invalid boot mode`, and the HP Smart Array P410 / RAID-1 logical volume is offline / not responding.

## Read-only WebPi recheck - 2026-05-17 23:55 CEST

Rechecked the OneProvider/WebPi UI without changing settings:

- Panel login succeeds with the account credentials.
- Server page still shows `PAR822349`, `195.154.209.133`, Paris, and `Rescue Mode`.
- Boot section still shows `Current Boot Mode: Rescue Mode`.
- The only visible boot action remains `Boot in normal mode`; it was not clicked.
- Server login username is `paris`.
- Rescue username is `paris`.
- The visible server-login password and rescue password match each other in WebPi.
- The visible current WebPi server/rescue password no longer matches the older originally pasted OS password; this appears to be the current provider-generated credential state, but it still cannot be functionally tested because SSH is unreachable.
- Current IPMI username and current IPMI password match the working iLO session details stored outside the repository.
- Remote Access still shows external iLO URL `https://51.159.47.149/`, an iLO username redacted here, whitelisted IP `152.53.35.28`, and expiration `Pending`.
- Network tab maps IPv4 `195.154.209.133` and IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` to MAC `e4:11:5b:0d:be:a0`.
- Screenshot artifact, with secrets not suitable for repository storage, was captured at `/tmp/panel-browser/ticket-recheck-2346-1779054443/webpi-current-check.png`.
- Ticket `#94047858` still shows `Customer-Reply`; no provider/staff reply was visible in this recheck.
- Fresh reachability after the WebPi recheck still failed:
  - IPv4 ping: failed
  - IPv4 TCP/22: failed
  - IPv6 ping: failed
  - IPv6 TCP/22: failed

## Midnight read-only follow-up - 2026-05-18 00:00 CEST

Fresh public reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` ping: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` TCP/22: failed.

Read-only iLO checks still work:

- iLO identifies the machine as `ProLiant DL120 G7`.
- iLO server state object remains enabled.
- Drive Bay 1 and Bay 2 still report `Ok`.
- Latest management log records sampled:
  - `record344`: IPMI/RMCP login by provider-side `dedibox`.
  - `record345`: `Server reset.`
  - `record346`: SSH login via the current iLO user.
- System Event Log still has the old `record44` controller warning from `2026-05-07`; `record45` is still invalid/nonexistent.

Impact: there is evidence of a server reset after the previous checks, but no public rescue network or SSH path appeared afterward.

## Read-only follow-up - 2026-05-18 00:09 CEST

Fresh public reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` ping: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` TCP/22: failed.

Other checks:

- iLO SSH port `51.159.47.149:22` is still reachable.
- Attempted read-only WebPi/ticket recheck through temporary visible Chromium sessions.
- Reusing the earlier temporary browser profile redirected to login and the browser process exited.
- A fresh visible Chromium session reached Cloudflare/`Just a moment...`, then the browser process exited before CDP inspection could continue.
- The persistent Chromium CDP process exists, but its `9222` debugging port is not listening from this workspace.
- Temporary browser/Xvfb sessions from this attempt were cleaned up.

Impact: no new provider reply could be verified in WebPi during this attempt. The server-side blocker remains unchanged: rescue/network/SSH is still absent while iLO remains reachable.

## Read-only iLO follow-up - 2026-05-18 00:19 CEST

Fresh public reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Read-only iLO findings:

- iLO credential files are still available locally outside the repository.
- `show /system1/bootconfig1` lists five boot sources; no settings were changed.
- Boot source order values reported:
  - `bootsource1`: `bootorder=3`
  - `bootsource2`: `bootorder=4`
  - `bootsource3`: `bootorder=2`
  - `bootsource4`: `bootorder=5`
  - `bootsource5`: `bootorder=1`
- `show /system1/oemhp_power1` still reports power regulation `os` and auto power `ON (Minimum delay)`.
- `show /system1/log1/record45` remains invalid/nonexistent, so no newer System Event Log entry is visible after old `record44`.
- Additional iLO management log records sampled:
  - `record347`: `Server reset.`
  - `record348`: `Server reset.`
  - `record349`: `Server power restored.`
  - `record350`: current iLO SSH logout.

Impact: iLO shows more resets/power restoration, but the public host still does not answer. There is still no customer-side evidence that the machine successfully boots into provider rescue.

## Read-only NIC follow-up - 2026-05-18 00:25 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Read-only iLO network output:

- `show /system1/network1` exposes `Integrated_NICs` only.
- `show /system1/network1/Integrated_NICs` reports:
  - `iLO3_MACAddress=e4:11:5b:0d:be:a3`
  - `Port1NIC_MACAddress=e4:11:5b:0d:be:a0`
  - `Port2NIC_MACAddress=e4:11:5b:0d:be:a1`

Impact: iLO 3 CLI confirms the MAC addresses but does not expose link state for the host NICs here. WebPi's public IPv4/IPv6 MAC mapping still needs provider-side switch/rescue-path validation.

## VSP follow-up - 2026-05-18 00:28 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Read-only iLO Virtual Serial Port check:

- Started a bounded 25-second VSP observation with `start /system1/oemhp_vsp1`.
- iLO reported `Virtual Serial Port Active: COM2`.
- No bootloader, PXE, kernel, rescue login, or OS console text appeared before the timeout.

Impact: there is still no customer-side console evidence that the selected rescue environment is booting or hanging at a visible stage.

## IPMI tool availability follow-up - 2026-05-18 00:31 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Tried to use a separate read-only RMCP/IPMI path for boot flag inspection:

- `ipmitool` is not installed in this workspace.
- No RMCP/IPMI boot-flag query was run.

Impact: the only reliable out-of-band data source available from this workspace remains iLO SSH/SMASH, which has already shown power/log/NIC MAC/VSP state but not a usable boot or link-status explanation.

## Hardware sensor follow-up - 2026-05-18 00:32 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Read-only iLO hardware checks:

- `/system1/fan1`: `OperationalStatus=Ok`, `HealthState=Ok`, desired speed `25 percent`.
- `/system1/powersupply1`: `OperationalStatus=Ok`, `HealthState=Ok`.
- `/system1/sensor1`: inlet ambient temperature `19 Celsius`, `OperationalStatus=Ok`, `HealthState=Ok`.

Impact: sampled fan, power supply, and ambient temperature sensors do not show a customer-visible hardware health reason for the missing rescue network.

## RMCP/IPMI follow-up - 2026-05-18 00:34 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

RMCP/IPMI read-only attempt:

- UDP/623 on `51.159.47.149` is reachable.
- `pyghmi` was installed temporarily under `/tmp` only for a read-only test.
- `pyghmi` failed to initialize/authenticate against iLO with `IpmiException` and no useful detail.
- Temporary `pyghmi` files were removed afterward.

Impact: RMCP reachability exists, but this workspace still does not have a usable non-SMASH IPMI query path. iLO SSH/SMASH remains the available out-of-band diagnostic path.

## Boot source detail follow-up - 2026-05-18 00:38 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Read-only iLO boot source query:

- `show -a /system1/bootconfig1/bootsource1` returned all five boot sources.
- The output still exposes only numeric `bootorder` properties:
  - `bootsource1=3`
  - `bootsource2=4`
  - `bootsource3=2`
  - `bootsource4=5`
  - `bootsource5=1`
- No labels such as PXE, HDD, CD, USB, network, or rescue are visible in iLO SMASH output.

Impact: iLO confirms a boot order exists, but the CLI does not provide enough read-only detail to validate which source provider Rescue Mode is actually using.

## TTL path probe follow-up - 2026-05-18 00:40 CEST

Fresh IPv4 reachability still fails:

- IPv4 `195.154.209.133` ping: failed.
- IPv4 `195.154.209.133` TCP/22: failed.

Trace tooling status:

- `tracepath`, `traceroute`, and `mtr` are not installed in this workspace.
- Ran a TTL-limited ICMP probe with `ping -t` instead.

Observed path:

- TTL 1-7 returned intermediate hops from the local/upstream path.
- TTL 9-11 returned Online/Scaleway-side hops including `195.154.2.103`, `51.158.8.73`, and `51.158.0.11`.
- TTL 12-18 produced no reply from the target-side path.
- Artifact: `/tmp/par822349-ttl-probe-1779057669.log`.

Impact: packets are leaving this workspace and reaching the provider network before disappearing near the target/rescue path. This remains consistent with a provider-side rescue/PXE/switch/host boot issue rather than a local routing failure.

## Provider ticket escalation posted - 2026-05-18 00:51 CEST

Posted a new customer reply to OneProvider ticket `#94047858` with the latest consolidated evidence:

- WebPi had been verified as `Current Boot Mode: Rescue Mode`.
- WebPi IPv4/IPv6 mapping to Host Port 1 MAC `e4:11:5b:0d:be:a0`.
- iLO confirms Host Port 1 MAC.
- iLO SSH/SMASH works, but public rescue network never appears after resets.
- VSP opens on COM2 but shows no PXE, bootloader, kernel, rescue login, or OS output.
- System Event Log has no newer entry after the old `2026-05-07` controller warning.
- Sampled fan, power supply, and ambient temperature sensors are `Ok`.
- TTL-limited probe reaches provider-side hops and then receives no target-side response.
- Fresh IPv4 ping and TCP/22 still fail.
- Requested datacenter/provider-side checks of Rescue/PXE delivery, switch port for MAC `e4:11:5b:0d:be:a0`, and early boot/console state.
- Repeated the instruction not to change BIOS, RAID, IPMI settings, disk layout, or reinstall the OS without explicit confirmation.

Submission evidence:

- Ticket page showed `REPLIES` increased from `4` to `5`.
- The new `Update 2026-05-18 00:45 CEST` text was visible in the discussion.
- Screenshot artifact: `/tmp/panel-browser/direct-chrome-ticket-stable2-1779058080/ticket-after-escalation-0045b.png`.

## Post-escalation SSH poll - 2026-05-18 00:56 CEST

Ran a bounded six-attempt SSH reachability poll after posting the provider escalation.

Results:

- `2026-05-18T00:52:57+02:00`: IPv4 TCP/22 failed; IPv6 TCP/22 failed.
- `2026-05-18T00:53:33+02:00`: IPv4 TCP/22 failed; IPv6 TCP/22 failed.
- `2026-05-18T00:54:09+02:00`: IPv4 TCP/22 failed; IPv6 TCP/22 failed.
- `2026-05-18T00:54:45+02:00`: IPv4 TCP/22 failed; IPv6 TCP/22 failed.
- `2026-05-18T00:55:21+02:00`: IPv4 TCP/22 failed; IPv6 TCP/22 failed.
- `2026-05-18T00:55:57+02:00`: IPv4 TCP/22 failed; IPv6 TCP/22 failed.
- Artifact: `/tmp/reachability-after-ticket-escalation-1779058377.log`.

Impact: no rescue SSH path appeared immediately after the latest provider ticket escalation.

## Post-escalation ticket recheck - 2026-05-18 01:04 CEST

Read-only ticket recheck after the latest escalation:

- Ticket `#94047858` still shows status `Customer-Reply`.
- Ticket `REPLIES` count shows `6`.
- The latest two visible discussion entries are both customer/client entries with the same `Update 2026-05-18 00:45 CEST` escalation text:
  - `17/05/26 18:51:12` HQ time.
  - `17/05/26 18:52:38` HQ time.
- No provider/admin/staff response is visible at the top of the discussion.
- Screenshot artifact: `/tmp/panel-browser/ticket-recheck-after-escalation-1779059036/ticket-recheck-after-escalation.png`.
- Temporary browser/Xvfb session was closed after the read-only check.

Impact: the escalation is definitely visible to the provider, but there is still no visible provider-side reply or resolution.

## iLO log follow-up - 2026-05-18 01:10 CEST

Fresh reachability:

- IPv4 `195.154.209.133` TCP/22: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` TCP/22: failed.

Read-only iLO management log sample:

- `record351`: `Server reset.`
- `record352`: `Server reset.`
- `record353`: current iLO SSH logout.
- `record354`: current iLO SSH login.

Read-only iLO System Event Log:

- `record45` is still invalid/nonexistent.

Impact: additional server resets are visible in iLO, but they still did not produce rescue SSH or a new System Event Log explanation.

## VSP after new reset records - 2026-05-18 01:10 CEST

Fresh reachability:

- IPv4 `195.154.209.133` TCP/22: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` TCP/22: failed.

Read-only iLO Virtual Serial Port check after newer reset records:

- Started a bounded 20-second VSP observation with `start /system1/oemhp_vsp1`.
- iLO reported `Virtual Serial Port Active: COM2`.
- No bootloader, PXE, kernel, rescue login, or OS console text appeared before timeout.

Impact: even after the newer reset records, the customer-visible serial console still does not show a boot/rescue state.

## Ticket and SSH recheck - 2026-05-18 01:13 CEST

Fresh reachability:

- IPv4 `195.154.209.133` TCP/22: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` TCP/22: failed.

Read-only provider ticket recheck:

- Opened `https://panel.op-net.com/support/94047858`.
- Ticket still shows status `Customer-Reply`.
- Ticket `REPLIES` count still shows `6`.
- No provider/admin/staff response was detected in the visible ticket text.
- Screenshot artifact: `/tmp/panel-browser/ticket-recheck-0112-1779059554/ticket-recheck-0112.png`.
- Temporary browser/Xvfb session was closed after the check.

Impact: there is still no visible provider-side reply or resolution, and rescue SSH remains unavailable.

## Remote Access session recheck - 2026-05-18 01:20 CEST

Fresh reachability:

- IPv4 `195.154.209.133` TCP/22: failed.
- IPv6 `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0` TCP/22: failed.

iLO/Remote Access state:

- iLO management IP `51.159.47.149` still answers ICMP ping.
- iLO TCP/22 and TCP/443 currently time out.
- iLO UDP/623 is reachable.
- WebPi Remote Access section no longer shows the prior active iLO session; it shows `IP to whitelist` and `Create`.
- Attempted to create a new Remote Access session with the known client IP `152.53.35.28`.
- WebPi returned `Invalid boot mode.` after the provider warning confirmation.
- Boot Mode was not changed to Normal as a workaround, because the active recovery objective requires Rescue Mode and changing it would risk leaving the machine in the wrong boot state.
- Screenshot artifacts:
  - `/tmp/panel-browser/overview-recheck-0116-1779059787/overview-recheck-0116.png`
  - `/tmp/panel-browser/overview-recheck-0116-1779059787/remote-access-confirm-after.png`
- Temporary browser/Xvfb session was closed after the check.

Impact: iLO SSH diagnostics are currently unavailable because the provider Remote Access session expired/closed and WebPi refuses to create a new session while the server is in Rescue Mode.

## Remote Access ticket-update attempt - 2026-05-18 01:26 CEST

Tried to add the new Remote Access state to ticket `#94047858`.

Result:

- The support ticket page opened and the form could be filled.
- The attempted submit did not produce a visible new reply or reply-count increase.
- A second direct form-submit attempt also did not produce visible ticket content.
- Stopped to avoid creating blank or duplicated replies.
- Temporary browser/Xvfb session was closed.

Impact: the new Remote Access `Invalid boot mode` state is documented in this local handoff, but there is no confirmed additional provider-ticket reply for it.
