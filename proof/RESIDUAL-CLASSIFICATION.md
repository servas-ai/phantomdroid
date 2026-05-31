# Definitive residual classification — what the live-spoofed 0.1394 is made of (2026-05-31)

After closing the android_id capture gap (0.1594→0.1394), the remaining ≥0.5 probes on the live
spoofed cell (l1-spoof-v3) classify into ARCHITECTURAL (cannot be fixed autonomously — needs an arm64
host / TEE / third-party module) vs MEASUREMENT-MODEL GAP (the detection snapshot doesn't capture the
field yet — like android_id was; not a real device tell). Backed by live `docker exec` evidence.

## ARCHITECTURAL (irreducible floor — owner/hardware-gated)

| Probe | Score | Live evidence | Class |
|---|---|---|---|
| emulator.cpu_abi | 1.0 | `ro.product.cpu.abi=x86_64` (host is x86) | needs arm64 ReDroid host |
| buildprop.board_hardware | 1.0 | `ro.hardware=redroid` (override breaks boot — B4) | posture/kernel |
| identity.imei_serial | 0.7 | `gsm.sim.state` empty — no modem | container has no telephony HW |
| identity.sim_iccid | 0.7 | no SIM | container has no telephony HW |
| identity.wifi_mac | 0.5 | `/sys/class/net/wlan0` absent (only eth0 veth) | container has no WiFi radio |
| sensors.accelerometer_gyro | 0.5 | `dumpsys sensorservice` ≈1 sensor | no sensor HAL (B2 L5 / VirtualSensor) |

These six are the genuine emulator floor: a software container on an x86 host with no modem/WiFi/sensor
hardware. Removing them needs an arm64 bare-metal host + a sensor-HAL/VirtualSensor module + a modem
bridge — all supply-chain/hardware (B1/B2) or owner (B4) gated.

## MEASUREMENT-MODEL GAPS (fixable — the snapshot doesn't capture these yet; not real tells)

| Probe | Score | Why it's a gap |
|---|---|---|
| env.language_country | 0.85 | snapshot has no locale field (device has a real locale) |
| ui.input_method | 0.7 | snapshot has no default_input_method capture |
| env.timezone_locale_mismatch | 0.5 | no timezone/locale capture |
| env.location_mock_rasp | 0.5 | no location-provider capture |
| network.dns_server | 0.5 | no net.dns capture |
| runtime.debugger_tracerpid | 0.5 | no /proc/self/status TracerPid capture |
| ui.screen_resolution | 0.5 | no `wm size` capture |
| ui.system_fonts | 0.5 | no font-list capture |

These scored as "missing/suspicious" purely because `live_matrix` doesn't yet read those fields — exactly
the android_id situation (which dropped 0.85→0.0 once captured). They are measurement-completeness gaps,
NOT device tells; closing each (same pattern as the settings capture) would lower the score further
toward the architectural floor. Tracked as incremental capture work, not blockers.

## Bottom line
The live spoofed device's irreducible detectability is the **6 architectural probes** (x86 ABI, redroid
hardware string, and absent modem/WiFi/sensor hardware). Everything else in the 0.1394 is measurement
incompleteness. No mainstream verdict-emitting detector app flags the device (proven: 5/5 CLEAN,
`audit/anti-spoof-80/`); the architectural floor only matters to a custom probe aggregator.

---

## Update 2026-05-31 (post capture-completion + spoof-consistency fixes)

Spoofed cell driven **0.1594 → 0.1018** via verified+pushed work:
- android_id captured → identity.android_id 0.85→0.0
- locale captured → env.language_country 0.85→0.0
- default_input_method captured → ui.input_method 0.7→0.0
- **resolution fixed** (1080x2400@420 Pixel-7) → ui.screen_resolution 1.0→0.0
- **timezone fixed** (America/Los_Angeles, consistent w/ en-US) → env.timezone_locale_mismatch 0.95→0.0

Two of those (resolution, timezone) were REAL spoof-incompleteness tells that "missing" had masked —
fixed in the spoof, not hidden. Remaining ≥0.5 probes are now the ARCHITECTURAL FLOOR + deeper-spoof items:
- Architectural (owner/hardware-gated B1-B4): buildprop.board_hardware (ro.hardware=redroid), emulator.cpu_abi (x86_64), identity.imei_serial/sim_iccid (no modem), identity.wifi_mac (no wlan0), sensors.accelerometer_gyro (no sensor HAL).
- Deeper-spoof (need module/asset, not just capture): ui.system_fonts (would need a Pixel font overlay bind-mount), env.location_mock_rasp (needs a mock-location provider), network.dns_server (container has no resolver config), runtime.debugger_tracerpid (no faithful host-side capture).

The verdict-app reality is unchanged: 0 mainstream detector apps flag the device (5/5 CLEAN). The 0.1018
internal residual matters only to a custom probe aggregator and is now dominated by the irreducible
no-modem/no-wifi/no-sensor/x86 hardware floor.
