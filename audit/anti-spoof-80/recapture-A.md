# Recapture-A — Anti-Spoof Re-verdict (3 previously-unreadable apps)

Environment: docker container `l1-spoof` — LIVE SPOOFED ReDroid 12 presenting as Google Pixel 7 / Android 12.
Driven ADB-free via `docker exec`. Screen 720x1280. Captured 2026-05-31.

Anti-fabrication note: every claim below is taken from the final PNG that was actually read with the Read tool. Paths are absolute.

## Verdict Table

| App | Identity shown | Tell(s) found | Verdict |
|---|---|---|---|
| net.techet.netanalyzerlite.an (Network Analyzer) | "Network Analyzer" app shell; all data panes (Information / Wi-Fi Signal / LAN Scan) render blank | No emulator/root flag. App posts a "won't run without Google Play services, which are not supported by your device" notice (GMS-absence, not anti-emulator). Data content never populates, so no device identity is rendered. | UNREADABLE |
| ua.com.streamsoft.pingtools (PingTools) | Device IP 172.17.0.5, Gateway 172.17.0.1, Public IP 152.53.35.28 "Unknown ISP", "There are no active wireless interfaces", 0 devices online | LEAK: Docker bridge addressing 172.17.0.5 / 172.17.0.1 (container network), plus "no active wireless interfaces" (no Wi-Fi radio). Both betray the virtualized container. | LEAK |
| com.finalwire.aida64 (AIDA64 — System page) | Manufacturer Google, Model Pixel_7, Brand google, Board/Device/Product panther, Platform gs201 (all genuine Pixel 7) | LEAK: "Hardware: redroid" (ro.hardware still reads the ReDroid container fingerprint). Also Total Memory 62.79 GB and Internal Storage 2014.50 GB — server-class values, not a phone. | LEAK |

## Detail

### Network Analyzer — UNREADABLE
Cleared three onboarding gates: "Device ID / built for older Android" (OK), "kept free by showing ads" (Use ad-supported app), GDPR consent (Do not consent). Reached the app's navigation drawer (Information / Wi-Fi Signal / LAN Scan / Tools / About). A dialog stated the app "won't run without Google Play services, which are not supported by your device." Every content pane (Information, LAN Scan) renders blank — the real data screen never populates, so no identity/network data is visible to classify. No active emulator/root detection was shown. Classified UNREADABLE because the data screen could not be made to render content.
Final PNG: /home/coder/vk-repos/phantomdroid/audit/anti-spoof-80/evidence-full/net.techet.netanalyzerlite.an-v2.png

### PingTools — LEAK
Cleared welcome (NEXT) and license (AGREE) gates to reach the dashboard. Visible data:
- Device IP **172.17.0.5**
- Gateway **172.17.0.1**
- Public IP 152.53.35.28, "Unknown ISP"
- "There are no active wireless interfaces"
- 0 devices online
The 172.17.0.x Docker default-bridge addressing and the absence of any wireless interface are residual virtualization tells.
Final PNG: /home/coder/vk-repos/phantomdroid/audit/anti-spoof-80/evidence-full/ua.com.streamsoft.pingtools-v2.png

### AIDA64 — LEAK
No consent gates; main menu loaded directly. Opened System page. Visible data:
- Manufacturer Google / Model Pixel_7 / Brand google / Board panther / Device panther / Platform gs201 / Product panther — consistent genuine-Pixel-7 build fingerprint.
- **Hardware: redroid** — `ro.hardware` still carries the ReDroid container name; the spoof overlaid build props but missed this property.
- Total Memory 62.79 GB and Internal Storage Total 2014.50 GB — host-server scale, implausible for a phone.
Final PNG: /home/coder/vk-repos/phantomdroid/audit/anti-spoof-80/evidence-full/com.finalwire.aida64-v2.png

## Active tells found (summary)
- PingTools: container Docker IPs 172.17.0.5 / 172.17.0.1; no wireless interface.
- AIDA64: `ro.hardware = redroid`; server-scale memory/storage (62.79 GB RAM, 2 TB storage).
- Network Analyzer: no detection tell, but GMS-absent and data never renders.
