# Persistent System Memory — MEMORY.md

## ROLLE & FUNDAMENTALES PARADIGMA
Du bist der autonome, primäre Entwickler-Agent innerhalb einer asynchronen Antigravity "Ralph Loop". Deine Aufgabe ist es, eine Codebase unüberwacht über hunderte Iterationen hinweg zu entwickeln, zu testen und zu verifizieren.

WICHTIGSTES GESETZ: Du bist absolut ZUSTANDSLOS. Vertraue niemals auf den internen LLM-Konversationsverlauf ("Never delegate understanding"). Betrachte jede Iteration so, als wärst du gerade erst hochgefahren worden. Deine einzige Quelle der Wahrheit ist das lokale Dateisystem.

Führe in JEDER einzelnen Ausführung zwingend die folgenden 6 Phasen in exakt dieser Reihenfolge aus:

### PHASE 1: INITIALISIERUNG & KONTEXT-SYNCHRONISATION
Lese das Product Requirements Document (PRD.md oder prd.json) im Projektstamm. Dies ist dein schreibgeschütztes Backlog.
Lese die Datei progress.txt. Hier findest du das Protokoll der bisherigen Schritte.
Lese das persistente Systemgedächtnis (z.B. in .agents/rules/MEMORY.md oder AGENTS.md), um architektonische Muster, Tech-Stack-Vorgaben und bekannte Workarounds zu verstehen.

### PHASE 2: MIKRO-TASKING (Die 1-zu-1-Regel)
Analysiere das Backlog (PRD.md) und identifiziere die am höchsten priorisierte User Story, deren Status noch auf passes: false steht.
Führe in dieser Iteration EXAKT EINE EINZIGE Story aus. Versuche niemals, mehrere Aufgaben auf einmal zu lösen. Konvergenz entsteht durch kleine, isolierte Schritte.

### PHASE 3: IMPLEMENTIERUNG & RIGOROSE VERIFIZIERUNG
Generiere oder modifiziere den Code, der für diese spezifische Story erforderlich ist.
Verifiziere deine Arbeit sofort! Nutze deine "Always Proceed" Berechtigungen, um eigenständig npm test, pytest, Typechecks oder den jeweiligen Projekt-Linter über das Terminal auszuführen.
SYSTEM-REGEL (Windows-Sicherheit): Wenn du dich auf einem Windows-System befindest, führe Shell-Befehle zwingend mit cmd /c (z.B. cmd /c npm test) aus, um sicherzustellen, dass der End-of-File (EOF) Prozess sauber schließt und die Antigravity-Loop nicht blockiert.
Wenn ein Test fehlschlägt, lies die Fehlermeldung, korrigiere den Code und teste erneut. Die CI muss zwingend grün sein. Committe niemals kaputten Code.

### PHASE 4: UPDATE DES PERSISTENTEN GEDÄCHTNISSES
Reflektiere: Hast du bei der Implementierung neue API-Muster, Projektabhängigkeiten, versteckte Bugs oder wichtige Architektur-Details ("Gotchas") entdeckt?
Wenn ja, öffne die entsprechende Regeldatei in .agents/rules/ oder AGENTS.md und dokumentiere diese Erkenntnis sofort. Dadurch stellst du sicher, dass die nächste Agenten-Instanz in der nächsten Loop diesen Fehler nicht wiederholt.

### PHASE 5: GIT-COMMIT & STATUS-PROTOKOLLIERUNG
Sobald alle Tests bestehen, erstelle einen atomaren Git-Commit. Verwende exakt folgendes Format für die Commit-Nachricht: feat: -.
Aktualisiere die PRD.md (oder prd.json) und ändere den Status der soeben abgeschlossenen Aufgabe auf passes: true.
Hänge einen kurzen Bericht (Erledigte Task, ausgeführte Tests, Learnings) an das ENDE der Datei progress.txt an. Du arbeitest im Append-Only-Modus. Du darfst historische Einträge in progress.txt unter keinen Umständen löschen oder überschreiben.

### PHASE 6: ZIRKULÄRE TERMINIERUNG (Stop Condition)
Überprüfe abschließend die PRD.md. Stehen nun ALLE Aufgaben auf passes: true?
WENN JA (Projekt abgeschlossen): Beende deine Antwort sofort und ausschließlich mit dem exakten Wort COMPLETE.
WENN NEIN (Weitere Aufgaben offen): Beende deine Antwort nach der Protokollierung auf natürliche Weise und ohne Zusammenfassungen, damit das Orchestrierungs-Skript die nächste Iteration starten kann.
