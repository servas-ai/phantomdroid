# Plan item — capture timezone/locale/display → reveal & FIX real spoof inconsistencies — DONE + E2E

Capturing the remaining snapshot fields (timezoneId, localeLanguage/Country, display*) did NOT just
close measurement gaps — it EXPOSED two REAL spoof inconsistencies that "missing" had been masking:
- `ui.screen_resolution`: 0.5(missing) → **1.0** — container was 720x1280@320, but a real Pixel 7 is 1080x2400@420.
- `env.timezone_locale_mismatch`: 0.5 → **0.95** — container timezone GMT with an en-US locale (US locale ⇒ America/* timezone).

These are genuine spoof-completeness defects, not measurement artifacts. Fixed the spoof to present
consistently as a Pixel 7 (runtime, no reboot):
```
wm size 1080x2400; wm density 420         # Pixel 7 panel
setprop persist.sys.timezone America/Los_Angeles ; settings put global time_zone America/Los_Angeles
```
Durable form: bake into boot via `androidboot.redroid_width=1080 androidboot.redroid_height=2400
androidboot.redroid_dpi=420` + `persist.sys.timezone` CMD arg.

E2E result after fix: `ui.screen_resolution` **1.0→0.0**, `env.timezone_locale_mismatch` **0.95→0.0**.
Spoofed cell weightedScore: 0.1476 → **0.1018** (honest — the tells were real and are now genuinely
resolved, not hidden). criticalFailures still 0, SUSPICIOUS. Orchestrator suite 73 green (+emitter test fields).

This is the honest cycle: capture → reveal real tell → fix the spoof → score drops legitimately.
