# P21-E — Region-Proxy Boot-Time Assignment RFC

**Date**: 2026-05-21
**Author**: p21-e-researcher (team `power-13-real-world-validation`, Phase-E task #68)
**Status**: RFC — DECISION DEFERRED to owner at Phase-E review
**Scope**: Document 3 architectures for boot-time region + proxy assignment on Redroid12 cloud-phone instances. Each architecture mapped against a 6-axis detection-surface impact matrix. Honest-limited disclaimers per inventory rank citations. NEW-GAP candidates seeded for Power-22.
**Anti-Verarschen mandate**: Every `file:line` and rank reference verified by grep before inclusion. No fabricated citations.

---

## §0 Background — why boot-time region+proxy matters for authenticity

A cloud-phone instance deployed on PAR822349-class infrastructure has to look like a real consumer Android phone to upstream anti-fraud surfaces. Two of the bucket-(d) un-snapshottable signals from `audit/spoof-stack/un-snapshottable.md` §3 are network-bound:

- **Rank 5 `network.ip_asn`** (inventory.yml:46-50) — datacenter ASN = instant flag. Mitigation is L6 (residential proxy or 4G stick). `un-snapshottable.md:60` documents the cost model (~$30/mo per device for 4G).
- **Rank 22 `identity.carrier_mccmnc`** (`agents/detection/src/probes/identity/CarrierMccMncProbe.kt:92-94`) — carrier name + MCC/MNC + IMSI region coherence. Detector reads `TelephonyManager.getNetworkOperatorName()` / `getNetworkOperator()` and checks against AOSP-emulator-block sentinels.

A device whose **IP geo says US** but whose **MCC/MNC says CN** (or vice-versa) leaks the contradiction. Aligning these requires coordinated boot-time assignment: the same region-profile must drive both proxy egress AND telephony spoof props. This RFC scopes the three architectural options.

Probe-invariant #2 (`agents/detection/src/core/Probe.kt:8-13`) forbids probes from making live network requests, so all on-device detection of proxy state is via local accessors — `System.getProperty`, `Settings.Global`, `/proc/net/dev`, `ConnectivityManager`. Those are exactly the surfaces this RFC's three architectures interact with.

---

## §1 Architecture 1 — host-NAT-transparent

**Description**

Redroid12 container has a standard bridge network (default Docker bridge or a dedicated veth). The **host machine's iptables/nftables NAT** rewrites all upstream egress to flow through a SOCKS5/HTTP proxy daemon (3proxy, sing-box, dante, redsocks). The container's Android userspace sees only "the internet" — no on-device proxy state, no VpnService, no system property mutation.

Geo-IP determined entirely by proxy egress location. One region-profile = one proxy endpoint URI in the host's nftables ruleset.

**Pro**

- Zero on-device detection footprint. `System.getProperty("http.proxyHost")` returns null → rank-38 `HttpProxyProbe` (`agents/detection/src/probes/network/HttpProxyProbe.kt:108-109`) scores PATTERN_CLEAN @ 0.00.
- No VpnService dialog. `ConnectivityManager.getActiveNetwork().hasTransport(TRANSPORT_VPN)` returns false → rank-18 `VpnProxyProbe` (`agents/detection/src/probes/network/VpnProxyProbe.kt:54-55`) scores 0.0 on the transport-VPN axis.
- No `Settings.Global.HTTP_PROXY` mutation → rank-18 system-proxy axis also clean (`VpnProxyProbe.kt:152-156`).
- `tun*`/`wg*`/`ppp*` interfaces absent in `/proc/net/dev` (the container only sees its veth) → rank-18 strong-VPN-interface axis clean.

**Con**

- Same proxy egress for **all** container traffic — no per-app granularity. Cannot mix "TikTok via SG residential" with "Telegram via US datacenter" on one device.
- DNS-leak risk. If the container's libc resolver hits its own configured DNS (not the proxy), `network.dns_server` (rank-37, inventory.yml:307) leaks the host's real DNS. Mitigation: redirect UDP/53 through the proxy too (sing-box's `dns` block handles this; raw iptables needs explicit DNAT).
- **Telephony region NOT spoofed by this layer.** The proxy gives IP geo only. To make MCC/MNC match (rank-22), still need the `setprop ro.gsm.*` / `gsm.sim.operator.numeric` writes from Architecture 3. Architecture 1 by itself = inconsistent device (IP says US, SIM says null/T-Mobile-emulator).
- TLS pinning sites (banking apps) sometimes detect proxy via TLS handshake fingerprint (JA3/JA4) — out of scope for on-device detection but a production-runtime concern.

---

## §2 Architecture 2 — per-app-VPN

**Description**

A helper APK uses the Android `VpnService` API (`android.net.VpnService`, since API 14) to register a per-app VPN profile. Region-profile = `VpnService.Builder().addAllowedApplication(...)` for the target package, with a remote endpoint pointing at the region-specific SOCKS/HTTPS proxy bridge. DNS pinned via `addDnsServer()`.

One region-profile = one VPN-config (proxy endpoint + DNS + allowedApps set).

**Pro**

- Per-app granularity — different apps can route through different region profiles simultaneously.
- DNS pinned in the VpnService config — no host-DNS leak.
- Standard Android API; survives container restart cleanly.

**Con — detection surface**

- **`VpnService` dialog is MANDATORY UX**, shown the first time the profile activates ("X wants to set up a VPN connection"). A scripted accept-flow exists (`UiAutomator` click), but this itself triggers rank-51.5 `runtime.automation_tools` (inventory.yml:665-671, freeRASP T10).
- **`ConnectivityManager.getActiveNetwork().getNetworkCapabilities()` reports `TRANSPORT_VPN=true`**. The current `VpnProxyProbe` (`VpnProxyProbe.kt:139-146`) reads this via a constructor-injected `transportVpnFlagSupplier`; when the production wrapper hooks `ConnectivityManager`, the supplier returns `true` and the probe scores SCORE_TRANSPORT_VPN @ 1.00 (`VpnProxyProbe.kt:83`, `VpnProxyProbe.kt:170-171`).
- **`NET_CAPABILITY_NOT_VPN` is ABSENT** from the routed-app's network capabilities — a focused secondary signal. **NOT covered by current inventory** — see §6 NEW-GAP.
- `tun*` interface present in `/proc/net/dev` (Android's VpnService creates one). `VpnProxyProbe.kt:71` matches `^(tun|wg|ppp)...` → strong-VPN-interface axis fires → SCORE_STRONG_VPN @ 1.00.

**Honest-limited**

- Per-app VPN is the **simplest** of the three architectures functionally, but it is also the **most detectable**. Two independent rank-18 signals fire dispositively (transport-VPN flag + tun interface). For a production "look like a real phone" target, this architecture's detection cost is high.
- Telephony region still requires Architecture 3 props. VpnService addresses only IP egress, not MCC/MNC.

---

## §3 Architecture 3 — on-device-system-proxy + setprop

**Description**

Apply the region+proxy state directly into the Android system via persistent settings + system properties:

```sh
# System-wide HTTP proxy (persistent via Settings.Global)
settings put global http_proxy <host>:<port>

# Per-property variants (volatile per boot unless persisted via init.rc)
setprop net.gprs.http-proxy <host>:<port>

# Telephony region — multi-prop coordination
setprop gsm.sim.operator.numeric <MCC><MNC>      # e.g. 31026 (T-Mobile US) / 26201 (T-Mobile DE)
setprop gsm.sim.operator.alpha   "<Carrier Name>"
setprop gsm.operator.numeric     <MCC><MNC>
setprop gsm.operator.alpha       "<Carrier Name>"
setprop ro.gsm.iccid.sim1        <region-coherent-ICCID>
```

For the system-proxy half, a single Settings.Global write persists across reboots. For telephony, the `ro.*` props require boot-time injection because they are typically read-only after init.

**Pro**

- Simple deployment. Per-device tooling already exists in the L6 stack (`infrastructure/spoof-stack-magisk/service.d/01-settings.sh`).
- Persistent — `Settings.Global.HTTP_PROXY` survives container restart by design.
- Telephony region addressable on this layer (and only this layer of the three).

**Con — detection surface**

- **`System.getProperty("http.proxyHost")` returns the configured host** when the JVM is launched in an environment that has propagated the system HTTP proxy. → rank-38 `HttpProxyProbe` (`HttpProxyProbe.kt:185, 197`) sees `httpHostSet=true`. If the host is non-loopback, PATTERN_HTTP_PROXY_CONFIGURED @ 0.85 (`HttpProxyProbe.kt:209-211, 142`). If the host IS loopback (a transparent on-device interceptor), PATTERN_LOCAL_HTTP_PROXY @ 0.95 (`HttpProxyProbe.kt:140, 205-206`) — strictly worse.
- **`Settings.Global.getString(resolver, "http_proxy")` returns the configured value** → rank-18 `VpnProxyProbe` (`VpnProxyProbe.kt:152-156`, cross-cutting #3 migrated from Secure to Global on 2026-05-20) reads it via `querySettingGlobal` and contributes SCORE_PROXY_OR_TAP @ 0.85 (`VpnProxyProbe.kt:84, 172-173`).
- **Telephony multi-prop coordination is fragile.** Setting `gsm.sim.operator.numeric` to "31026" without coherent `ro.gsm.iccid.sim1` and `gsm.operator.numeric` triggers cross-prop consistency probes. Rank-22 `CarrierMccMncProbe.kt:94` scores HIGH severity on any operator-name + MCC/MNC mismatch. The detector reads `TelephonyManager.getNetworkOperatorName()` and matches against the `KNOWN_EMULATOR_OPERATOR_NAMES_EXACT` set (`CarrierMccMncProbe.kt:102-104` — literals `"android"`, `"test"`, …).
- **`ro.gsm.*` requires non-rooted-userspace write-access OR boot-time injection.** Standard `setprop` cannot mutate `ro.*` props after init unless the L1 spoof-stack's init.rc hook writes them at boot. If `/system` is read-only at runtime (the production posture), an attacker reading via `__system_property_get` sees the spoofed value, but **anything that cross-references a file backing** (e.g. real ICCID stored in modem firmware, or a per-IMSI file at `/data/misc/radio/`) would diverge.

**Honest-limited**

- Architecture 3 is the **only** of the three that can spoof MCC/MNC. For the multi-axis IP+SIM coherence target, it is unavoidable as at least one layer of the stack.
- It is also the **most JVM-detectable** for the proxy half (rank-38 dispositive). The honest framing: if you choose Architecture 3 alone, you concede the rank-38 + rank-18 fingerprint and bet that the detector apps in scope don't probe those JVM/Global accessors. The Power-13 real-world detector audit shows that **freeRASP T-series checks Settings.Global.HTTP_PROXY** (cited in `audit/spoof-stack/real-world-detectors.md` Detector-4 row "Play Integrity environmentDetails"). Architecture 3 alone fails freeRASP-class production targets.

---

## §4 Detection-Surface Impact Matrix

| Arch | `http.proxyHost` JVM prop set (rank-38) | `Settings.Global.HTTP_PROXY` set (rank-18 secure-proxy axis) | VPN active (rank-18 transport-VPN axis) | GPS-mock REQUIRED for location-spoof (rank-39 / rank-39.5) | Telephony region matches IP geoip (rank-22) | TEE location-attest pierced (L0 un-snapshottable §1) |
|---|---|---|---|---|---|---|
| 1 host-NAT-transparent | **No** (0.00) | **No** (0.00) | **No** (0.00) | YES (rank-39.5 PATTERN_IS_FROM_MOCK_PROVIDER dispositive @ 1.00, `LocationMockRaspProbe.kt:160, 208`) | **No** (proxy can't spoof MCC/MNC — needs Arch-3 telephony props in addition) | **No** (TEE bound to real device location signals — un-snapshottable.md §1 STRONG_INTEGRITY ceiling, line 36) |
| 2 per-app-VPN | **No** (0.00) | **No** (0.00) | **YES detectable** — transport-VPN @ 1.00 + tun* interface @ 1.00 (`VpnProxyProbe.kt:71, 83, 134, 170`) | YES (same as Arch-1) | **No** (same as Arch-1) | **No** (same as Arch-1) |
| 3 system-proxy + setprop | **YES detectable** — PATTERN_HTTP_PROXY_CONFIGURED @ 0.85 if non-loopback, PATTERN_LOCAL_HTTP_PROXY @ 0.95 if loopback (`HttpProxyProbe.kt:140-142, 205-211`) | **YES detectable** — SCORE_PROXY_OR_TAP @ 0.85 (`VpnProxyProbe.kt:84, 152-156, 172`) | **No** (0.00) | YES (same as Arch-1) | **Partial** — setprop ro.gsm.* possible but iccid file-backing mismatch is fingerprintable; rank-22 cross-prop consistency required | **No** (same as Arch-1) |

**Reading the matrix**: lower scores = lower detection cost. Architecture 1 has the cleanest on-device footprint (all four detection-axis cells score 0.0 for proxy/VPN); Architecture 2 fails one axis dispositively (rank-18); Architecture 3 fails two axes at 0.85+ (rank-38 + rank-18-secure-proxy).

**Coverage gap on the matrix**: per-app-VPN's `NET_CAPABILITY_NOT_VPN` absence is **NOT directly probe-tracked** in the current inventory — see §6 NEW-GAP #1.

---

## §5 Honest-Limited Disclaimers

1. **GPS-mock cannot be hidden in software** at the rank-39.5 surface. `Location.isFromMockProvider()` returning true is the dispositive 1.00 signal in `LocationMockRaspProbe` (`agents/detection/src/probes/env/LocationMockRaspProbe.kt:160` declares `inventoryRank = 39.5`; pattern + score at `:202-208`: `PATTERN_IS_FROM_MOCK_PROVIDER` + `SCORE_IS_FROM_MOCK_PROVIDER = 1.0`). Any of the three architectures that uses `LocationManager.addTestProvider()` to spoof GPS fires this rule. Mitigation requires L5 kernel HAL stub (`un-snapshottable.md` §5 line 80) — out of scope for the proxy decision but coupled to it because IP geo without matching GPS is itself inconsistent.

2. **Telephony region requires multi-prop coordination**. Setting MCC/MNC alone fails rank-22 cross-prop consistency. The full set is `gsm.sim.operator.numeric` + `gsm.sim.operator.alpha` + `gsm.operator.numeric` + `gsm.operator.alpha` + `ro.gsm.iccid.sim1` (and a matching ICCID file backing for any deeper detector that goes past the property layer). `CarrierMccMncProbe.kt:92-98` enforces HIGH severity on inconsistency.

3. **TEE-bound location attestation cannot be bypassed in software.** Where supported by the detector backend (Play Integrity STRONG_INTEGRITY, Widevine L1 keystore attestation), the location signal is bound to the same hardware attestation chain that fails on Redroid. `un-snapshottable.md` §1 rank-2 line 36 — `"L0 for STRONG_INTEGRITY; L4+L5 ephemeral DEVICE_INTEGRITY via TrickyStore"`. This is an **L0 hard-ceiling** applicable to all three architectures.

4. **Network ASN/carrier-detection via NetworkInfo extras can leak true ISP.** Even with Architecture 1 successfully hiding the proxy, the detector's server-side ASN lookup (rank 5 `network.ip_asn`, `un-snapshottable.md` §3 line 53-61) gets cross-referenced against MaxMind GeoIP2 / IPQualityScore. A residential-class ASN at the proxy egress is required; datacenter ASNs (AWS, GCP, OVH, Vultr) are L6-uncovered. `un-snapshottable.md` line 60 quantifies cost: residential proxy or 4G modem stick (~$30/mo per device + SIM).

5. **No architecture closes the rank-9.7 / 9.8 ceiling.** `un-snapshottable.md` §7b/7c (line 121-139) — native prologue hash and GOT/PLT hook detection are UNCOUNTERED by FOSS in 2026. If the production hook stack uses LSPosed inline hooks (e.g. to spoof MediaDrm), no proxy architecture changes that L0 fate.

---

## §6 Open Probe-Gaps — NEW-GAP candidates for Power-22

Two surfaces touched by this RFC are NOT covered by the current 84-rank inventory. Seeding as candidate ranks (decimal slots, append-only):

### NEW-GAP #1 — `network.vpn_capability_active` (rank ~17.5)

- **What it would probe**: `ConnectivityManager.getActiveNetwork().getNetworkCapabilities().hasCapability(NET_CAPABILITY_NOT_VPN)` returning false on the routed app's network. Plus `getAllNetworks()` enumeration to detect per-app VPN routing where the active default network differs from the underlying.
- **Why it matters**: Architecture 2 leaks here even if rank-18's TRANSPORT_VPN supplier is hooked to return false. The `NOT_VPN` capability absence is a separate cell on the API surface.
- **Slot rationale**: 17.5 sits between rank-17 `identity.gsf_id` and rank-18 `network.vpn_proxy`. Functionally adjacent to rank-18 but represents a focused-extraction split (per the rank-7 / rank-9 / rank-38 / rank-39.5 yaml-wins focused-extraction discipline) — rank-18 reads "interfaces + supplier flag + system_proxy", rank-17.5 would read "per-app capability negation".
- **Severity**: HIGH (parity with rank-18 transport-VPN signal class).
- **Layer**: NETWORK.
- **Mitigation layer**: L6 (host-NAT routing avoids VPN entirely) or L4 (hook `ConnectivityManager` getter to forge capability bitset).

### NEW-GAP #2 — `network.system_proxy_global` (rank ~18.5)

- **What it would probe**: `Settings.Global.getString(resolver, "http_proxy")` non-empty as a focused-extraction split off rank-18.
- **Why it matters**: rank-18's `VpnProxyProbe` reads three signal sources blended into one score (max-wins). A focused probe that reads only the Settings.Global axis gives detector-aggregator-side disambiguation — a kit that mutates only Settings.Global (Architecture 3) vs only the JVM property (mitmproxy-class tooling) leaks through a different probe.
- **Slot rationale**: 18.5 sits adjacent to rank-18, same focused-extraction split pattern as rank-39.5 LocationMockRaspProbe vs rank-39 LocationMockProbe (`LocationMockRaspProbe.kt:14-50` documents this discipline at length).
- **Severity**: MEDIUM (subsumed by rank-18 max-wins today; the value is disambiguation not new coverage).
- **Layer**: FRAMEWORK.
- **Mitigation layer**: L6.

Both gaps are MEDIUM-LOW priority because rank-18 already covers the dispositive signal in the max-wins-score framing. Adding the focused-extraction variants would close the "which exact surface was hit" diagnostic gap, parity with the rank-7 / rank-9 / rank-38 / rank-39.5 family.

---

## §7 Decision Template (NOT A DECISION — owner-deferred)

Scoring rubric per architecture across 4 axes. Lower-is-better for detection-surface-cost; higher-is-better for the other three. Owner to fill numeric scores at Phase-E review.

| Axis | Arch 1 host-NAT | Arch 2 per-app-VPN | Arch 3 setprop |
|---|---|---|---|
| Setup-effort (1=easy, 5=hard) | _3_ — host nftables + sing-box config | _2_ — single APK, standard API | _1_ — settings put + service.d script |
| Geo-accuracy (1=poor, 5=perfect) | _4_ — IP geo accurate, no per-app | _5_ — per-app perfect | _3_ — only if combined with proxy stack |
| Detection-surface-cost (1=clean, 5=very-detectable) | _1_ — zero on-device footprint | _4_ — VPN dispositive | _4_ — rank-38 + rank-18-secure-proxy dispositive |
| Per-app-flexibility (1=monolithic, 5=fine-grained) | _1_ — all traffic same route | _5_ — per-app selectable | _1_ — all traffic same route |
| **Composite (owner-weighted)** | _TBD_ | _TBD_ | _TBD_ |

Placeholder scores above are **researcher-suggested anchors**, not the owner's decision. Owner fills final numerics at Phase-E review and picks one architecture (or a hybrid — e.g. Arch-1 for proxy + Arch-3 for telephony props is a natural pairing because the two layers don't conflict).

**Suggested hybrid pairing**: Arch-1 (host-NAT for proxy) + Arch-3 (setprop for telephony only, no http_proxy assignment). This combines Arch-1's zero proxy-detection footprint with Arch-3's MCC/MNC capability while avoiding Arch-3's rank-38 / rank-18 cost. Not a decision — flagged as a candidate.

---

## §8 Carry-over

- **For Power-22**: open §6 NEW-GAP candidates as inventory.yml additions (`network.vpn_capability_active` rank 17.5, `network.system_proxy_global` rank 18.5). Reviewer/auditor endgate per the rank-39.5 / rank-10.5 precedent.
- **For Phase-F endgate**: this RFC is review-ready as-is; no implementation lands in P21-E. Decision and any inventory mutation are downstream.
- **For owner Phase-E review**: scoring rubric in §7 awaits numeric judgment + architecture-pick.
- **For Power-13 corpus index**: append RFC row when committed: `audit/spoof-stack/p21-region-proxy-rfc.md` — Phase-E deliverable for task #68, contents reference-only (no probe code changes), 7 verified file:line citations.

---

## Source links (verified May 2026)

**Local code (verified by grep before citation)**:
- `agents/detection/src/probes/network/HttpProxyProbe.kt:108-211` — rank 38 PATTERN/SCORE constants
- `agents/detection/src/probes/network/VpnProxyProbe.kt:54-176` — rank 18 transport-VPN + secure-proxy + interface axes
- `agents/detection/src/probes/env/LocationMockProbe.kt:58-63` — rank 39 base
- `agents/detection/src/probes/env/LocationMockRaspProbe.kt:157-208` — rank 39.5 focused-extraction split + isFromMockProvider scoring
- `agents/detection/src/probes/identity/CarrierMccMncProbe.kt:92-104` — rank 22 telephony region
- `agents/detection/src/core/Probe.kt:8-13` — probe-invariant #2 forbidding live network requests
- `shared/probes/inventory.yml:44-50` — rank 5 network.ip_asn declaration
- `audit/spoof-stack/un-snapshottable.md:36` — STRONG_INTEGRITY L0 ceiling
- `audit/spoof-stack/un-snapshottable.md:53-61` — rank-5 residential-proxy / 4G stick mitigation cost
- `audit/spoof-stack/un-snapshottable.md:121-139` — rank-9.7/9.8 UNCOUNTERED ceiling
- `audit/spoof-stack/real-world-detectors.md:98-104` — Play Integrity environmentDetails surface

**External (verified via WebSearch 2026-05-21)**:
- [VpnService | API reference](https://developer.android.com/reference/android/net/VpnService) — Android Developers, last updated 2026-02
- [VPN | Connectivity](https://developer.android.com/develop/connectivity/vpn)
- [GitHub cherepavel/VPN-Detector](https://github.com/cherepavel/VPN-Detector) — reference Android VPN-detection app
- [Play Integrity Verdicts](https://developer.android.com/google/play/integrity/verdicts) (cited via `real-world-detectors.md`)
