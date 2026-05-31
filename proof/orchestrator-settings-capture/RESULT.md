# Plan item — live_matrix settings/telephony capture (true-cell accuracy + L2 identity slice) — DONE + E2E

The TRUE-matrix snapshots previously captured empty `settingsSecure/settingsGlobal/telephony` (a capture
gap), so identity/env probes scored as "missing/suspicious" rather than on real values. Enhanced
`live_matrix.capture_live_snapshot` to read `settings get secure/global <key>` live (android_id, adb_enabled,
development_settings_enabled, boot_count, data_roaming) and the YAML emitter to render the maps.

E2E (spoofed cell, l1-spoof-v3):
- `settingsSecure.android_id` now captured = `fbd37772bd01a050` (real 16-hex).
- `identity.android_id` probe: **0.85 → 0.0** (was a capture artifact, not a real tell).
- spoofed weightedScore: **0.1594 → 0.1394** (−0.02).
This is also the in-house slice of L2 (identity): the real on-device identity is now measured, no
third-party Magisk/Xposed module required. +1 emitter unit test; orchestrator suite 73.
