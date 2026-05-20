# Power-15 A0 — Canonical Sources for Frida + Multi-Vendor Emulator Snapshots

**Date**: 2026-05-21
**Author**: ralph-researcher (team `power-13-real-world-validation`, A0 prep for A1 Frida-positive snapshot + A3 multi-vendor snapshots)
**Mission**: For every signal we plan to encode in upcoming positive/multi-vendor snapshots, anchor it to a citeable public source URL + concrete literal value. NO fabricated values. Where a source cannot be found, mark explicit GAP.
**Owner mandate**: Anti-Verarschen — never claim verified what we cannot cite.

---

## §A1 — Frida Positive Snapshot Sources

### §A1.1 `/proc/self/maps` library-token strings

| Token | Canonical source path on device | Public source URL | Confidence |
|---|---|---|---|
| `frida-agent-32.so` | `/data/local/tmp/re.frida.server/frida-agent-32.so` | https://github.com/frida/frida/issues/3528 (Frida maintainer confirms path) | HIGH — official issue |
| `frida-agent-64.so` | `/data/local/tmp/re.frida.server/frida-agent-64.so` | https://github.com/frida/frida/issues/3528 | HIGH |
| `frida-helper-32` | `/data/local/tmp/re.frida.server/frida-helper-32` | https://github.com/frida/frida/issues/3528 | HIGH |
| `libfrida-gadget.so` | injected into target process address space (path varies — APK lib dir or /data/local/tmp/) | https://frida.re/docs/gadget/ (official docs) | HIGH |
| `frida-agent` (substring token) | substring of frida-agent-*.so | https://github.com/OWASP/owasp-mastg/issues/1130 (OWASP MASTG discussion) | HIGH |
| `frida-gadget` (substring token) | substring of libfrida-gadget.so | https://github.com/OWASP/owasp-mastg/issues/1130 | HIGH |
| `gum` (substring token) | frida-gum runtime; appears in lib names | https://github.com/frida/frida-gum (project README) | HIGH |
| `linjector` (substring token) | NOT in /proc/self/maps — see §A1.4; DetectFrida uses it in /proc/self/fd readlink targets only | https://github.com/erfur/linjector-rs (project README — Android port of linux_injector) | MEDIUM — token is correct, but PATH is /proc/self/fd not /proc/self/maps |

**Canonical positive-snapshot map-line format** (verified against Frida footprint docs + issue #3528):
```
7f8a4c5000-7f8a4c8000 r-xp 00000000 fd:01 12345  /data/local/tmp/re.frida.server/frida-agent-64.so
7f8a4c8000-7f8a4c9000 r--p 00003000 fd:01 12345  /data/local/tmp/re.frida.server/frida-agent-64.so
7f8a4c9000-7f8a4ca000 rw-p 00004000 fd:01 12345  /data/local/tmp/re.frida.server/frida-agent-64.so
```

### §A1.2 `/proc/self/task/<tid>/comm` thread names

DetectFrida `native-lib.c` source quotes (verified verbatim via WebFetch 2026-05-21):

```c
static const char *FRIDA_THREAD_GUM_JS_LOOP = "gum-js-loop";
static const char *FRIDA_THREAD_GMAIN = "gmain";
static const char *PROC_TASK = "/proc/self/task";
static const char *PROC_STATUS = "/proc/self/task/%s/status";
```

| Thread name | Source library/runtime | Canonical source URL | Confidence |
|---|---|---|---|
| `gum-js-loop` | frida-gum JS scheduler (V8 / QuickJS event loop) | https://github.com/darvincisec/DetectFrida (`app/src/main/c/native-lib.c`); also https://darvincitech.wordpress.com/2019/12/23/detect-frida-for-android/ (author blog); also https://github.com/frida/frida-gum/issues/1000 (Frida maintainers confirm) | HIGH — multiple independent citations |
| `gmain` | glib main-loop thread inside frida-gum | https://github.com/darvincisec/DetectFrida + https://darvincitech.wordpress.com/2019/12/23/detect-frida-for-android/ | HIGH |
| `gdbus` | NOT in DetectFrida; appears in Frida-itself glib internals | GAP — Frida itself does not currently officially document `gdbus` as a stable thread name; blog-mentioned only (https://qweraqq.github.io/security/2024/04/06/android-frida-detection-and-bypass.html) | LOW — blog-only, the Power-14 KDoc disclaimer already marks `gdbus` as "from Frida-itself internals, not DetectFrida-canonical" |

**Source-file evidence (DetectFrida native-lib.c, verbatim)**:
```c
if (my_strstr(buf, FRIDA_THREAD_GUM_JS_LOOP) ||
    my_strstr(buf, FRIDA_THREAD_GMAIN)) {
    // Frida detected
}
```

Source: https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c

**Note on Frida v16+ randomization** (carried from Power-13 doc): newer Frida versions randomize thread names. The `gum-js-loop` + `gmain` exact strings are the canonical v12-v15 fingerprint; v16+ may not match. Document in snapshot KDoc.

### §A1.3 `/proc/net/tcp` ports

| Port | Role | Canonical source | Confidence |
|---|---|---|---|
| `27042` (decimal) / `0x69A2` (hex in /proc/net/tcp encoding) | frida-server primary listener — exact quote from Frida docs: "listening on _localhost:27042_ by default" and `"port": 27042` in default Gadget config | https://frida.re/docs/gadget/ (official Frida documentation) | HIGH — official docs |
| `27043` (decimal) / `0x69A3` | secondary/persistent-mode port commonly cited in detection literature | https://medium.com/@aimardcr/detecting-frida-the-right-way-7cb3227edafb (research blog citing both ports); https://github.com/frida/frida/issues/203 (Frida issue tracker confirms 27042 is the primary, mentions 27043 in detection context) | MEDIUM — primary 27042 is official; 27043 is detection-literature-common but not exclusively documented in official Frida sources as a default |

**Canonical /proc/net/tcp positive line for port 27042** (loopback bind on all-zero):
```
   0: 0100007F:69A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0
```
(`0100007F` = 127.0.0.1 little-endian; `69A2` = 27042 hex; `0A` = TCP_LISTEN state)

### §A1.4 `/proc/self/fd` linjector symlinks

DetectFrida `native-lib.c` source quotes (verified verbatim):

```c
static const char *FRIDA_NAMEDPIPE_LINJECTOR = "linjector";
static const char *PROC_FD = "/proc/self/fd";

// ...
if (NULL != my_strstr(buf, FRIDA_NAMEDPIPE_LINJECTOR)) {
    // Frida detected
}
```

Source: https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c (DetectFrida's native-lib.c, confirmed via WebFetch 2026-05-21)

| Surface | Canonical readlink target convention | Source | Confidence |
|---|---|---|---|
| `/proc/self/fd/<n>` | symlink target contains substring `linjector` when Frida's pipe-based injection is active | https://github.com/darvincisec/DetectFrida (verbatim source) + https://github.com/erfur/linjector-rs (linjector project README: "library injection using /proc/mem, without ptrace") | HIGH (DetectFrida source); MEDIUM for exact pipe filename convention (linjector-rs README does not name the pipe explicitly) |
| Pipe filename literal | GAP — DetectFrida looks for substring `"linjector"` but does not document a specific full pipe-path literal. linjector-rs README does not specify pipe naming. | GAP — needs owner-verify on live device (run frida-server + grep /proc/<pid>/fd readlink output) | LOW for exact literal |

**Snapshot positive-fixture should encode the substring match, not a specific full path**:
- e.g. `procSelfFdSymlinks = mapOf("17" to "pipe:[linjector-1234]")` — this matches DetectFrida's `strstr` check but acknowledges we have no full-literal citation.

---

## §A3 — Multi-Vendor Emulator Snapshot Sources

Goal: One column per vendor with citeable strings + canonical URL. Where public-unverifiable, mark explicit.

### §A3.1 Nox

| Signal | Literal value | Canonical source URL | Confidence |
|---|---|---|---|
| Init file | `fstab.nox` | https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java (`NOX_FILES` array) | HIGH — open-source detector |
| Init file | `init.nox.rc` | https://github.com/framgia/android-emulator-detector (same source) + https://github.com/mofneko/EmulatorDetector | HIGH — two independent detectors |
| Init file | `ueventd.nox.rc` | https://github.com/framgia/android-emulator-detector + https://github.com/mofneko/EmulatorDetector | HIGH |
| `Build.HARDWARE` substring | `nox` | https://ray-chong.medium.com/android-emulator-detection-4d0f994aab5e (blog citation) + https://xdaforums.com/t/nox-emulator-is-getting-detected.4708108/ (Nox-user XDA forum confirms `ro.hardware` carries nox indicator) | MEDIUM — multi-blog convergent |
| `Build.PRODUCT` substring | `nox` | https://ray-chong.medium.com/android-emulator-detection-4d0f994aab5e | MEDIUM — blog-only |
| `Build.BOARD` substring | `nox` | https://ray-chong.medium.com/android-emulator-detection-4d0f994aab5e | MEDIUM — blog-only |
| `ro.product.manufacturer` | `Nox` OR `alps` (Nox masquerades as MediaTek-alps OEM in some builds) | GAP — XDA threads mention "alps" but no canonical Nox firmware decomp publicly available. Treat as blog-only. | LOW — blog-heuristic only |
| `qemu-pipe` path | `/dev/qemu_pipe` (Nox uses QEMU under the hood) | https://github.com/framgia/android-emulator-detector (`QEMU_DRIVERS`) | HIGH (QEMU surface), but Nox-specific binding via this pipe is inferential |
| Package `com.nox.mopen.app` | Nox launcher | https://github.com/mofneko/EmulatorDetector (PACKAGE_NAMES) | HIGH |

**Nox honest framing**: file-paths (init.nox.rc, ueventd.nox.rc, fstab.nox) are HIGH-confidence; build-prop values (manufacturer/board/etc.) are MEDIUM (blog-only). Full firmware-decomp public source: NOT FOUND — mark as gap.

### §A3.2 BlueStacks

| Signal | Literal value | Canonical source URL | Confidence |
|---|---|---|---|
| Package prefix | `com.bluestacks.` | https://github.com/mofneko/EmulatorDetector (`com.bluestacks.` prefix in package detection) | HIGH |
| Package | `com.bluestacks.appmart`, `com.bluestacks.BstCommandProcessor`, `com.bluestacks.help`, `com.bluestacks.home` | Cited in https://github.com/framgia/android-emulator-detector/issues/35 (BlueStacks-detection issue thread) — packages NOT publicly listed in detector source code with exact strings, but referenced in issue tracker | MEDIUM — issue-thread but not in code |
| File | `/data/.bluestacks.prop` | Cited in https://github.com/framgia/android-emulator-detector/issues/35 (community comments) | LOW — blog-comment-only; needs owner-verify on live BlueStacks instance |
| File | `/data/.bstconf.prop` | https://github.com/framgia/android-emulator-detector/issues/35 | LOW — blog-only |
| File | `/boot/bstsetup.env` | https://www.b4x.com/android/forum/threads/recognize-bluestack-emulator.104681/ (B4X forum thread) | LOW — single-forum source |
| `libBstHwHelper.so` library | claimed in user-question but NO public source found | GAP — public-unverifiable, blog/heuristic only | NONE — DO NOT use without owner-verify |
| `ro.product.model = BlueStacks` literal | claimed in some build.prop modification threads; no canonical decomp | GAP — public-unverifiable | NONE |
| `ro.bluestacks.version` property | claimed but no canonical source found | GAP — public-unverifiable, blog/heuristic only | NONE |

**BlueStacks honest framing**: BlueStacks is closed-source (commercial). Only HIGH-confidence signal is the `com.bluestacks.*` package prefix. File-paths and ro.* properties are LOW or GAP. Public firmware decomp NOT available — mark all property-level signals as needs-owner-verify-on-live-instance.

### §A3.3 Genymotion

| Signal | Literal value | Canonical source URL | Confidence |
|---|---|---|---|
| `Build.PRODUCT` / `ro.product.device` | `vbox86p` | https://support.genymotion.com/hc/en-us/articles/115001338045 (official Genymotion support docs reference vbox86p platform); also https://gist.github.com/runo280/e4be3e04c24b463b55ddf012c5cfbdc4 (genymotion-ova-links lists `genymotion_vbox86p_*.ova` builds) | HIGH — official + OVA filename confirms |
| `Build.MANUFACTURER` | `Genymotion` | https://github.com/mofneko/EmulatorDetector (explicit Genymotion-manufacturer string check) | HIGH |
| Package | `com.google.android.launcher.layouts.genymotion` | https://github.com/mofneko/EmulatorDetector | HIGH |
| `/dev/socket/genyd` | Genymotion-specific socket | https://github.com/framgia/android-emulator-detector + https://github.com/strazzere/anti-emulator/blob/master/AntiEmulator/src/diff/strazzere/anti/emulator/FindEmulator.java | HIGH — two independent detectors |
| `/dev/socket/baseband_genyd` | Genymotion baseband socket | https://github.com/framgia/android-emulator-detector + https://github.com/strazzere/anti-emulator | HIGH |
| Init file | `fstab.vbox86`, `init.vbox86.rc`, `ueventd.vbox86.rc` | https://github.com/framgia/android-emulator-detector (X86_FILES array) + https://github.com/mofneko/EmulatorDetector | HIGH |
| ABI suffix | `vbox86` in `ro.product.cpu.abi` paths (vbox86 + vbox86p) | AOSP-vbox86 fork referenced in https://support.genymotion.com/ | MEDIUM — official Genymotion docs + AOSP-fork |
| `/sys/class/dmi/id/product_name = VirtualBox` | claimed in user question; Genymotion runs in VirtualBox under the hood | GAP — DMI is x86-host-detectable but Android API doesn't typically expose sysfs DMI in a portable way; this is a Linux/x86 detection surface, not an Android-API surface | LOW — heuristic only, needs owner-verify |
| `genymotion-vbox86-additions.apk` | claimed in user question | GAP — public-unverifiable as a canonical filename. The Genymotion OVA contains vendor blobs but not under that exact filename in public docs | LOW |

**Genymotion honest framing**: vbox86p product name + Genymotion manufacturer + socket paths are HIGH-confidence (multiple independent open-source detector libraries cite them verbatim). DMI / OVA-internal filenames are LOW or GAP — mark needs-owner-verify-on-live-instance.

### §A3.4 Andy, MEmu, Droid4x, MicroVirt (bonus rows for completeness)

| Vendor | Signal | Literal | Source | Confidence |
|---|---|---|---|---|
| Andy | init file | `fstab.andy`, `ueventd.andy.rc` | https://github.com/framgia/android-emulator-detector + https://github.com/mofneko/EmulatorDetector | HIGH |
| MEmu | Package | `com.microvirt.*` (MEmu was developed by Microvirt) | https://github.com/mofneko/EmulatorDetector (PACKAGE_NAMES) | HIGH |
| Droid4x | Package | `com.kaopu.*` | https://github.com/mofneko/EmulatorDetector | HIGH |
| MicroVirt | Package | `com.vphone.*` | https://github.com/mofneko/EmulatorDetector | HIGH |
| ttVM_x86 (older x86 emulators) | Init file | `init.ttVM_x86.rc`, `ueventd.ttVM_x86.rc`, `fstab.ttVM_x86` | https://github.com/framgia/android-emulator-detector | HIGH |

---

## §B — Cross-Reference Map (Power-13 → Power-15 Sources)

| Power-13 doc reference | Power-15 canonical source upgrade |
|---|---|
| RootBeer `Const.java` (Power-13 cited GitHub source) | NO CHANGE — already verified at Power-14 via AAR decomp (https://repo1.maven.org/maven2/com/scottyab/rootbeer-lib/0.1.1/) |
| DetectFrida thread-name strings (Power-13 generic citation) | UPGRADED — verbatim C constants now quoted from https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c |
| DetectFrida linjector-pipe (Power-13 vague) | UPGRADED — verbatim C constants `FRIDA_NAMEDPIPE_LINJECTOR = "linjector"` + `PROC_FD = "/proc/self/fd"` |
| Frida port 27042 (Power-13 generic) | UPGRADED — official Frida docs quote at https://frida.re/docs/gadget/ |
| Frida port 27043 (Power-13 cited) | DOWNGRADED to MEDIUM — only detection-literature documents 27043 as a "default"; official Frida docs only specify 27042 |
| Nox / BlueStacks / Genymotion (Power-13 mentioned but not snapshotted) | NEW — per-vendor source table above |

---

## §C — Gap Summary (for owner-action follow-up)

Items marked as GAP / LOW / needs-owner-verify in A1+A3 above:

1. **DetectFrida full linjector pipe-path literal** — DetectFrida uses substring `linjector` match only. Need live Frida-server + readlink /proc/<pid>/fd to capture full pipe filename convention. (BLOCKED on live device.)
2. **Frida port 27043 official documentation** — detection literature consensus but not in frida.re official docs. Recommend snapshot uses 27042 as MUST-detect, 27043 as ALSO-COMMONLY-OBSERVED.
3. **BlueStacks `libBstHwHelper.so` library** — UNVERIFIED publicly. Do NOT encode in snapshot without live BlueStacks decomp by owner.
4. **BlueStacks `ro.product.model=BlueStacks` literal** — UNVERIFIED publicly. Use `Build.MANUFACTURER` containing `BlueStacks` (which is community-documented) instead.
5. **Nox `ro.product.manufacturer=alps`** — blog-only, low confidence. Recommend use HIGH-confidence file-path signals (init.nox.rc etc.) as primary detection, ro.* as corroborating.
6. **Genymotion `/sys/class/dmi/id/product_name=VirtualBox`** — Android API exposure not portable. Drop from snapshot OR mark as Linux-x86-host-only sub-evidence row.
7. **Genymotion `genymotion-vbox86-additions.apk`** — exact filename UNVERIFIED. Use `/dev/socket/genyd` + `/dev/socket/baseband_genyd` (HIGH-confidence) as primary Genymotion signals.

---

## §D — Source Inventory (master list)

All public URLs cited above, deduplicated:

### Frida-specific
1. https://github.com/darvincisec/DetectFrida — DetectFrida sample app (the primary "DetectFrida technique" reference)
2. https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c — verbatim native-lib.c source (thread + pipe constants)
3. https://darvincitech.wordpress.com/2019/12/23/detect-frida-for-android/ — DetectFrida author's original blog
4. https://frida.re/docs/gadget/ — official Frida Gadget docs (port 27042 quote)
5. https://github.com/frida/frida/issues/3528 — Frida maintainer confirms `/data/local/tmp/re.frida.server` + frida-agent-{32,64}.so paths
6. https://github.com/frida/frida/issues/203 — Frida port-binding issue confirming 27042 default
7. https://github.com/frida/frida-gum — frida-gum repo (gum-* token origin)
8. https://github.com/frida/frida-gum/issues/1000 — gum-js-loop thread confirmation by Frida maintainers
9. https://github.com/OWASP/owasp-mastg/issues/1130 — OWASP MASTG `/proc/self/maps` Frida-detection discussion
10. https://github.com/erfur/linjector-rs — linjector Android port (named-pipe injection lineage)
11. https://github.com/muellerberndt/frida-detection/blob/master/AntiFrida/app/src/main/cpp/native-lib.cpp — additional Frida-detection sample (LIBFRIDA + AUTH/REJECT auth-dance)
12. https://medium.com/@aimardcr/detecting-frida-the-right-way-7cb3227edafb — research blog citing both 27042 + 27043
13. https://qweraqq.github.io/security/2024/04/06/android-frida-detection-and-bypass.html — research blog (gdbus thread name, LOW confidence)

### Multi-vendor emulator
14. https://github.com/framgia/android-emulator-detector/blob/master/library/src/main/java/com/framgia/android/emulator/EmulatorDetector.java — primary open-source emulator-detector with NOX_FILES, X86_FILES, QEMU_DRIVERS arrays
15. https://github.com/mofneko/EmulatorDetector/blob/master/library/src/main/java/com/nekolaboratory/EmulatorDetector.java — second open-source detector; package-name lists
16. https://github.com/strazzere/anti-emulator/blob/master/AntiEmulator/src/diff/strazzere/anti/emulator/FindEmulator.java — strazzere/anti-emulator (QEMU + Genymotion socket paths)
17. https://github.com/framgia/android-emulator-detector/issues/35 — BlueStacks 5 detection issue thread (BlueStacks paths cited in community comments)
18. https://support.genymotion.com/hc/en-us/articles/115001338045 — official Genymotion docs (vbox86p platform reference)
19. https://gist.github.com/runo280/e4be3e04c24b463b55ddf012c5cfbdc4 — Genymotion OVA build catalog (`genymotion_vbox86p_*.ova` filenames)
20. https://ray-chong.medium.com/android-emulator-detection-4d0f994aab5e — emulator-detection survey blog (Nox hardware/product/board strings — MEDIUM confidence)
21. https://xdaforums.com/t/nox-emulator-is-getting-detected.4708108/ — XDA Nox-user thread (ro.hardware corroboration)
22. https://www.b4x.com/android/forum/threads/recognize-bluestack-emulator.104681/ — B4X forum (BlueStacks `/boot/bstsetup.env`, single-source LOW confidence)
23. https://versprite.com/vs-labs/android-emulator-detection/ — VerSprite emulator-detection writeup (general background)
24. https://www.cin.ufpe.br/~tg/2022-1/tg_CC/tg_lccao.pdf — academic comparative study of Android emulator detection techniques

### Cross-cutting / Anti-Verarschen audit lineage
25. https://github.com/skylot/jadx — jadx decompiler (Power-14 toolchain)
26. https://repo1.maven.org/maven2/com/scottyab/rootbeer-lib/0.1.1/rootbeer-lib-0.1.1.aar — RootBeer 0.1.1 AAR (Power-14 verification artifact)

---

## §E — Aristotle First-Principles Check

**Assumption challenged**: "open-source detector code = canonical source." PARTIALLY TRUE.

**Irreducible truth**:
- HIGH-confidence sources: verbatim C/Java constants in detector source (DetectFrida native-lib.c thread/pipe strings; framgia/mofneko detector file arrays) + official Frida docs (port 27042).
- MEDIUM-confidence sources: research blogs that cite specific strings but don't link to canonical decomp (rayChong, aimardcr).
- LOW / GAP: closed-source vendor properties (BlueStacks ro.* values, Nox firmware internals) — cannot be verified without live-instance access and decomp.

**Aristotelian move for A1+A3 snapshots**:
- Encode HIGH-confidence signals as PRIMARY detection (with full source citation in snapshot KDoc).
- Encode MEDIUM-confidence signals as CORROBORATING (with KDoc note "blog-cited, multi-source convergent").
- Do NOT encode LOW / GAP signals — defer to owner-action live-device capture.

**Honest framing for downstream A1/A3 implementation**:
- The Frida positive snapshot CAN be built end-to-end from HIGH-confidence sources (all four signal classes have verbatim C/Java/docs citations).
- The multi-vendor snapshots CAN be built for Genymotion (HIGH) and Nox file-paths (HIGH).
- BlueStacks snapshot REQUIRES owner-action — only the `com.bluestacks.*` package prefix is HIGH-confidence; everything else is LOW or GAP.

---

## §F — Ready-for-A1 Determination

Required for A1 (Frida positive snapshot):
- [x] `/proc/self/maps` library paths — HIGH (issue #3528 + Frida docs)
- [x] Thread names (`gum-js-loop`, `gmain`) — HIGH (DetectFrida verbatim source)
- [x] Port 27042 — HIGH (official Frida docs)
- [/] Port 27043 — MEDIUM (detection literature, downgrade in KDoc)
- [x] linjector substring in /proc/self/fd — HIGH (DetectFrida verbatim source)
- [/] linjector full pipe-path literal — GAP (substring-only encoding acceptable, KDoc-document the limit)

**A1 READY**: yes — with explicit KDoc noting MEDIUM/GAP items.

Required for A3 (multi-vendor snapshots):
- [x] Genymotion: 5 HIGH-confidence signals (vbox86p, Genymotion manufacturer, /dev/socket/genyd, /dev/socket/baseband_genyd, launcher package) — READY
- [x] Nox: 3 HIGH-confidence signals (init.nox.rc, ueventd.nox.rc, fstab.nox + package com.nox.mopen.app) — READY for FS-level snapshot; build-prop signals stay MEDIUM/blog-only
- [/] BlueStacks: only 1 HIGH-confidence signal (`com.bluestacks.*` package prefix); file/property signals are LOW/GAP — PARTIAL-READY, requires owner-verify before full snapshot

**A3 READY**: partial — Genymotion + Nox HIGH-confidence snapshots can proceed; BlueStacks snapshot needs owner-action OR explicit "public-unverifiable" KDoc disclaimer matching Power-14 §1ter convention.

---

**Status**: A0 canonical-sources compilation complete; ready to hand off to A1 (Frida-positive snapshot) and A3 (multi-vendor snapshots) implementation tasks.

**Tag candidate**: `power-15-A0-canonical-sources-2026-05-21`
