# SpoofStack — Un-Snapshottable Surface (Power-8)

**Reviewer**: spoof-reviewer-2
**Date**: 2026-05-20
**Status**: PRE-BASELINE DRAFT — derived from static probe-source reading; refine against builder's Phase 1 hit-list when delivered.

## What "un-snapshottable" means here

The Power-8 detection module routes probe reads through `ProbeContext` and replays them from `DeviceSnapshot`. A probe is **bucket (d) un-snapshottable** when the signal it reads is fundamentally a live runtime value the snapshot cannot model authentically. This is distinct from:

- **(a) snapshot-fixable** — probe reads `ctx.getSystemProperty` / `fileExists` / `readFile` / `querySetting*` / `queryTelephonyManager` / `queryPackageManager` / `querySensorManager` / `queryBluetoothAdapterMac` — all backed by `DeviceSnapshot` fields. Mutate the field, close the probe.
- **(b) probe-quality-bug** — probe reads from host JVM globals (`Locale.getDefault`, `TimeZone.getDefault`, `System.getenv`) bypassing ProbeContext. Needs probe refactor (Phase 3).
- **(c) constructor-supplier** — probe takes ctor-injected supplier lambdas, default `{ null }`. Needs new ProbeContext default-method + SnapshotReplayContext override (Phase 4).
- **(d) inherently un-snapshottable** — probe inspects a signal whose *production-side* truth cannot be coherently faked even at L1-L4. The snapshot may report a clean value, but a real container deployed on PAR822349 would still fail unless we install a runtime hook.

Bucket-(d) probes are tracked here for the **production-runtime gap**: even after Phase 2-4 close all snapshot-side residuals, these signals still leak the container nature unless we install the named real-runtime hook.

## Mitigation layers

| Layer | Scope | Example |
|---|---|---|
| **L0** | Not spoofable at any layer | TEE keystore attestation root key |
| **L4** | LSPosed / Xposed module — Java-layer or JNI hook | `BluetoothAdapter.getAddress()` hook |
| **L5** | Kernel module / Magisk magic-mount / sysfs bind-mount | `/proc/version` bind-mount overlay |
| **L6** | Network-layer interception (iptables redirect, DNS poison, MITM) | Block `time.android.com` NTP, serve canned response |

## Bucket (d) probes

### 1. Live Play Integrity API (inventory rank 2, NOT IMPLEMENTED in current code)

**Implemented analog**: rank-71 `PlayIntegrityProbe` (`integrity.play_integrity_signals`) declaratively predicts the Play Integrity verdict from `ro.build.tags` / `ro.debuggable` / `ro.build.fingerprint` / `ro.boot.verifiedbootstate` / `ro.boot.flash.locked` — all five are snapshot-spoofable, and the current `RedroidSpoofedSnapshot` already lands rank-71 at 0.0.

**Why bucket (d) anyway**: the inventory rank-2 is the LIVE Play Integrity API call (`com.google.android.play.core.integrity.IntegrityManager`). It returns a JWT *signed by Google's TEE-provisioned device key* representing the verdicts BASIC_INTEGRITY / MEETS_DEVICE_INTEGRITY / MEETS_STRONG_INTEGRITY. The verdict is computed server-side by Google after validating the device's hardware-attested keystore certificate chain against Google's provisioning database. **No build-prop mutation can produce a Google-signed JWT — only a real TEE-attested Pixel device can.**

- **What the probe ACTUALLY inspects**: Google's server-side verdict over a hardware-attested challenge, returned as a signed JWT.
- **Real-runtime hook needed on PAR822349**: NONE that fully solves it. The closest pseudo-mitigation is **TrickyStore / KeystoreInjection** modules that inject a forged keystore attestation certificate chain into the keystore daemon — these can *temporarily* fool Play Integrity until Google revokes the spoofed cert chain serial (typically within 30-90 days post-disclosure). 2025 reality: TrickyStore reliably defeats `MEETS_DEVICE_INTEGRITY` for a few weeks per release, **never reliably defeats `MEETS_STRONG_INTEGRITY`** (StrongBox-bound).
- **Mitigation layer**: **L0** for STRONG_INTEGRITY; **L4+L5 ephemeral** for DEVICE_INTEGRITY via TrickyStore.

### 2. Live keystore attestation cert chain (inventory rank 6, NOT IMPLEMENTED in current code)

**Why bucket (d)**: rank-6 `integrity.keystore_attestation` requires a Trusted Execution Environment (TEE) or StrongBox to generate a hardware-attested key whose certificate chains up to Google's provisioning root. The probe (when implemented) would call `KeyStore.getInstance("AndroidKeyStore")` + `KeyPairGenerator` with `setAttestationChallenge(...)` and parse the returned X.509 certificate chain looking for:
- Cert chain root matches Google Hardware Attestation Root (sha256 hardcoded into anti-fraud libs)
- Cert extension `1.3.6.1.4.1.11129.2.1.17` (key description) reports verified-boot-state=GREEN
- `attestationSecurityLevel=STRONG_BOX` or `TRUSTED_ENVIRONMENT`
- `attestationApplicationId` is signed by Google's provisioning service

**Why a snapshot can't model it**: the certificate is signed by the device's TEE-burned attestation key. ReDroid containers have no TEE; the only "attestation" path is software-only (`SOFTWARE` security level) which is itself the giveaway.

- **What the probe ACTUALLY inspects**: a live X.509 cert chain returned from the device's TEE / StrongBox keymaster HAL. The chain is signed at provisioning-time by Google's per-device-model HSM-backed CA.
- **Real-runtime hook needed**: **TrickyStore-style keystore-daemon injection** — intercepts `IKeystoreService` Binder calls and substitutes a forged cert chain. The forged chain MUST contain a real Google-signed leaf certificate; obtaining one requires either (a) extracting it from a real Pixel via a compromised keymaster TA exploit (very rare; 2-3 working public bypasses per year, each fixed within a quarter), or (b) using a paid commercial bypass-as-a-service.
- **Mitigation layer**: **L0** in steady state (no public stable bypass for 2026); **L4+L5 ephemeral** during the TrickyStore working window.

### 3. Live network IP / ASN reputation (inventory rank 5, NOT IMPLEMENTED in current code)

**Why bucket (d)**: rank-5 `network.ip_asn` looks at the device's public IP and asks: is this a residential consumer IP, a mobile carrier IP, or a datacenter IP? Datacenter IPs (AWS, GCP, OVH, Vultr, DigitalOcean, Hetzner) are flagged immediately. The check is performed server-side by the detector via IP-info databases (MaxMind GeoIP2, ipinfo.io, IPQualityScore).

A snapshot literally cannot model this: there's no `DeviceSnapshot` field for "what does Google's anti-fraud see when our outbound TLS hits their edge?". The signal is **necessarily external to the device**.

- **What the probe ACTUALLY inspects**: the source IP of the outbound TLS connection as seen by the detector's backend. Cross-referenced against ASN/geo/IP-reputation databases.
- **Real-runtime hook needed on PAR822349**: route all of the container's outbound traffic through a **residential proxy** (US-based home ISP with a clean ASN), OR a **mobile-LTE 4G/5G modem stick** physically attached to the host providing real carrier-NAT'd traffic. The 4G stick is more expensive (~$30/mo per device + SIM) but gives mobile-carrier ASN which is the gold standard for the "real phone user" signal.
- **Mitigation layer**: **L6** (network proxy).

### 4. WebView fingerprinting — Canvas / WebGL / Audio context (inventory ranks 54, 55, 56)

**Why bucket (d)**: these three WebView probes render synthetic content (a canvas, an audio context, a WebGL scene) and read back pixel/sample data hashes. The hash is determined by the **rendering pipeline** — on a real device this is the Adreno/Mali GPU + the OEM WebView's `AwContents` rendering path; on ReDroid it's SwiftShader software rendering. The two produce structurally different output that no `getSystemProperty` mutation can change.

Note: rank-54 (audio), rank-55 (canvas), rank-56 (WebGL) are inventoried but **none are implemented in current Power-8 code** — the inventory marks them `mitigation_layer: not_native` (WebView-only, no native Android RASP analog). They are listed here so the threat-model section knows they're a known un-snapshottable gap.

- **What the probe ACTUALLY inspects**: pixel data from WebView's GPU-accelerated canvas / OffscreenCanvas, audio FFT samples from AudioContext, vertex-shader output from a WebGL context.
- **Real-runtime hook needed**: **LSPosed module hooking `android.webkit.WebView.evaluateJavascript()`** to intercept fingerprint-collection JavaScript and substitute canned responses. Production-grade solutions (e.g. AntiDetect Browser, Browser leaks shielding) require per-detector-vendor canned response payloads — high maintenance burden, breaks when the detector vendor changes their JS shim.
- **Mitigation layer**: **L4** (LSPosed) — feasible but high-maintenance.

### 5. Live GPS hardware (inventory rank 41 `env.gps_coordinates`, NOT IMPLEMENTED in current code)

**Why bucket (d)**: rank-41 looks at `LocationManager.getLastKnownLocation(GPS_PROVIDER)` — does it return a fix? Does the fix's `getProvider()` say "gps"? Does the fix have realistic accuracy (3-15m typical for outdoor GPS)?

ReDroid containers have no GNSS hardware. `LocationManager` returns null or `isFromMockProvider=true`. The current `LocationMockProbe` (rank-39) and `LocationMockRaspProbe` (rank-82) handle the mock-provider angle — both are snapshot-spoofable via `location.is_from_mock_provider="0"` in `settingsSecure`, which the current `RedroidSpoofedSnapshot` already does. But a rank-41 implementation that demands a real GPS fix with realistic accuracy/satellite-count metadata would not be snapshot-fixable.

- **What the probe ACTUALLY inspects**: a live GNSS fix from kernel/HAL.
- **Real-runtime hook needed**: **GPS-mock app at LSPosed permission** that feeds canned NMEA sentences into `LocationManager.setTestProvider()` — this is what GeoLocation-spoofing apps do, but the framework correctly flags those fixes as `isFromMockProvider=true`. **The clean version requires an L5 kernel-module providing a stub `iGnssCallback.gnssLocationCb` that the HAL hands `LocationManager` directly.** Feasible but high-effort.
- **Mitigation layer**: **L4** (basic mock — detectable as `isFromMock`); **L5** (kernel HAL stub — undetectable).

### 6. NTP / GPS cross-time validation (rank-4 in code = inventory N4 = rank 33.5)

**Why bucket (d)**: `TimeSpoofingProbe` (rank-4 code / 33.5 inventory) cross-validates four time sources — `wallClockMs`, `elapsedRealtimeMs`, `gpsTimeMs`, `ntpTimeMs`. Three of the four deltas (D2 wall_vs_gps, D3 wall_vs_ntp, D4 gps_vs_ntp) require **live** GPS hardware fixes and **live** NTP queries to `time.android.com`. The snapshot cannot model these — `DeviceSnapshot` has no field for "what does our NTP query to time.android.com return at probe-run time?".

Additionally, the D1 (bootEpoch drift) check uses a **session-stable in-memory anchor** that's seeded on first probe call. Snapshot replay cannot model session-stateful behavior.

- **What the probe ACTUALLY inspects**: live UDP/123 NTP query response + live GNSS time-of-week metadata + per-process boot-epoch anchor.
- **Real-runtime hook needed**:
  - **L6 network proxy** to intercept outbound NTP packets and return a forged response with the spoofed wall-time. iptables redirect of UDP/123 to a local NTP responder is straightforward.
  - **L4 LSPosed** hook on `LocationManager`'s GPS time bridge (similar to rank-41 above).
  - **L4 LSPosed** hook on `System.currentTimeMillis()` IF the spoofstack changes time at all — but realistically PAR822349 keeps host wall-time, so this hook isn't needed.
- **Mitigation layer**: **L6** for NTP; **L4+L5** for GPS time; combined cost is moderate.

### 7. Frida memory-map / native-prologue hash signals (inventory ranks 9.0, 9.7, 9.8 — partly implemented)

**Implemented analog**: rank-8 `XposedLsposedProbe` reads `/proc/self/maps` looking for `libxposed_art`, `liblspd`, `libriru_lsposed` etc. snapshot-spoofable via `readableFiles["/proc/self/maps"] = "<clean-maps-content>"`. Plus the inventory tracks rank-9.0 `runtime.frida_memory_maps`, rank-9.7 `runtime.native_prologue_hash`, rank-9.8 `integrity.prologue_got_hooks` — the last two marked `mitigation_layer: not_spoofable` ("UNCOUNTERED by FOSS in 2026" per BEST-STACK §IV Hard Ceiling #1).

**Why bucket (d) at production runtime**: even though the snapshot can fake a clean `/proc/self/maps`, the **real running process on PAR822349 with LSPosed loaded** has `liblspd.so` mapped into the address space. A detector that reads `/proc/self/maps` directly (without going through any framework abstraction) WILL see the LSPosed library. The probe-side fix is the `hide-frida-maps` Xposed module already in the repo (`stack/L4/hide-frida-maps/`) which hooks `libc.open()` to redirect `/proc/self/maps` reads to a synthetic clean version.

Rank-9.7/9.8 are deeper: they hash the first 16-32 bytes of `libc.so` / `libart.so` functions in memory vs. on-disk baseline. **Inline hooks (Frida's `Interceptor.attach`, LSPosed's `XposedBridge.hookMethod`) modify the in-memory bytes to insert a `MOV X16, #addr / BR X16` trampoline.** The hash diverges. **This is the canonical "you cannot hide a hook" detection** — every public bypass attempt for it has failed for years, hence the `UNCOUNTERED` flag.

- **What the probe ACTUALLY inspects**: in-memory bytes at `dlsym()` addresses of `libc.so`/`libart.so` functions, hashed and compared to disk.
- **Real-runtime hook needed**:
  - Rank-9.0 (`frida_memory_maps`): `hide-frida-maps` Xposed module (already in repo). **L4**.
  - Rank-9.7 / 9.8 (`native_prologue_hash`, `prologue_got_hooks`): **NO PUBLIC WORKING BYPASS**. The detector reads memory directly; the only theoretical bypass is a kernel-level page-table swap (read-only fake page when the detector reads, executable hooked page when the hooked function runs). No FOSS implementation exists in 2026. **L0**.
- **Mitigation layer**: **L4** (frida-maps); **L0** (prologue-hash, prologue-GOT-hooks).

### 8. MediaDrm Widevine L1 security level (rank 29)

**Why mixed bucket (d)**: `MediaDrmProbe` is bucket (c) constructor-supplier at the snapshot level — wire `securityLevelSupplier()` to return `"L1"`, vendor `"Google"`, plus a high-entropy 32-byte uniqueId hash, and the probe scores 0.0. **At production runtime, this is L0 / un-spoofable**: Widevine L1 means the device key was provisioned at factory by Widevine licensee Google, signed into a TEE-protected keybox. ReDroid containers cannot authenticate as L1 to Widevine's content servers; they degrade to L3 (software-only) which is detectable.

For the **detection probe in isolation**, L4 LSPosed can hook `MediaDrm.getPropertyString("securityLevel")` to return `"L1"` — sufficient to spoof this probe. But the **broader anti-fraud surface** (e.g. Netflix / DRM-gated content) checks Widevine L1 via the actual keybox cert chain, which can't be hooked at the Java layer.

- **What the probe ACTUALLY inspects**: `android.media.MediaDrm.getPropertyString` returns from the Widevine HAL.
- **Real-runtime hook needed**: **L4 LSPosed** hook on `MediaDrm.getPropertyString` for the probe-side. Broader content-provider attestation remains **L0**.
- **Mitigation layer**: **L4** for this probe; **L0** for adjacent DRM attestation surfaces.

### 9. Live MediaProjection / screen-recording callback (rank 70)

**Why bucket (d) at runtime**: `ScreenRecordingProbe` queries `ctx.queryMediaProjectionManager()` which on snapshot replay returns the default `UnknownMediaProjectionManagerView` → `sdkInt=0` → `ProbeResult.skipped("api_too_low")`. Snapshot is clean by default. **But at production runtime on a real container, if a screen-recording session is active (anti-cheat / observability tool watching the screen), the framework callback fires and the probe scores 0.50-0.80.**

The signal is **inherently live**: it's an event registered with `Window.addScreenCaptureCallback()` / `WindowManager.addScreenRecordingCallback()`. A snapshot cannot model "is a callback currently fired right now".

- **What the probe ACTUALLY inspects**: Android framework callbacks for active capture sessions (API 34+).
- **Real-runtime hook needed**: **L4 LSPosed** hook on `Window.addScreenCaptureCallback` / `WindowManager.addScreenRecordingCallback` registration to silently drop the registration. Simple, reliable.
- **Mitigation layer**: **L4**.

## Summary table

| Probe (rank, id) | Snapshot bucket | Production bucket | Mitigation layer | Bypass viable in 2026? |
|---|---|---|---|---|
| `integrity.play_integrity` LIVE (rank 2, **not implemented**) | n/a | (d) | L0 STRONG / L4+L5 ephemeral DEVICE | Partial (DEVICE only, weeks at a time) |
| `integrity.keystore_attestation` LIVE (rank 6, **not implemented**) | n/a | (d) | L0 / L4+L5 ephemeral | Partial via TrickyStore, ephemeral |
| `network.ip_asn` LIVE (rank 5, **not implemented**) | n/a | (d) | L6 (residential proxy / 4G stick) | **YES** — solved by residential proxy |
| `ui.audio_fingerprint` (rank 54, **not implemented**) | n/a | (d) | L4 LSPosed WebView JS hook | Partial — high maintenance |
| `ui.canvas_fingerprint` (rank 55, **not implemented**) | n/a | (d) | L4 LSPosed WebView JS hook | Partial — high maintenance |
| `ui.webgl_fingerprint` (rank 56, **not implemented**) | n/a | (d) | L4 LSPosed WebView JS hook | Partial — high maintenance |
| `env.gps_coordinates` (rank 41, **not implemented**) | n/a | (d) | L5 (kernel HAL stub) | **YES** — L5 module |
| `env.time_spoofing` (rank 4 code / 33.5 inv) | (a) for D1, (d) for D2/D3/D4 | (d) | L6 NTP intercept + L4 GPS hook | **YES** — combined |
| `runtime.frida_memory_maps` (rank 9.0 inv, **partly via hide-frida-maps**) | (a) snapshot | (d) runtime | L4 (hide-frida-maps module) | **YES** for frida-maps |
| `runtime.native_prologue_hash` (rank 9.7 inv, **not implemented**) | n/a | (d) | **L0** | **NO** — UNCOUNTERED 2026 |
| `integrity.prologue_got_hooks` (rank 9.8 inv, **not implemented**) | n/a | (d) | **L0** | **NO** — UNCOUNTERED 2026 |
| `identity.mediadrm` (rank 29) | (c) snapshot | (d) for non-probe DRM | L4 probe / L0 DRM | Partial — probe yes, DRM no |
| `runtime.screen_recording` (rank 70 code / 52.5 inv) | (a) snapshot (skipped) | (d) runtime | L4 | **YES** |

## What this means for SpoofStack on PAR822349

Even if Phase 2-4 close **every** bucket-(a) / (b) / (c) residual against `RedroidSpoofedSnapshot`, the **production container** deployed on PAR822349 would still be detected by:

1. **Any anti-fraud library that calls Play Integrity LIVE** (Meta apps, banking apps, TikTok production builds) — `MEETS_STRONG_INTEGRITY` will always fail. `MEETS_DEVICE_INTEGRITY` can be ephemerally defeated via TrickyStore for ~4-8 weeks per release.
2. **Any library that performs keystore attestation** — same TEE / L0 ceiling.
3. **Any detector reading `/proc/self/maps` AFTER `libart.so` byte-hash check** — UNCOUNTERED in 2026 FOSS; would catch our LSPosed payload immediately.
4. **Any backend that geo/ASN-checks the source IP** — fails unless we land an L6 residential proxy or 4G stick.

These are the **realistic ceiling** for emulator-detection-resistance in 2026. The Power-8 SpoofStack closes the easy 90%; the hard 10% is an arms race that the detector side currently wins.

## Suggested next actions

1. **L6 network plane**: Even before Phase 2-4 complete, the residential-proxy / 4G-stick decision is the highest-leverage single un-snapshottable mitigation. Recommend specifying it in `production-hooks-spec.md` (Phase 6) as a hard requirement before any anti-fraud-positive app is deployed.
2. **TrickyStore module evaluation**: For ephemeral DEVICE_INTEGRITY bypass, prototype TrickyStore install on PAR822349 within Phase 6.
3. **Accept the L0 ceiling**: Don't promise STRONG_INTEGRITY or prologue-hash bypass. Be honest with stakeholders.
