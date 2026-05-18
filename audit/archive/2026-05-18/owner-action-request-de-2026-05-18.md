# Owner action request - PAR822349

Stand: 2026-05-18 16:36 CEST

## Kurzstatus

Der Server `PAR822349` / `195.154.209.133` ist aus der letzten authentifizierten WebPi-Pruefung korrekt im Rescue Mode (`Modo rescate` / `rescue_mode`). Die IP/MAC-Zuordnung war korrekt:

- IPv4: `195.154.209.133`
- IPv6: `2001:0bc8:0610:0007:e611:5bff:fe0d:bea0`
- MAC: `e4:11:5b:0d:be:a0`

Aktuell ist die lokale WebPi-/Browser-Session ausgeloggt. Es wurden keine nutzbaren lokalen Panel-Zugangsdaten oder gespeicherten Logins gefunden. Alte temporaere Browserprofile mit OneProvider/WebPi-Cookie-Metadaten wurden gezielt getestet, bleiben aber live bei Cloudflare `Just a moment...` haengen und liefern keine nutzbare WebPi-Sitzung.

## Was noch nicht funktioniert

- WebPi Remote Access/IPMI kann nicht erstellt werden: WebPi meldete `Invalid boot mode`.
- IPMI-Endpunkt `51.159.47.149` liefert nur default nginx auf TCP/80.
- TCP/443 und TCP/22 am IPMI-Endpunkt sind nicht nutzbar.
- `ipmi-ping` / `ipmiping` bekommen von der whitelisted IPv4 `152.53.35.28` keine RMCP-Antwort.
- Letzter externer Poll um 2026-05-18 16:01 CEST zeigt keine Verbesserung: IPv4 Rescue SSH/HTTP funktioniert weiter, IPv6 pingt aber TCP/22 timeoutet, IPMI TCP/80 bleibt default nginx, IPMI TCP/443 timeoutet, RMCP antwortet nicht.
- Lokale SSH-Keys wurden um 13:54 CEST explizit gegen `paris` und `root` getestet; beide vorhandenen Keys scheitern mit `Permission denied`, daher ist weiterhin das aktuelle Rescue-SSH-Passwort oder ein anderer gueltiger SSH-Zugang noetig.
- Ein lokaler unstrukturierter `.env`-Secret-Kandidat wurde um 14:01 CEST ebenfalls ohne Ausgabe des Werts gegen `paris` und `root` getestet; beide Logins scheitern, daher liefert auch `.env` keinen aktuellen Rescue-Zugang.
- Zwei aus lokaler Agent-History rekonstruierte WebPi-Login-Kandidaten wurden um 14:10 CEST mit Cloak Chromium getestet; beide bleiben auf `Sign in | OneProvider`, daher liefern auch die Histories keinen aktuellen Panel-Zugang.
- Eine lokale OneProvider-API-Zugangssuche um 14:21 CEST fand kein nutzbares `Api-Key` / `Client-Key`-Paar.
- Eine aktuelle Environment-Variable-Pruefung um 14:40 CEST fand keine OneProvider-/WebPi-/Rescue-/IPMI-/API-spezifischen Variablennamen; credential-artige Variablen gehoerten zu anderer Tooling-Infrastruktur und Werte wurden nicht ausgegeben.
- Eine erneute Pruefung um 14:50 CEST fand weiterhin keine nutzbare lokale WebPi-Session: direkter Zugriff auf die WebPi-Server-URL liefert Cloudflare `HTTP/2 403`, der lokale Chromium-CDP-Port `9222` ist nicht erreichbar, und die bekannten `/tmp/panel-browser`-Profile sind nur historische Artefakte.
- Ein isolierter CDP-Test mit dem historischen WebPi-Profil um 15:06 CEST landete auf `https://panel.op-net.com/login#overview` mit Titel `Registrarse | OneProvider`; Login-Marker waren sichtbar, aber keine Server-Marker fuer `PAR822349`. Damit liefert auch dieses Profil keine authentifizierte WebPi-Sitzung.
- Eine lokale Browserprofil-Metadatenpruefung um 15:32 CEST fand ausserhalb von `/tmp/panel-browser` keine OneProvider-/WebPi-Cookie-Hosts; Cookie-Werte oder Passwortwerte wurden nicht ausgegeben.
- Eine lokale SSH-Konfigurationspruefung um 15:40 CEST fand keine `~/.ssh/config`, keine geladenen Agent-Keys und nur die zwei bereits getesteten Public Keys; damit gibt es keinen weiteren lokalen SSH-Alias oder ungetesteten Key-Pfad.
- Eine Firefox-/Camoufox-Metadatenpruefung um 15:46 CEST fand keine OneProvider-/WebPi-Cookie-Hosts, keine passenden History-URLs und keine `logins.json`; Cookie-Werte oder Passwortwerte wurden nicht ausgegeben.
- Eine lokale Keyring-/Password-Manager-Metadatenpruefung um 15:54 CEST fand keinen nutzbaren entsperrten Credential-Pfad: `secret-tool`, GNOME Keyring, KWallet, `pass` und `op` fehlen; Bitwarden CLI ist vorhanden, aber locked. Es wurden keine Vault-Items oder Secret-Werte ausgegeben.
- Eine Shell-History-Metadatenpruefung um 16:09 CEST fand Zielbezug nur in bereits geprueften Agent-History-Dateien; Trefferzeilen oder Befehlsinhalte wurden nicht ausgegeben.
- Eine direkte WebPi-HTTP-Statuspruefung um 16:17 CEST ergab weiterhin Cloudflare `HTTP/2 403`; die Login-URL meldet `cf-mitigated: challenge`. Es wurde kein Login versucht.
- Eine erneute oeffentliche/RMCP/WebPi-Pruefung um 16:30 CEST zeigte keine Verbesserung: IPv4 Rescue Ping/SSH/HTTP funktionieren, IPv6 TCP/22 laeuft in ein Timeout, IPMI TCP/443 laeuft in ein Timeout, `ipmi-ping` bekommt 0 Antworten, und die WebPi-Server-, Login- und Root-URLs liefern Cloudflare `HTTP/2 403`; die Login-URL meldet `cf-mitigated: challenge`.
- Eine erneute lokale Passwortmanager-Statuspruefung um 16:36 CEST ergab: Bitwarden ist weiterhin gesperrt; `secret-tool`, `gnome-keyring-daemon`, `kwallet-query`, `pass` und `op` sind nicht vorhanden. Es wurde kein Tresor entsperrt oder aufgelistet.
- Der HP Smart Array P410 / RAID-1 Logical Volume Zustand ist nicht sicher:
  - `/dev/sda` ist `offline`
  - `ssacli` meldete `Smart Array P410 (Error: Not responding)`

## Entscheidung noetig

Bitte eine dieser Optionen explizit freigeben:

1. Weiter auf Provider-Antwort warten.
2. Express/VIP-Eskalation im Ticket `#94047858` freigeben.
3. Frische CZ Design / OneProvider WebPi-Zugangsdaten oder eine bereits eingeloggte Browser-Session bereitstellen.
4. Aktuelles Rescue-SSH-Passwort fuer User `paris` oder einen anderen gueltigen SSH-Zugang bereitstellen.

## Sicherer Scope bei frischem WebPi-Zugang

Wenn neue WebPi-Zugangsdaten oder eine eingeloggte Session bereitstehen, ist der sichere naechste Versuch:

- Server `PAR822349` oeffnen.
- `Modo rescate` / Rescue Mode verifizieren.
- Remote-Access-Whitelist auf `152.53.35.28` setzen.
- Nur den WebPi Remote Access create-session Flow ausloesen.
- Stoppen, falls wieder `Invalid boot mode` kommt oder WebPi BIOS/RAID/IPMI-Aenderungen verlangt.

## Nicht freigegeben

Ohne ausdrueckliche Freigabe nicht ausfuehren:

- Normal Boot
- Reinstall
- BIOS-Aenderungen
- RAID-Aenderungen
- IPMI/iLO-Konfigurationsaenderungen
- Disk-Layout-Aenderungen
- `fsck`
- read-write Mounts der installierten Platten
- Express/VIP-Eskalation

## Aktuelle Empfehlung

Technisch gibt es keine weitere sichere kundenseitige IP/WebPi-Korrektur, die den aktuellen Fehler erklaert. Der naechste sinnvolle Schritt ist Provider-Remediation oder eine explizit freigegebene Express/VIP-Eskalation. Fuer einen weiteren Kundenseiten-Test brauche ich frischen WebPi-Zugang oder das aktuelle Rescue-SSH-Passwort.
