# L6 Network Egress — Stability Bring-up Runbook

**Owner:** Stability Agent (id=174f9181-63c9-40b5-8041-46beef440e56)
**Filed under:** GOAL-Power-1 acceptance #4 (SpoofStack RUNBOOK scaffolding)
**Compose:** `agents/stability/stack/compose/L6.compose.yml`
**Image pins:** `stack/image-pins.yml`
**Seccomp profile:** `agents/stability/stack/seccomp/redroid-seccomp.json`
**Layer reference:** `agents/stability/stack/layers.md` §L6 — Network Egress

This runbook is for lab measurement of detection signals in owned test
environments. It is not for evading anti-abuse controls on real services.
Carrier-IP shaping is for measurement of detector behaviour on owned test
infrastructure only; it is NOT a residential-proxy or carrier-spoofing
tool.

---

## §1. Baseline-layer-set decision

**Decision: L0a + L0b + L1 + L2 + L3 + L4 + L5 + L6 (full stack).**

L6 closes the SpoofStack composition. Per `agents/stability/stack/layers.md`
§L6 the canonical components are:

- A lab LTE modem (USB-LTE stick on the host with its own APN), supplying
  a real mobile-carrier IP.
- An iptables NAT gateway routing container egress to the LTE link.
- A local DNS resolver (1.1.1.1) for realistic DNS behaviour.

Target probes: `network.source_ip_asn` (#5), `network.vpn_interface_present`
(#18), `network.cellular_vs_wifi` (#25), `network.dns_resolver` (#37),
`network.iptables_nat_chain` (#38).

---

## §2. Pre-flight

```bash
# §2.1 — Pin-match check (image digest)
PINNED_DIGEST=$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)
COMPOSE_DIGEST=$(grep -oP 'redroid/redroid@\Ksha256:[a-f0-9]{64}' \
  agents/stability/stack/compose/L6.compose.yml | head -1)
[[ "$PINNED_DIGEST" == "$COMPOSE_DIGEST" ]] \
  || { echo "DIGEST DRIFT — refuse compose up"; exit 78; }

# §2.2 — Forbidden-pattern grep
grep -nE '(privileged:\s*(true|yes)|cap_add:.*\b(SYS_ADMIN|ALL)\b|pid:\s*host|network:\s*host|ipc:\s*host|userns_mode:\s*host|/var/run/docker\.sock|:/host\b|image:.*:latest)' \
  agents/stability/stack/compose/L6.compose.yml \
  | grep -v '^[[:space:]]*#' \
  && { echo "FORBIDDEN PATTERN"; exit 78; } \
  || echo "preflight pattern grep: OK"

# §2.3 — Results dirs
mkdir -p agents/stability/stack/compose/results/l6/{network-policy,out}
chmod 0777 agents/stability/stack/compose/results/l6/{network-policy,out}

# §2.4 — Host LTE modem check (skip if no modem; the cell still boots with
#        a plain bridge network and the carrier-IP probe will read as
#        non-cellular, which is documented in §6).
if [[ -e /dev/cdc-wdm0 ]]; then
  echo "host LTE modem present at /dev/cdc-wdm0"
else
  echo "host LTE modem NOT present — L6 cell will use plain bridge"
fi
```

---

## §3. Bring-up

```bash
# §3.1 — Pull pinned image
docker pull "redroid/redroid@$(yq -r '.redroid_12_64only.digest_amd64' stack/image-pins.yml)"

# §3.2 — Compose up the full L0a..L6 stack
cd agents/stability/stack/compose
docker compose \
  -f L0a.yml \
  -f L0b.compose.yml \
  -f L1.compose.yml \
  -f L2.compose.yml \
  -f L3.compose.yml \
  -f L4.compose.yml \
  -f L5.compose.yml \
  -f L6.compose.yml \
  up -d

# §3.3 — Wait for boot_completed=1 (36×10s budget)
C=stability-l6-redroid
for i in $(seq 1 36); do
  if docker exec "$C" sh -c 'getprop sys.boot_completed' 2>/dev/null | grep -q '^1$'; then
    echo "[L6] boot_completed=1 after ${i}0s"; break
  fi
  sleep 10
done

# §3.4 — Verify DNS resolver is 1.1.1.1
docker exec "$C" sh -c 'getprop net.dns1' | grep -q '1.1.1.1' \
  || echo "WARN: DNS resolver not 1.1.1.1 — DHCP override may not be wired"
```

---

## §4. Acceptance criteria

- `getprop sys.boot_completed` returns `1` within the 36×10s budget.
- All lower-layer modules still load (`magisk --list` shows the L1..L4
  modules `enabled`).
- `network.dns_resolver` probe reports `1.1.1.1` (#37).
- `network.vpn_interface_present` probe scores `<0.5` (no `tun0` or
  `wg0` visible to the container) (#18).
- Once the lab LTE link is wired:
  - `network.source_ip_asn` probe reports a mobile-carrier ASN (not
    a cloud-hoster ASN like AS24940 Hetzner) (#5).
  - `network.cellular_vs_wifi` probe reports `cellular` (#25).
- Fail-safe: removing the L6 compose layer and re-running compose-up
  MUST produce a clean L0a..L5 cell.

---

## §5. Rollback

```bash
cd agents/stability/stack/compose
docker compose -f L0a.yml -f L0b.compose.yml -f L1.compose.yml \
               -f L2.compose.yml -f L3.compose.yml -f L4.compose.yml \
               -f L5.compose.yml -f L6.compose.yml \
               down --volumes --remove-orphans
rm -rf results/l6/
```

The host-side iptables NAT chain (if installed) is rolled back via the
host's network-policy script, not by `docker compose down` — see §6.

---

## §6. Open questions / known gaps

- The host-side iptables NAT chain and LTE-modem PPPoE/QMI bring-up are
  NOT yet scripted. Without them, the cell uses a plain Docker bridge
  and the carrier-IP / cellular-vs-wifi probes will read as cloud
  hoster, NOT cellular. This is the dominant gap blocking the full
  L6 acceptance.
- DHCP-override of the in-container resolver to 1.1.1.1 is best-effort:
  `getprop net.dns1` may not reflect the override until the first DNS
  query is made. The compose `dns:` directive sets the Docker-level
  resolver; Android's net.dns* properties are set by `netd` at
  framework boot.
- TLS fingerprint shaping (JA3/JA4) is OUT OF SCOPE for L6 per the
  safety boundary in `layers.md`. Any future TLS-shaping work must
  classify as a detector + reporter, not an evasion module.
- Interaction with L3 attestation backends is not yet measured: some
  detectors cross-check the source-IP ASN against the attested SIM
  operator (#21). The layer-composition matrix needs an L3+L6 cell.
