# Threat Model — Container Detection

STRIDE-orientiertes Mapping der 60 Detection-Punkte auf Android-Layer.

## Nutzungsgraenze

Dieses Threat Model ist fuer defensive Messung und reproduzierbare Lab-Tests
gedacht. Es darf nicht als Anleitung verwendet werden, um Erkennung,
Attestation, Account-Integritaet oder Anti-Abuse-Systeme fremder Plattformen zu
umgehen. Vorschlaege zu Kernel-, Root-, Sensor-, Netzwerk- oder TLS-Ebene
werden nur als Detection-Signal, Risikoannahme oder Lab-Kontrolle aufgenommen.

## Akteure

| Akteur | Rolle |
|---|---|
| **Detector** (App im Container) | Misst Geraete-Eigenschaften, sendet Telemetrie |
| **Container** (ReDroid) | Virtualisierte Android-Umgebung |
| **Host** (Linux + Docker) | ARM64-Server |
| **Spoof-Modules** (Magisk/LSPosed) | Manipulieren Probe-Antworten |
| **Attestation-Authority** (Google Play Services / TEE) | Externe Vertrauenswurzel |

## Layer-Mapping

```
+-------------------------------------------------------+
| Application Layer                                     |
| - Play Integrity API (#2)                             |
| - WebGL/Canvas Fingerprint (#55, #56)                 |
| - Audio Fingerprint (#54)                             |
+-------------------------------------------------------+
| Framework Layer (Java/Kotlin SDK)                     |
| - Settings.Secure.ANDROID_ID (#11)                    |
| - TelephonyManager (IMEI, Carrier) (#12, #21, #22)    |
| - AdvertisingIdClient GAID (#16)                      |
| - SensorManager (#24, #42-45)                         |
| - LocationManager Mock (#39, #41)                     |
+-------------------------------------------------------+
| Native Layer (NDK / libc)                             |
| - getprop / __system_property_get (#1, #7, #9, #28)   |
| - /system/build.prop                                  |
| - File-Existence Checks (Su, Magisk-Sockets) (#3)     |
+-------------------------------------------------------+
| Kernel Layer                                          |
| - /proc/version (#30)                                 |
| - /proc/cpuinfo (#27)                                 |
| - /proc/self/maps (Hooks, Frida) (#8)                 |
| - SELinux Status (#14)                                |
+-------------------------------------------------------+
| Hardware Layer                                        |
| - TEE / StrongBox Keystore Attestation (#6)           |
| - MediaDRM Unique ID (#29)                            |
| - Sensor-Hardware-Signaturen (FFT) (#24)              |
| - GPU Renderer (#26, #56)                             |
+-------------------------------------------------------+
| Network Layer                                         |
| - Source-IP / ASN / Geo (#5)                          |
| - VPN-Interface (#18)                                 |
| - DNS-Provider (#37)                                  |
| - Cellular vs WiFi (#25)                              |
+-------------------------------------------------------+
```

## STRIDE pro Vektor

| Vektor | S | T | R | I | D | E | Mitigierbar durch Layer |
|---|:-:|:-:|:-:|:-:|:-:|:-:|---|
| Build Fingerprint | x | x | | | | | L1 |
| Play Integrity | x | x | | | | x | L3 |
| Hardware Attestation | x | x | x | | | x | L3 (mit Keybox) |
| Sensor FFT | x | | | | | | L5 (mit Trace) |
| IP/ASN | x | | | x | | | L6 |
| /proc/version | | x | | x | | | (Custom Kernel) |

S = Spoofing, T = Tampering, R = Repudiation, I = Information Disclosure, D = DoS, E = Elevation

## Vertrauensanker (Trust Roots)

| Root | Spoofbar? | Wie? |
|---|---|---|
| Google Play Services | nein direkt, nur Antwort manipulierbar | PIF haengt sich vor API |
| TEE (StrongBox) | nein, hardware-gebunden | TrickyStore tauscht Cert-Chain |
| Mobile Carrier IMSI | nein im Container | Mobile-Egress-Proxy als Workaround |
| Google Server-Side Behavior | nein | nicht adressierbar |

## Erkenntnisse fuer das Paper

Die robustesten Probes sind die mit **externer Vertrauenswurzel** (Google Play Services, TEE, Mobile-Carrier). Die schwaechsten sind App-interne Selbst-Checks (file existence, getprop). Layer-Tiefe der Erkennung ist proportional zur Spoof-Schwierigkeit.
