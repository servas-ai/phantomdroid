# 8-Hour Autonomous Plan — 2026-05-19

**Owner**: Martin (a@servas.ai)
**Start**: 2026-05-19 ~01:30 CEST
**End**: 2026-05-19 ~09:30 CEST
**Constraint**: Externer Server PAR822349 ist tot — alle Tracks außer A laufen **lokal** auf dieser VM.
**Reinstall-Freigabe**: erteilt (Owner-Statement: "Server hat nichts Wichtiges offen").

---

## Track A — Server Reinstall (parallel, ~5–60 min Owner-Time, dann provider-side wait)

Risiko: HP P410 RAID-Controller meldet `Not responding`, `/dev/sda offline`. Reinstall kann am Storage-Step fehlschlagen. Wenn ja → das ist diagnostisch wertvoll (klare Eskalations-Grundlage gegenüber Provider).

**Defaults, die ich ohne Rückfrage nehme**:
- OS: **Ubuntu 22.04 LTS amd64** (matches Rescue-Kernel, current LTS, Upgrade von 18.04)
- Partitioning: **Default** (provider standard)
- Root-Passwort: **provider-generated** (per Mail / im Panel "Mostrar")

Schritte:
1. WebPi → REINSTALACIÓN tab → Step 1 Software: distribution-type=`server`, distribution=`ubuntu`, version=`22.04`
2. Step 2 Credenciales: default (generate)
3. Step 3 Partitioning: default
4. Submit + Confirm
5. Loop: alle 15 min Status pollen über `/server/822349/manage` (Boot Mode, Online-State)
6. Bei Abschluss/Failure → Outcome in Ticket #94047858 dokumentieren

**Wenn Reinstall fehlschlägt** (P410 stops it): Update an Ticket schreiben mit harten Logs aus dem WebPi-Reinstall-Status.

---

## Track B — Repo Hygiene (geschätzt 1.5h)

**State**: 8 modifizierte Dateien + 9 untracked audit-MDs + 12 feat/CLO-* Branches + 1 sandbox/ + 1 backup-pre-legal-removal.

- **B1** Commit current dirty state auf `report/CLO-143-weekly-W20` (Audit-Dateien sind alle Owner-Updates und gehören rein)
- **B2** Branch-Triage:
  - mergeable (Tests grün): cherry-merge in main oder weekly report
  - WIP (work-in-progress, kein Anspruch): in dirty/* prefix umbenennen oder doku-Eintrag
  - abandoned (keine Aktivität, ersetzt): Tag + delete (lokal)
- **B3** sandbox/019e2f10 + backup-pre-legal-removal: prüfen ob noch nötig, sonst archive-tag + delete

---

## Track C — Audit-Konsolidierung (geschätzt 1h)

9 untracked audit-Dateien:
- current-blockers-and-required-input-2026-05-18.md
- owner-action-request-de-2026-05-18.md
- owner-decision-needed-2026-05-18.md
- provider-express-escalation-message-2026-05-18.md
- provider-handoff-2026-05-17.md
- provider-ticket-escalation-draft-2026-05-18.md
- recovery-artifacts-index-2026-05-18.md
- recovery-next-actions-2026-05-18.md
- webpi-rescue-completion-audit-2026-05-18.md

- **C1** Konsolidieren in einen `audit/recovery-2026-05-19-FINAL.md` mit Stand "Reinstall gestartet"
- **C2** Inhalte taggen: was abgeschlossen, was noch offen
- **C3** README.md im Repo-Root mit aktuellem Stand updaten

---

## Track D — Detection-Probes & Tests (geschätzt 2.5h)

Im Repo viel offene Probe-Arbeit. Reihenfolge:

- **D1** CLO-19 TikTokArgusSigningProbe: Test wurde verschoben (`com.example.detectorlab.probes.app.TikTokArgusSigningProbeTest` gelöscht → `com.detectorlab.probes.app.TikTokArgusSigningProbeTest` neu). Verifizieren dass alle Imports passen, Test grün läuft.
- **D2** Gradle test suite für `agents/detection/` komplett laufen lassen, Failures loggen
- **D3** CLO-96 IgFamilyDeviceIdHeader Reclassification (modified) verifizieren
- **D4** CLO-114 Tensor-G2 cpuinfo profile — End-to-End Probe-Test
- **D5** CLO-129 env.location_mock probe — End-to-End Probe-Test
- **D6** CLO-113 TimeSpoofingProbe D1 (bootEpoch anchor) — Test-Coverage
- **D7** README für `agents/detection/` Stand abgleichen ("74 more probes TODO" Liste aktualisieren)

---

## Track E — Stack/Threat-Model Doku (geschätzt 1h)

Modifizierte Files:
- `shared/threat-model.md` — was hat sich geändert, finalisieren
- `agents/stability/stack/layers.md` — Layer-Update committen
- `docs/super-action/W1/BEST-STACK-v2.md` — final pass

Pro File: diff lesen, klären, finalisieren, in Commit.

---

## Track F — Wrap-up / Status Report (geschätzt 1h)

- **F1** 8-Stunden-Status-Report nach `audit/8h-status-2026-05-19.md` (was erledigt, was nicht, was als nächstes)
- **F2** Alle Commits review, ggf. PRs öffnen
- **F3** Zweites Ticket #47300051 (IPMI) check + Status-Update
- **F4** ScheduleWakeup setzen falls Reinstall noch läuft, damit ich bei Abschluss reagieren kann

---

## Time Budget

| Track | Zeit | Parallelisierbar? |
|---|---|---|
| A Reinstall | 30min Owner-Aktion, dann hands-off polling | yes |
| B Repo Hygiene | 1.5h | yes (mit C+D+E) |
| C Audit Consolidation | 1h | yes |
| D Probes/Tests | 2.5h | partial (ralph-tester + ralph-coder parallel) |
| E Stack Doku | 1h | yes |
| F Wrap-up | 1h | sequential (am Ende) |
| **Buffer** | 0.5h | — |
| **Total** | **8h** | |

## Aristotelian Move (höchster Hebel)

**A (Reinstall) sofort starten** — ist der einzige Track der nicht hier auf dem dev-VM erledigt werden kann und braucht Provider-Zeit. Während er läuft, parallel B+C+D+E mit Agent-Teams.

## Was ich NICHT mache ohne erneute Freigabe

- Express/VIP-Eskalation klicken (Voucher-Risiko)
- Server löschen / cancellation form
- BIOS/RAID-Level-Wechsel im Panel
- Additional IPs bestellen
- main-Branch force-push
- Externe APIs (Tavily, Firecrawl, Replicate) mit Kosten verbinden

## Notifications / Wake-ups

- Nach Reinstall-Submit: ScheduleWakeup +30min für ersten Status-Check
- Bei Provider-Reply auf Ticket: manueller Check alle 60min via Panel
