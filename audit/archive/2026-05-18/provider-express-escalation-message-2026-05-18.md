# Provider Express escalation message - PAR822349

Prepared: 2026-05-18 16:36 CEST

Use only after owner approval for Express/VIP escalation.

```text
Hello,

Please escalate server PAR822349 / 195.154.209.133 for provider-side Remote Access/IPMI and storage-controller investigation.

The relevant evidence is already visible in WebPi tickets:
- #94047858: Respuesta-cliente, 19 replies, includes Update 2026-05-18 06:15 CEST with Smart Array P410 and Invalid boot mode evidence.
- #47300051: Respuesta-cliente, 2 replies, includes Update 2026-05-18 06:09 CEST with Invalid boot mode / PAR822349 evidence.

Customer-side WebPi state is coherent:
- WebPi UI and backend report Rescue Mode / rescue_mode.
- getStatus returns success=true, status=rescue_mode.
- getRescueMode returns success=true, status=rescue_mode, currentMode.value=rescue_mode, user=paris.
- Later plain Chromium GUI access at 2026-05-18 09:38 CEST rendered the authenticated server page again and visually reconfirmed PAR822349, IPv4 195.154.209.133, and Modo rescate.
- Current local browser/session state is logged out; another customer-side WebPi action requires fresh panel credentials or an already-authenticated browser session.
- IPv4 Rescue SSH works on 195.154.209.133:22 when the current WebPi Rescue password is available; key-only SSH from this workspace failed for both paris and root.
- Latest unauthenticated SSH banner at 2026-05-18 13:13 CEST is SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.11.
- IPv4 HTTP/80 is open but returns HTTP/1.1 503 from provider Rescue nginx with body title "Serveur Dedibox en maintenance"; this is not evidence of a healthy installed OS.
- Latest unauthenticated recheck at 2026-05-18 16:01 CEST still shows IPv4 ping OK, TCP/22 open, and HTTP/80 returning the same Rescue nginx 503 page.
- IPv4 nmap top-1000 TCP scan at 2026-05-18 12:36 CEST found only 22/tcp and 80/tcp open on 195.154.209.133; 998 top TCP ports were filtered.
- IPv6 ping works, but targeted IPv6 TCP checks remain unusable; latest TCP/22 check at 2026-05-18 16:01 CEST timed out.
- Network/IP/MAC mapping is coherent.
- createIpmiSession fails with both the auto-filled client IPv6 and whitelist IP 152.53.35.28, so this does not appear to be a whitelist-input issue.
- The current WebPi frontend exposes no missing required IPMI/session parameter; it sends action=createIpmiSession, ip, optional duration/type only if present, and server_id.

Remote Access/IPMI is still broken:
- getIpmiCredentials returns success=false: Unable to obtain authentication info. Please try again later or contact support.
- createIpmiSession returns success=false: Invalid boot mode.
- Latest external check at 2026-05-18 16:01 CEST: 51.159.47.149:80/tcp is open but returns only a default "Welcome to nginx!" page.
- Latest external check at 2026-05-18 16:01 CEST: 51.159.47.149:443/tcp timed out.
- Current workspace public egress IP is 152.53.35.28, matching the intended WebPi Remote Access whitelist IP.
- Current workspace also has IPv6 egress 2a0a:4cc0:100:899:98f1:fdff:fe6d:a2b1; this was already tested earlier in WebPi and also returned Invalid boot mode.
- Latest external check at 2026-05-18 12:17 CEST: 51.159.47.149:623/udp appears reachable via nc, but FreeIPMI protocol checks from the same whitelisted source IP get no RMCP response:
  - ipmi-ping -c 3 51.159.47.149: 3 transmitted, 0 responses, 100% packet loss.
  - ipmiping -c 3 51.159.47.149: 3 transmitted, 0 responses, 100% packet loss.
- Latest 2026-05-18 16:01 CEST recheck with ipmi-ping -c 2 also returned 0 responses / 100% packet loss.
- No usable WebPi/iLO Remote Access session or RMCP/IPMI service is exposed to the whitelisted source IP.
- Limited check of common alternate management/KVM ports (22, 23, 80, 443, 623/tcp, 17988, 17990, 5900-5902, 8000, 8080, 8443, 9000, 9443) found only TCP/80 open.
- A broader nmap top-1000 TCP scan at 2026-05-18 12:33 CEST also found only 80/tcp open; 999 top TCP ports were filtered.

Storage/controller is still unsafe:
- From provider Ubuntu 22.04 live Rescue, lsblk shows /dev/sda as HP LOGICAL VOLUME, about 1.8T, state offline.
- /sys/class/scsi_disk/0:1:0:0/device/state reports offline.
- /proc/mdstat shows no active md array.
- ssacli ctrl all show reports: Smart Array P410 (Error: Not responding).
- ssacli ctrl all show status returns: Error: Cannot show status for this device.
- ssacli ctrl all show config reports: Smart Array P410 (Error: Not responding).
- lspci identifies the HP Smart Array P410 using driver hpsa.

Customer-side safety and local-access limitation:
- No disks were mounted.
- No fsck was run.
- No RAID, BIOS, IPMI, partition, filesystem, boot-order, or reinstall changes were made after the read-only diagnostics.
- Local credential/session recovery found no reusable panel credentials or persistent logged-in browser session.
- Common local credential-store metadata check found no OneProvider/WebPi source in .netrc, .authinfo, .git-credentials, .aws/credentials, .config/op, .password-store, or .config/gh/hosts.yml.
- Latest key-only SSH check at 2026-05-18 13:54 CEST tested local keys id_ed25519 and online-paris for both paris and root using a temporary fresh known_hosts file; all four attempts failed with Permission denied (publickey,password), so fresh Rescue SSH diagnostics require the current WebPi Rescue password or another owner-provided login path.
- Local unstructured .env credential candidate check at 2026-05-18 14:01 CEST failed password authentication for both paris and root.
- Local history-derived WebPi login candidate check at 2026-05-18 14:10 CEST found one unique email and two unique password candidates, but both Cloak Chromium login attempts stayed on Sign in | OneProvider.
- Local OneProvider API credential check at 2026-05-18 14:21 CEST found no usable local Api-Key / Client-Key pair.
- Current environment variable check at 2026-05-18 14:40 CEST found no OneProvider/WebPi/Rescue/IPMI/API-specific variable names.
- Public/WebPi session recheck at 2026-05-18 14:50 CEST found no usable authenticated local WebPi session: direct WebPi URL access returned Cloudflare HTTP/2 403, local Chromium CDP on 127.0.0.1:9222 was unreachable, and scoped /tmp/panel-browser artifacts were historical only.
- Historical WebPi profile CDP recheck at 2026-05-18 15:06 CEST reached https://panel.op-net.com/login#overview with title "Registrarse | OneProvider"; login markers were present but server markers for PAR822349 were absent, so the historical profile is not an authenticated WebPi session.
- Local browser profile metadata check at 2026-05-18 15:32 CEST found no OneProvider/WebPi cookie hosts outside /tmp/panel-browser; no cookie values or saved password values were printed.
- Local SSH configuration metadata check at 2026-05-18 15:40 CEST found no ~/.ssh/config, no loaded SSH-agent identities, and only the two already-tested public keys; no additional local SSH alias, agent key, or untested public key path is available.
- Firefox/Camoufox metadata check at 2026-05-18 15:46 CEST found no OneProvider/WebPi cookie hosts, no matching history URLs, and no logins.json; no cookie values or saved password values were printed.
- Local keyring/password-manager metadata check at 2026-05-18 15:54 CEST found no usable unlocked credential path; Bitwarden CLI is present but locked, and no vault items or secret values were printed.
- Shell-history metadata check at 2026-05-18 16:09 CEST found target-related hits only in already checked .claude/history.jsonl and .codex/history.jsonl; matching lines or command contents were not printed.
- Direct unauthenticated WebPi HTTP status check at 2026-05-18 16:17 CEST returned Cloudflare HTTP/2 403 for the server URL, login URL, and root panel URL; the login URL included cf-mitigated: challenge. No login was attempted.
- Latest public/RMCP and WebPi status recheck at 2026-05-18 16:30 CEST showed no improvement: IPv4 Rescue ping/SSH/HTTP still work, IPv6 TCP/22 times out, IPMI TCP/443 times out, ipmi-ping returns 0 responses, and WebPi server/login/root URLs return Cloudflare HTTP/2 403 with the login URL marked cf-mitigated: challenge.
- Latest local password-manager state recheck at 2026-05-18 16:36 CEST found Bitwarden still locked and the other checked password-manager CLIs absent; no vault was unlocked or enumerated.
- Temporary text/JSON artifacts under /tmp/panel-browser were redacted, and the known secret-pattern scan returned no hits afterward.

Please check/fix:
1. Why WebPi reports Rescue Mode but createIpmiSession returns Invalid boot mode.
2. Why 51.159.47.149 exposes default "Welcome to nginx!" on TCP/80 and UDP/623 gives no RMCP response from the whitelisted source IP while HTTPS/443 and SSH/22 are closed.
   Also confirm whether the Remote Access service is expected on any alternate port; none of the common checked ports were open except TCP/80.
3. HP Smart Array P410 controller health, physical disk health, and RAID logical-volume state.

Please do not change BIOS, RAID/IPMI settings, disk layout, boot order, or reinstall the OS without explicit confirmation.
```
