# Current blockers and required input - PAR822349

Updated: 2026-05-18 17:41 CEST

## Current verified state

- Server: `PAR822349`
- IPv4: `195.154.209.133`
- IPv6: `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`
- MAC: `e4:11:5b:0d:be:a0`
- Intended Remote Access whitelist source IP: `152.53.35.28`
- Last authenticated WebPi evidence showed `Modo rescate` / `rescue_mode`.
- Prior authenticated WebPi Network tab showed the IPv4/IPv6/MAC mapping above.
- Latest public/RMCP and WebPi status recheck at 2026-05-18 16:30 CEST showed no provider-side improvement:
  - IPv4 ping works.
  - IPv4 TCP/22 is open.
  - IPv4 HTTP/80 returns Rescue nginx `503`.
  - IPv6 ping works, but TCP/22 times out.
  - IPMI `51.159.47.149` serves nginx on TCP/80.
  - IPMI TCP/443 times out.
  - `ipmi-ping -c 2 51.159.47.149` returns 0 responses / 100% packet loss.
  - Direct WebPi server, login, and root URLs return Cloudflare `HTTP/2 403`; the login URL returns `cf-mitigated: challenge`.
- Latest WebPi/CDP/password-manager access recheck at 2026-05-18 17:13 CEST showed no customer-side access improvement:
  - WebPi login URL still returns Cloudflare `HTTP/2 403` with `cf-mitigated: challenge`.
  - Local Chromium DevTools endpoint `127.0.0.1:9222` still refuses connection.
  - Bitwarden CLI remains locked.
  - `rbw` remains unusable because the agent is not running.
- Latest persistent browser namespace recheck at 2026-05-18 17:30 CEST showed the long-running Chromium CDP is reachable only inside the browser network namespace, but its WebPi tab is logged out:
  - Current CDP page is `https://panel.op-net.com/login#overview`, title `Sign in | OneProvider`.
  - Browser history records `https://panel.op-net.com/server/822349/manage#overview` at `2026-05-18 10:02:26 UTC`, also with title `Sign in | OneProvider`.
  - Cookie metadata for `op-net.com` exists but no cookie values were printed.
  - `Login Data` has no saved OneProvider/WebPi login origins.
- Latest persistent browser CDP DOM recheck at 2026-05-18 17:41 CEST showed the current page is still only the OneProvider login page:
  - sign-in/login marker present;
  - server identifier, Rescue, and IP markers absent;
  - 0 visible filled login inputs;
  - visible email-like input and visible password input are both empty;
  - no auth/session-style storage keys or cookie names were visible.

## Current blockers

- No current authenticated CZ Design / OneProvider WebPi session is available.
- Direct unauthenticated WebPi URLs return Cloudflare `HTTP/2 403`; the latest 17:13 CEST login URL recheck still returns `cf-mitigated: challenge`.
- Latest local browser/CDP recheck at 2026-05-18 16:48 CEST found `127.0.0.1:9222` still refusing connections and `/workspace/.browser-profile` still without `Cookies`, `History`, or `Login Data` files.
- Latest direct CDP endpoint recheck at 2026-05-18 17:13 CEST still found `127.0.0.1:9222` refusing connections.
- Latest browser namespace CDP recheck at 2026-05-18 17:30 CEST found CDP reachable inside the Chromium namespace, but only to the logged-out OneProvider login page.
- Latest browser namespace DOM recheck at 2026-05-18 17:41 CEST found no prefilled visible email/password fields and no server/Rescue/IP markers.
- Latest Chromium open-file metadata recheck at 2026-05-18 16:52 CEST found no open file descriptor target names for `Cookies`, `History`, `Login Data`, `Local State`, session/local storage, IndexedDB, OneProvider, or WebPi.
- No current Rescue SSH login path is available from this workspace.
- Latest SSH identity/auth-method recheck at 2026-05-18 16:42 CEST showed unchanged host-key fingerprints and banner `SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.11`; non-credential auth for `paris` still fails with `Permission denied (publickey,password)`.
- Latest key-only SSH retry at 2026-05-18 16:58 CEST tested `id_ed25519` and `online-paris` for both `paris` and `root`; all four attempts failed.
- Latest local password-manager state recheck at 2026-05-18 16:36 CEST found Bitwarden still locked and `secret-tool`, `gnome-keyring-daemon`, `kwallet-query`, `pass`, and `op` absent.
- Latest Bitwarden/rbw state recheck at 2026-05-18 17:06 CEST found no relevant credential environment variables, Bitwarden CLI still locked, and `rbw` present but with agent not running.
- Latest Bitwarden/rbw status recheck at 2026-05-18 17:13 CEST still found Bitwarden locked and `rbw` blocked by `agent not running`.
- WebPi Remote Access/IPMI remains unusable:
  - prior `createIpmiSession` returned `Invalid boot mode`;
  - prior `getIpmiCredentials` failed;
  - RMCP/IPMI does not respond from the intended whitelist IP.
- HP Smart Array P410 / RAID-1 logical volume remains unsafe from prior read-only Rescue diagnostics:
  - `/dev/sda` was `offline`;
  - `ssacli` reported `Smart Array P410 (Error: Not responding)`.

## Local access paths already ruled out

- Current browser/CDP session.
- `/workspace/.browser-profile` metadata at the latest 16:48 CEST recheck.
- Chromium open-file metadata at the latest 16:52 CEST recheck.
- Historical `/tmp/panel-browser` WebPi profiles.
- Browser profile metadata outside `/tmp/panel-browser`.
- Firefox/Camoufox metadata.
- Local SSH keys and SSH agent/config metadata.
- Latest key-only SSH retry with `id_ed25519` and `online-paris` for `paris` and `root`.
- Repository-local `.env` candidate.
- Local agent-history WebPi login candidates.
- Local OneProvider API credential search.
- Current environment variable names.
- Local keyring/password-manager metadata; Bitwarden CLI exists but is locked, unchanged at the 16:36 CEST recheck.
- Bitwarden/rbw state; no unlocked session is available.
- Latest 17:13 CEST WebPi/CDP/password-manager recheck.
- Latest 17:30 CEST persistent browser namespace recheck; it explains the namespace mismatch but still provides no authenticated WebPi session.
- Latest 17:41 CEST persistent browser CDP DOM recheck; no usable prefilled login or local auth/session state was found.
- Shell-history metadata; target-related hits appeared only in already checked agent-history files.
- SSH identity/auth-method recheck; host identity is unchanged and no credential-free login path exists.

No raw credential values are stored in this artifact.

## Required input or external action

One of these is required before safe customer-side work can continue:

1. Fresh CZ Design / OneProvider WebPi credentials or an already-authenticated browser session.
2. Current Rescue SSH password for user `paris`, or another valid SSH login path.
3. Provider-side remediation/confirmation for WebPi Remote Access/IPMI and P410/logical-volume health.
4. Explicit owner approval to use Express/VIP escalation in ticket `#94047858`.

## Do not do without explicit owner confirmation

- Boot normal mode.
- Reinstall.
- Change BIOS settings.
- Change RAID settings.
- Change persistent IPMI/iLO settings.
- Change disk layout.
- Run `fsck`.
- Mount installed disks read-write.
- Click Express/VIP escalation.
