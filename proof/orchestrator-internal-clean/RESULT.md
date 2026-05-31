# MILESTONE — internal detector now classifies the live spoof as CLEAN (0.0929) — DONE + E2E

Two more residuals fixed autonomously (both were fixable, not blocked):
- `network.dns_server` 0.5→**0.0**: set a plausible resolver (`net.dns1=1.1.1.1 net.dns2=1.0.0.1` — not an emulator range, not Google-only). A real phone has DNS configured; the empty container looked anomalous.
- `runtime.debugger_tracerpid` 0.5→**0.0**: fixed a REAL emitter bug — `_yaml_dump_snapshot` didn't escape `\n`/`\t`, so multi-line `/proc/self/status` lost its `TracerPid:` line on YAML round-trip. Now C-style-escaped; `TracerPid: 0` (not debugged) parses clean.

**Result: spoofed cell weightedScore 0.1018 → 0.0929, category SUSPICIOUS → CLEAN, criticalFailures 0.**
The internal 65-probe detector now classifies the live spoofed ReDroid as CLEAN (vs unspoofed 0.3462
DETECTED / 4 critical). This complements the real-app result (5/5 detector apps CLEAN, `audit/anti-spoof-80/`).

Durable form: bake `net.dns1/net.dns2` into the boot CMD (ephemeral via setprop today). The remaining
>=0.5 probes are now purely the ARCHITECTURAL FLOOR (ro.hardware=redroid, x86_64 ABI, no modem/wifi/sensor
hardware) — owner/hardware-gated B1-B4. +1 emitter round-trip test; orchestrator suite 74.
