// agents/detection/src/test/kotlin/com/detectorlab/core/replay/FridaInjectedRedroidSnapshot.kt
//
// Power-15 Phase-A track A1 — Frida-injected Redroid snapshot for
// positive-path detection of FridaMemoryMapsProbe (rank 9.0). This fixture
// models the canonical state of a Redroid 12 container that has had
// frida-server pushed to /data/local/tmp/re.frida.server/ and started
// (frida-agent loaded into the calling process via Frida's standard
// injection flow). All non-Frida base values are inherited verbatim from
// `RedroidV12Snapshot` because the injected box IS Redroid + Frida.

package com.detectorlab.core.replay

/**
 * Frida-injected Redroid 12 snapshot for P15 A1 positive-path detection.
 *
 * **Base snapshot**: identical to `RedroidV12Snapshot` (the injected box
 * starts as the same Redroid 12 container captured 2026-05-20 from
 * PAR822349). Only three fields differ from the un-injected baseline —
 * the three surfaces FridaMemoryMapsProbe consults:
 *
 *   * [`procSelfMapsLibs`][DeviceSnapshot.procSelfMapsLibs] — frida-agent
 *     + frida-gadget + frida-helper library tokens.
 *   * [`runtimeThreadNames`][DeviceSnapshot.runtimeThreadNames] — the
 *     gum-js-loop + gmain canonical Frida threads.
 *   * [`openTcpPorts`][DeviceSnapshot.openTcpPorts] — Frida's listener
 *     port(s).
 *
 * ## SOURCE CITATION (per Power-15 A0 canonical-sources doc, commit b811e27)
 *
 * Every encoded token below has an inline `// cite:` comment naming the
 * A0 source URL and confidence level. Summary of confidence framing:
 *
 *   * `procSelfMapsLibs`: **HIGH-confidence** — paths verified by Frida
 *     maintainer in
 *     [issue #3528](https://github.com/frida/frida/issues/3528) plus
 *     official Gadget docs at <https://frida.re/docs/gadget/>.
 *   * `runtimeThreadNames` (`gum-js-loop`, `gmain`): **HIGH-confidence**
 *     — verbatim C constants in DetectFrida's `native-lib.c`
 *     (<https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c>).
 *   * `openTcpPorts[27042]`: **HIGH-confidence** — official Frida Gadget
 *     docs explicitly quote "listening on localhost:27042 by default".
 *   * `openTcpPorts[27043]`: **MEDIUM-confidence** — appears in
 *     detection-literature (Medium / blog) but NOT in the official Frida
 *     documentation as a default. Encoded as a "commonly observed
 *     secondary listener" not a "must-detect" canonical port.
 *
 * ## GAP DISCLAIMERS (per A0 §C)
 *
 *   * **linjector full pipe-path literal**: GAP per A0 §A1.4.
 *     DetectFrida's `native-lib.c` uses `strstr(buf, "linjector")` (a
 *     substring match against `/proc/self/fd` readlink targets); the
 *     specific full pipe filename convention is NOT documented in any
 *     public Frida or linjector-rs source. This snapshot does NOT
 *     encode a linjector entry in `dirEntries` — the substring-only
 *     match surface is deferred to a runtime fixture extension where
 *     the literal can be captured on a live device. The presence of
 *     `linjector` is therefore NOT asserted by this fixture; only the
 *     three HIGH-confidence Frida surfaces above are.
 *
 *   * **Frida v16+ thread-name randomization**: A0 §A1.2 documents that
 *     newer Frida versions randomize gum-* thread names. The
 *     `gum-js-loop` + `gmain` literals encoded below are the canonical
 *     v12-v15 fingerprint — the variant DetectFrida's source matches.
 *     This snapshot models a Frida v12-v15 injection; a v16+ injection
 *     would not match the literals and would require a separate
 *     fixture or a regex-mode probe.
 *
 *   * **`gdbus` thread name**: A0 §A1.2 marks `gdbus` as LOW-confidence
 *     (blog-cited only, not in DetectFrida's verbatim source). NOT
 *     encoded in this fixture — only the HIGH-confidence DetectFrida
 *     constants are used.
 *
 * ## Anti-Verarschen audit trail
 *
 * Every literal in the three Frida-specific fields below is traceable to
 * a public source URL committed in `audit/spoof-stack/power-15-canonical-sources.md`
 * (commit b811e27). No fabricated values; no speculative encodings.
 */
object FridaInjectedRedroidSnapshot {

    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        // Label format per Pre-Audit §7 convention: `<device-tag>-<arch>-<YYYY-MM-DD>-<variant>`.
        label = "redroid-12-amd64-2026-05-21-frida-injected",
        capturedAt = "2026-05-21T00:00:00Z",
        sdkInt = 31, // Android 12 → API 31 (Redroid base, unchanged by Frida injection)

        // === Base values inherited verbatim from RedroidV12Snapshot ===
        //
        // The Frida injection modifies ONLY runtime memory (mapped libs,
        // spawned threads, bound listening sockets). Property keys,
        // installed packages, mount tables, init services, dir-entries,
        // and every other declarative surface are UNCHANGED — the
        // container's filesystem and prop database do not move when
        // frida-server is pushed to /data/local/tmp/ and started.
        // Values below are bit-identical to RedroidV12Snapshot.SNAPSHOT
        // (see agents/detection/src/core/replay/RedroidV12Snapshot.kt
        // for the per-field rationale).
        systemProperties = mapOf(
            "ro.build.fingerprint" to
                "redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/" +
                "eng.frank.20240527.145941:userdebug/test-keys",
            "ro.vendor.build.fingerprint" to
                "redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/" +
                "eng.frank.20240527.145941:userdebug/test-keys",
            "ro.build.display.id" to
                "redroid_x86_64_only-userdebug 12 SP1A.210812.016.C2 " +
                "eng.frank.20240527.145941 test-keys",
            "ro.build.tags" to "test-keys",
            "ro.build.type" to "userdebug",
            "ro.build.version.release" to "12",
            "ro.build.version.sdk" to "31",
            "ro.product.brand" to "redroid",
            "ro.product.model" to "redroid12_x86_64_only",
            "ro.product.manufacturer" to "redroid",
            "ro.product.device" to "redroid12_x86_64_only",
            "ro.product.name" to "redroid12_x86_64_only",
            "ro.kernel.qemu" to "",
            "ro.kernel.qemu.gles" to "",
            "ro.hardware" to "redroid",
            "ro.product.board" to "",
            "ro.board.platform" to "",
            "ro.product.cpu.abi" to "x86_64",
            "ro.product.cpu.abilist" to "x86_64,arm64-v8a",
            "ro.product.cpu.abilist32" to "",
            "ro.product.cpu.abilist64" to "x86_64,arm64-v8a",
            "ro.boot.vbmeta.device_state" to "",
            "ro.boot.verifiedbootstate" to "",
            "ro.boot.flash.locked" to "",
            "ro.secure" to "0",
            "ro.debuggable" to "1",
            "ro.boot.selinux" to "",
            "ro.build.selinux" to "",
            "ro.boot.veritymode" to "",
            "ro.boot.warranty_bit" to "",
            "ro.boot.warranty" to "",
            "ro.bootmode" to "",
            "ro.hardware.keystore" to "",
            "ro.hardware.touchscreen" to "",
            "ro.gpu.driver" to "",
            "ro.hardware.gralloc" to "",
            "ro.hardware.vulkan" to "",
            "ro.opengles.version" to "",
            "ro.boot.qemu.gltransport" to "",
            "ro.hardware.audio" to "snd_card_dummy",
        ),
        existingFiles = setOf(
            "/system/bin/su",
        ),
        readableFiles = mapOf(
            "/proc/version" to
                "Linux version 4.15.0-213-generic (buildd@lcy02-amd64-079) " +
                "(gcc version 7.5.0 (Ubuntu 7.5.0-3ubuntu1~18.04)) " +
                "#224-Ubuntu SMP Mon Jun 19 13:30:12 UTC 2023",
        ),
        telephony = mapOf(
            "OPERATOR_NAME" to "Android",
            "MCC_MNC" to "310260",
            "LINE1_NUMBER" to "15555215554",
            "SUBSCRIBER_ID" to "310260000000000",
        ),
        installedPackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
        ),
        sensorTypes = emptySet(),
        bluetoothMac = null,
        gpsLat = null,
        gpsLng = null,
        gpsAccuracy = null,
        gpsProvider = null,
        gpsIsMock = null,

        // === Frida-specific positive signals (the three overrides) ===

        /**
         * Library tokens observable in `/proc/self/maps` when frida-server
         * has injected `frida-agent` into the calling process. Each entry
         * corresponds to a canonical mmap'd shared object the Frida
         * runtime brings in.
         */
        procSelfMapsLibs = setOf(
            // cite: https://github.com/frida/frida/issues/3528 — HIGH
            // Frida maintainer confirms /data/local/tmp/re.frida.server/
            // as the canonical install path; frida-agent-64.so is the
            // 64-bit agent companion that frida-server loads into the
            // target process via ptrace.
            "/data/local/tmp/re.frida.server/frida-agent-64.so",
            // cite: https://github.com/frida/frida/issues/3528 — HIGH
            // 32-bit agent variant. Both bitness variants are commonly
            // present on dual-arch Redroid (x86_64 + arm64-v8a via
            // Houdini); injectors pick whichever matches the target.
            "/data/local/tmp/re.frida.server/frida-agent-32.so",
            // cite: https://github.com/frida/frida/issues/3528 — HIGH
            // frida-helper-{32,64} are the privilege-bridge binaries
            // frida-server invokes when crossing user/SELinux contexts.
            // Verbatim path from the Frida maintainer's issue comment.
            "/data/local/tmp/re.frida.server/frida-helper-32",
            "/data/local/tmp/re.frida.server/frida-helper-64",
            // cite: https://frida.re/docs/gadget/ — HIGH
            // Frida Gadget shared object. Embedded-mode injection (apk
            // patch, gadget linkage) maps this name into /proc/self/maps.
            // The canonical filename per the official Gadget docs is
            // `libfrida-gadget.so`; deploy location varies (APK lib dir
            // or /data/local/tmp/) but the basename is dispositive.
            "/data/local/tmp/libfrida-gadget.so",
            // cite: https://github.com/OWASP/owasp-mastg/issues/1130 — HIGH
            // OWASP MASTG discussion of /proc/self/maps Frida-detection
            // explicitly enumerates the `frida-agent` substring token as
            // the load-bearing match (FridaMemoryMapsProbe does
            // case-insensitive substring scanning per its KDoc).
            "frida-agent",
            // cite: https://github.com/OWASP/owasp-mastg/issues/1130 — HIGH
            // Companion `frida-gadget` substring token from the same
            // OWASP MASTG discussion. Catches the gadget basename even
            // when the full path varies across embed locations.
            "frida-gadget",
            // cite: https://github.com/frida/frida-gum — HIGH
            // The `gum` token is the frida-gum runtime library family
            // (libgum.so, libgumjs.so etc.); appears in library names
            // mapped by frida-gum-based agents/gadgets.
            "libgum",
        ),

        /**
         * Thread names observable in `/proc/self/task/<tid>/comm` once
         * frida-agent is loaded. Both names are verbatim string constants
         * from DetectFrida's `native-lib.c` (Frida v12-v15 fingerprint).
         */
        runtimeThreadNames = setOf(
            // Baseline AOSP/Linux thread names that exist on any Android
            // process — preserved so the snapshot reflects realistic
            // /proc/self/task content, not a synthetic Frida-only stub.
            "main",
            "Binder:1",
            "Binder:2",
            "FinalizerDaemon",
            "ReferenceQueueDaemon",
            // cite: https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c — HIGH
            // Verbatim DetectFrida constant:
            //   static const char *FRIDA_THREAD_GUM_JS_LOOP = "gum-js-loop";
            // The frida-gum JS scheduler (V8 / QuickJS event loop) spawns
            // this thread the moment the agent is loaded. Also cited by
            // Frida maintainers in https://github.com/frida/frida-gum/issues/1000
            // and DetectFrida author blog
            // https://darvincitech.wordpress.com/2019/12/23/detect-frida-for-android/
            "gum-js-loop",
            // cite: https://github.com/darvincisec/DetectFrida/blob/master/app/src/main/c/native-lib.c — HIGH
            // Verbatim DetectFrida constant:
            //   static const char *FRIDA_THREAD_GMAIN = "gmain";
            // glib main-loop thread inside frida-gum's runtime support.
            // Same A0 §A1.2 citation cluster as gum-js-loop.
            "gmain",
        ),

        /**
         * TCP listener ports observable in `/proc/net/tcp` when
         * frida-server is running.
         */
        openTcpPorts = setOf(
            // cite: https://frida.re/docs/gadget/ — HIGH
            // Quoted verbatim from the official Frida Gadget docs:
            // "listening on localhost:27042 by default" plus the default
            // Gadget config block `"port": 27042`. This is THE canonical
            // frida-server primary listener port and the only one the
            // official documentation guarantees.
            27042,
            // cite: https://medium.com/@aimardcr/detecting-frida-the-right-way-7cb3227edafb — MEDIUM
            // cite: https://github.com/frida/frida/issues/203 — MEDIUM
            // Detection-literature consensus: 27043 is commonly observed
            // as a secondary/persistent-mode listener. NOT documented in
            // the official Frida docs as a default. A0 §A1.3 explicitly
            // downgrades this from HIGH (in Power-13) to MEDIUM here —
            // encoded as a corroborating signal, not a must-detect
            // canonical port. See KDoc disclaimer above.
            27043,
        ),

        // === Remaining base fields inherited from RedroidV12Snapshot ===
        //
        // The probes below this point consume declarative surfaces that
        // Frida injection does not perturb (Magisk mount layout, init
        // svc props, UDS sockets, dirEntries, mountInfo). Values are
        // bit-identical to RedroidV12Snapshot.SNAPSHOT.
        prologueHashDeltas = emptyMap(),
        trampolinePatternCount = 0,
        gotPltAnomalies = emptyMap(),
        rwxpMemorySegments = emptyList(),
        dirEntries = mapOf(
            "/data/adb/modules" to listOf(
                "zygisksu",
                "safetynet-fix",
                "MagiskHidePropsConf",
            ),
            // NOTE: /proc/self/fd is INTENTIONALLY NOT populated with a
            // `linjector` entry. Per A0 §A1.4 the full pipe-path literal
            // that DetectFrida's strstr match would catch is a GAP — the
            // substring-only convention is not documented in any public
            // source. Encoding a fabricated path here would violate the
            // anti-verarschen mandate. The three Frida fields above
            // already cover the HIGH-confidence DetectFrida-canonical
            // surfaces; the linjector signal is deferred to a runtime
            // fixture extension where a live frida-server can be observed.
        ),
        initSvcProps = mapOf(
            "zygote" to "running",
            "zygote_secondary" to "running",
            "servicemanager" to "running",
            "surfaceflinger" to "running",
            "mediaserver" to "running",
            "audioserver" to "running",
            "installd" to "running",
            "vold" to "running",
            "netd" to "running",
            "logd" to "running",
            "lmkd" to "running",
            "ueventd" to "running",
            "healthd" to "running",
            "hwservicemanager" to "running",
            "adbd" to "running",
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
        ),
        procNetUnixSockets = listOf(
            "@android:installd",
            "@android:zygote",
            "@android:netd",
            "@MAGISK",
            "/sbin/.magisk/magiskd",
            "/dev/socket/installd",
            "/dev/socket/zygote",
        ),
        mountInfo = mapOf(
            "self" to """
                1 0 0:1 / / ro - overlay overlay rw,lowerdir=/lower,upperdir=/upper
                2 1 0:2 / /system ro - overlay overlay rw,lowerdir=/system_lower
                3 1 0:3 / /vendor ro - overlay overlay rw
                4 1 0:4 / /data rw - ext4 /dev/block/vda
                5 1 0:5 / /sys ro - sysfs sysfs
                6 1 0:6 / /proc ro - proc proc
                7 1 0:7 / /dev rw - tmpfs tmpfs
                8 4 0:8 / /storage/emulated rw - fuse fuse
            """.trimIndent(),
            "1" to """
                1 0 0:1 / / ro - overlay overlay rw,lowerdir=/lower,upperdir=/upper
                2 1 0:2 / /system ro - overlay overlay rw,lowerdir=/system_lower
                3 1 0:3 / /vendor ro - overlay overlay rw
                4 1 0:4 / /data rw - ext4 /dev/block/vda
                5 1 0:5 / /sys ro - sysfs sysfs
                6 1 0:6 / /proc ro - proc proc
                7 1 0:7 / /dev rw - tmpfs tmpfs
                8 4 0:8 / /storage/emulated rw - fuse fuse
                9 1 0:9 / /sbin/.magisk rw - tmpfs magisk_tmp
                10 9 0:10 / /sbin/.magisk/mirror rw - tmpfs magisk_tmp
                11 4 0:11 / /data/adb/modules rw - ext4 /dev/block/vda
                12 9 0:12 / /sbin/magisk rw - bind /sbin/.magisk/busybox
            """.trimIndent(),
        ),
    )
}
