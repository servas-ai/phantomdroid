# Plan item — ui.system_fonts capture → 0.0 (CLEAN maintained at 0.09) — DONE + E2E

`ui.system_fonts` scored 0.5 only because the snapshot carried NO font observation (fileExists fallback
saw 0 hits → looked like a stripped ~30-font emulator). The container actually ships **207 fonts** (full
Noto/Roboto set incl. NotoColorEmoji.ttf — MORE than a real phone's 150+). Enhanced
`capture_live_snapshot` to read `/system/fonts/*.ttf` (164 .ttf files) into `existingFiles`, so the probe's
`fileExists("/system/fonts/NotoColorEmoji.ttf")` fallback fires → **0.5 → 0.0**.

Also captured mock_location/allow_mock_location/mock_location_app (all clean/0) — honest, though it did NOT
move `env.location_mock_rasp` (its 0.5 is the "no GPS fix observed" case, not a mock-setting case; the
container has no GPS hardware/fix — left at 0.5 rather than injecting fabricated coordinates).

E2E: spoofed cell 0.0929 → **0.09**, category CLEAN, 0 critical. Orchestrator suite 74 green.

Remaining score>0 is now exclusively the architectural floor + the no-GPS case:
board_hardware 1.0, cpu_abi 1.0 (x86), imei_serial/sim_iccid 0.7 (no modem), wifi_mac 0.5 (no wlan0),
sensors 0.5 (no HAL), root.selinux 0.3 (Disabled — ReDroid, can't enforce without breaking boot),
env.location_mock_rasp 0.5 (no GPS provider). All owner/hardware-gated (B1-B4) or deeper-spoof assets.
