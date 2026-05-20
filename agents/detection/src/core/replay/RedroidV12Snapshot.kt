// agents/detection/src/core/replay/RedroidV12Snapshot.kt
//
// Real ReDroid 12 device snapshot captured 2026-05-20 from PAR822349.
// Source: `audit/E2E-validation-2026-05-20.md` — values pulled directly
// via `docker exec redroid-test getprop <key>` and `docker exec redroid-test
// ls <path>` against the live container running
//   redroid/redroid@sha256:e6f799d56b9a9a2bbc6224b5b7a6dc744c9b4d878ac856f27f0c4ec793ef55d3
// on Ubuntu 18.04 kernel 4.15.0-213-generic.
//
// This snapshot is the ground truth that the probe inventory was validated
// against in Power-3 / Power-4. It is reused here to drive the
// `SnapshotReplayE2ETest` integration test, giving the detection module
// end-to-end execution against real container telemetry without requiring
// a live device at test time.

package com.detectorlab.core.replay

object RedroidV12Snapshot {

    /**
     * Frozen ReDroid 12 container capture. Values are exact strings from
     * the `audit/E2E-validation-2026-05-20.md` capture run. Property keys
     * that the audit ran but found empty are encoded as `""` (not omitted)
     * so probes that distinguish "set-but-empty" from "not-set" see the
     * same answer the live container gave.
     */
    val SNAPSHOT: DeviceSnapshot = DeviceSnapshot(
        label = "redroid-12-amd64-2026-05-20",
        capturedAt = "2026-05-20T00:00:00Z",
        sdkInt = 31, // Android 12 → API 31
        systemProperties = mapOf(
            // rank 1 buildprop.fingerprint
            "ro.build.fingerprint" to
                "redroid/redroid_x86_64_only/redroid_x86_64_only:12/SP1A.210812.016.C2/" +
                "eng.frank.20240527.145941:userdebug/test-keys",
            // Power-13 Gap #9 — internally-consistent vendor
            // fingerprint. Un-spoofed ReDroid container has
            // matching system + vendor fingerprints (both
            // `redroid/...`) because the container is built as
            // a single Docker image. Rank-9.5 fires
            // PATTERN_PARTITIONS_CONSISTENT at 0.0 (consistent —
            // both partitions agree). The probe is testing for
            // SPOOFED DIVERGENCE, not internally-consistent
            // emulators; un-spoofed Redroid is NOT a positive
            // for this probe (other ranks catch the
            // `redroid/...` identifier directly).
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

            // rank 9 model_brand_manufacturer
            "ro.product.brand" to "redroid",
            "ro.product.model" to "redroid12_x86_64_only",
            "ro.product.manufacturer" to "redroid",
            "ro.product.device" to "redroid12_x86_64_only",
            "ro.product.name" to "redroid12_x86_64_only",

            // rank 4 qemu_artifacts — empty (ReDroid does NOT set qemu props
            // because it is not running under QEMU — the leak is via
            // ro.hardware=redroid instead). Empty-string is the captured
            // ground-truth.
            "ro.kernel.qemu" to "",
            "ro.kernel.qemu.gles" to "",

            // rank 28 board_hardware — ro.hardware is the load-bearing
            // emulator marker; board/platform are empty in the capture.
            "ro.hardware" to "redroid",
            "ro.product.board" to "",
            "ro.board.platform" to "",

            // rank 27 cpu_abi — DUAL-ARCH (x86_64 + arm64-v8a Houdini bridge)
            "ro.product.cpu.abi" to "x86_64",
            "ro.product.cpu.abilist" to "x86_64,arm64-v8a",
            "ro.product.cpu.abilist32" to "",
            "ro.product.cpu.abilist64" to "x86_64,arm64-v8a",

            // rank 13 bootloader
            "ro.boot.vbmeta.device_state" to "",
            "ro.boot.verifiedbootstate" to "",
            "ro.boot.flash.locked" to "",
            // Power-13 fixture upgrade — un-spoofed ReDroid containers ship
            // with the AOSP-default debuggable bootimage signature: BOTH
            // `ro.debuggable=1` AND `ro.secure=0`. The May-2026 docker-exec
            // capture recorded `ro.secure=1` which is the post-Magisk-PIF
            // state and not the fully-dirty pre-spoof state. Reset to the
            // canonical dirty pair so the rank-7 RootBeer dangerous-props
            // rule (Power-13 Gap #5) exercises BOTH axes on the standard
            // RedroidV12 fixture instead of just the debuggable axis.
            "ro.secure" to "0", // VIOLATION — production must be 1
            "ro.debuggable" to "1", // VIOLATION — production must be 0

            // rank 14 selinux
            "ro.boot.selinux" to "",
            "ro.build.selinux" to "",

            // rank 6 keystore_attestation — ReDroid containers have NO
            // hardware keystore HAL. Both `ro.hardware.keystore` and the
            // Knox/verity property surface are EMPTY on the captured
            // container — the kernel-virtual `/proc/sys/kernel/<keystore>`
            // namespace simply doesn't exist when the host kernel is a
            // generic Ubuntu kernel rather than an Android GKI kernel
            // with the keystore HAL pre-bound. Encoded as empty-string
            // (not omitted) so the rank-6 declarative probe sees the
            // captured "set-but-empty" answer (which lands in the
            // `hardwareKeystoreAbsent` 0.70 tier).
            "ro.boot.veritymode" to "",
            "ro.boot.warranty_bit" to "",
            "ro.boot.warranty" to "",
            "ro.bootmode" to "",
            "ro.hardware.keystore" to "",

            // rank 57 touch_pressure — ReDroid containers have NO real
            // touchscreen HAL bound. `ro.hardware.touchscreen` is EMPTY
            // on the captured container (no IC name, no synthetic stub).
            // Encoded as empty-string (not omitted) so the rank-57
            // declarative probe sees the captured "set-but-empty" answer.
            // Combined with the absence of any `/dev/input/event*` files
            // below this lands the probe in PATTERN_NO_TOUCH_HAL (0.95).
            "ro.hardware.touchscreen" to "",

            // rank 56 webgl_fingerprint (DECLARATIVE VARIANT) — containerized
            // ReDroid has NO GPU HAL bound. The host's Ubuntu 18.04 kernel
            // ships no Android vendor GPU driver and the ReDroid build does
            // NOT inject a software-renderer (no SwiftShader binding) —
            // instead AOSP's libGLES_swiftshader.so is absent and the
            // gpu.driver property is simply left empty. Captured ground
            // truth from `docker exec redroid-test getprop ro.gpu.driver`
            // is empty stdout (set-but-empty, not "key not found"), so we
            // encode each property as `""` (preserving the rank-4 /
            // rank-14 / rank-57 set-but-empty convention).
            //
            // Per the rank-56 WebGlFingerprintProbe signal cascade, the
            // gpu_driver+vulkan both-empty pair is the load-bearing
            // signal — it lands the probe in PATTERN_NO_GPU_HAL (0.70)
            // with confidence 0.75 declarative. The opengles.version is
            // also empty for the same reason (no GLES driver to report a
            // version), but the no_gpu_hal rule fires FIRST in the
            // cascade so the old_gles rule (0.50) is preempted. The
            // gltransport / kernel.qemu.gles / hardware.gralloc props
            // are all empty too — ReDroid is not running under QEMU and
            // does not synthesize an AVD GL stack, so the emulator_gl
            // rule (0.85) does not fire and no_gpu_hal stays the winning
            // pattern.
            "ro.gpu.driver" to "",
            "ro.hardware.gralloc" to "",
            "ro.hardware.vulkan" to "",
            "ro.opengles.version" to "",
            "ro.boot.qemu.gltransport" to "",

            // rank 54 audio_fingerprint (DECLARATIVE VARIANT) — ReDroid
            // containerized Android has NO real audio HAL. The ReDroid
            // build script binds the canonical Linux ALSA dummy driver
            // (`snd_card_dummy`) as the audio HAL so the Android
            // framework's AudioFlinger has a HAL name to consult on
            // boot — without it, AudioFlinger crashes the system server.
            // The `snd_card_dummy` value is the load-bearing signature
            // that the declarative probe scores against: real Android
            // devices on real silicon report a vendor codec name
            // (`trondheim_audio` on Pixel 7, `exynos_audio_2200` on
            // Galaxy S22, `qcom_aud_codec_*` on Qualcomm devices). The
            // dummy HAL signals "container with software-only audio"
            // which strongly implies the WebView AudioContext FFT
            // fingerprint would be the well-known software-DSP variant
            // (the actual un-snapshottable rank-54 surface — see the
            // probe KDoc's "declarative limitation" section).
            //
            // Combined with the absence of `/dev/snd/controlC0` below
            // (the ALSA control device for the dummy HAL is not bound
            // in the container's /dev namespace), the declarative
            // probe lands in PATTERN_SOFTWARE_HAL (0.85) — the
            // software-HAL signature wins over the no-device-no-HAL
            // rule per the cascade in source order, which is the
            // correct stronger signal for the snd_card_dummy case.
            "ro.hardware.audio" to "snd_card_dummy",
        ),
        existingFiles = setOf(
            // rank 3 su_detection — ReDroid ships /system/bin/su
            "/system/bin/su",
            // rank 6 keystore_attestation — `/dev/keymaster` is NOT present
            // in the captured ReDroid /dev tree. The container shares the
            // host's /dev namespace (a generic Ubuntu /dev with no
            // android-vendor keymaster node bound), so the probe's
            // `keymasterMissing` predicate fires (0.50 tier). Declared by
            // omission below — the set explicitly does NOT include
            // "/dev/keymaster", which is the captured ground-truth and
            // mirrors what `docker exec redroid-test ls /dev/keymaster`
            // returned in the audit run.

            // rank 57 touch_pressure — containerized ReDroid has NO
            // /dev/input/event* nodes (no evdev backend bound to the
            // generic Ubuntu host kernel). Declared by omission — the
            // set explicitly does NOT include any /dev/input/event[0-4]
            // path. Combined with the empty ro.hardware.touchscreen prop
            // above this lands rank-57 in PATTERN_NO_TOUCH_HAL (0.95) —
            // the dispositive "container without input HAL" signal.

            // rank 54 audio_fingerprint (DECLARATIVE VARIANT) —
            // containerized ReDroid has NO `/dev/snd/*` device nodes
            // (the host Ubuntu kernel's ALSA tree is not bound into
            // the container's /dev namespace; even if the user namespace
            // had it visible, the ReDroid build does not request the
            // sound-card capability in its docker run flags). Declared
            // by omission — the set explicitly does NOT include
            // "/dev/snd/controlC0", mirroring `docker exec redroid-test
            // ls /dev/snd/` which returns "No such file or directory"
            // on the captured run. The absence is one of two surfaces
            // the rank-54 probe consults; combined with the
            // `ro.hardware.audio=snd_card_dummy` prop above, the
            // declarative probe lands in PATTERN_SOFTWARE_HAL (0.85)
            // — the dummy-HAL signature wins over the no-device-no-HAL
            // rule per the cascade in source order.
        ),
        readableFiles = mapOf(
            // rank 30 proc_version — leaks host (Ubuntu 18.04 launchpad
            // builder) instead of an Android build banner
            "/proc/version" to
                "Linux version 4.15.0-213-generic (buildd@lcy02-amd64-079) " +
                "(gcc version 7.5.0 (Ubuntu 7.5.0-3ubuntu1~18.04)) " +
                "#224-Ubuntu SMP Mon Jun 19 13:30:12 UTC 2023",

            // rank 5 network.ip_asn — NOT captured in this ground-truth
            // snapshot (the 2026-05-20 audit run did not snapshot
            // /proc/net/route), but the path IS readable via
            // `docker exec redroid-test cat /proc/net/route` against the
            // live container. What we'd see on the un-spoofed container:
            // a route table whose default-route gateway hex is `010011AC`
            // (= 172.17.0.1, the Docker bridge) — the canonical
            // EMULATOR_GATEWAY_HEX_TOKEN for Docker. NetworkIpAsnProbe
            // would score 0.70 (SIGNAL_EMULATOR_GATEWAY_ROUTE) on the
            // un-spoofed container. The matching spoofed counterpart in
            // RedroidSpoofedSnapshot populates the path with a clean
            // home-network 192.168.1.1 gateway to disarm the rule.
            // Future re-captures SHOULD populate this entry verbatim from
            // the live container for full-fidelity replay parity.
        ),
        // Power-13 fixture upgrade — un-spoofed ReDroid containers running
        // the AOSP emulator carrier metadata ship the canonical synthetic
        // emulator identity. The original 2026-05-20 docker-exec capture
        // did not enumerate TelephonyManager because system_server was not
        // booted on kernel 4.15 (binderfs gap), so the original snapshot
        // declared `telephony = emptyMap()` by default. The values added
        // here are the AOSP-emulator defaults verbatim — same constants
        // referenced by strazzere/anti-emulator + EmulatorDetector, NOT
        // a fabricated lab approximation. Closes the Power-13 Gap #6
        // coverage where PATTERN_EMULATOR_PHONE_NUMBER and
        // PATTERN_EMULATOR_IMSI branches fire only on synthetic test ctx.
        telephony = mapOf(
            // AOSP emulator carrier name — RootBeer / strazzere flag
            // operatorName == "Android" as dispositive emulator marker.
            "OPERATOR_NAME" to "Android",
            // AOSP emulator MCC=310 (T-Mobile US) + MNC=260.
            "MCC_MNC" to "310260",
            // First entry of the 16-entry AOSP emulator phone-number
            // block (15555215554..15555215584). Power-13 Gap #6 rule
            // PATTERN_EMULATOR_PHONE_NUMBER fires on any block member.
            "LINE1_NUMBER" to "15555215554",
            // AOSP canonical IMSI — T-Mobile MCC/MNC + 10-zero
            // synthetic subscriber suffix. Power-13 Gap #6 rule
            // PATTERN_EMULATOR_IMSI fires on this exact literal.
            "SUBSCRIBER_ID" to "310260000000000",
        ),
        installedPackages = setOf(
            // ReDroid 12 minimal package set — only AOSP system packages
            // are pre-installed; nothing from the rank-10 marker list
            // would be expected to fire here. NOTE explicitly NO
            // `com.google.android.gms`: ReDroid containerized = no Play
            // Services. This is load-bearing for rank-2
            // (integrity.play_integrity) which scores the no-GMS signal at
            // 0.95 (NO_VERDICT tier). The absence is the ground-truth
            // emulator tell.
            //
            // **Rank-55 (ui.canvas_fingerprint) ground-truth signal**:
            // `com.google.android.webview` is INTENTIONALLY ABSENT from
            // this set. Every Google-certified retail Android phone ships
            // the Chromium WebView provider preinstalled (since 2016), but
            // ReDroid 12's containerized minimal /system_apks/ overlay
            // does not include it — the container has no Chromium binary,
            // so any WebView canvas render either fails entirely or falls
            // back to the AOSP stub which produces a non-standard pixel
            // hash. CanvasFingerprintProbe scores 0.85
            // (PATTERN_WEBVIEW_PROVIDER_MISSING) on this snapshot,
            // declaratively standing in for the un-snapshottable canvas-
            // render signal per the rank-55 KDoc.
            "android",
            "com.android.systemui",
            "com.android.settings",
        ),
        // ReDroid containerized = no real sensor HAL → empty set is GROUND
        // TRUTH, not a missing data point. Containerized ReDroid runs on the
        // Linux host kernel directly; it has no iio / sensor-hub HAL backend,
        // so `SensorManager.getSensorList(TYPE_ALL)` legitimately returns an
        // empty list. Declared explicitly (not relying on the data-class
        // default) so the negative-class semantics are visible at the call
        // site — preserves the contract that this snapshot represents an
        // un-spoofed ReDroid capture and not a snapshot-author oversight.
        sensorTypes = emptySet(),

        // ReDroid container has no Bluetooth HAL backend either —
        // BluetoothAdapter.getDefaultAdapter() returns null, and
        // /sys/class/bluetooth/hci0/address does not exist in the
        // container's /sys tree. Declared explicitly as the negative-class
        // ground truth (rather than relying on the data-class default)
        // for the same reasons as sensorTypes above.
        bluetoothMac = null,

        // ReDroid container has no GPS HAL backend — LocationManager
        // exists as a system service stub but no provider has ever
        // delivered a fix, so `getLastKnownLocation()` returns null for
        // every provider. Declared explicitly (all five gps* fields
        // null) as the negative-class ground truth: rank-41 will read
        // this as UnknownLocationManagerView → hasLastKnownLocation()
        // == null → CONFIDENCE_PERMISSION_MISSING. The probe still
        // scores 0.0 (no positive signal fires) — the LOW severity +
        // confidence-degraded result is the correct conservative answer
        // for "container with no real GPS hardware".
        gpsLat = null,
        gpsLng = null,
        gpsAccuracy = null,
        gpsProvider = null,
        gpsIsMock = null,

        // Power-12 rank-9.0 (runtime.frida_memory_maps) — DECLARATIVE.
        // The un-spoofed ReDroid container has no Frida payload mapped
        // into its system processes by default — no frida-server is
        // installed in the container image, no embedded gadget link.
        // Empty across all three surfaces (libs / threads / ports) is
        // the ground-truth ReDroid baseline. (If a future spoof iteration
        // installed Frida itself into the container, the probe would
        // fire; but the un-spoofed capture is clean of these markers.)
        procSelfMapsLibs = emptySet(),
        runtimeThreadNames = emptySet(),
        openTcpPorts = emptySet(),

        // Power-12 rank-9.7 (runtime.native_prologue_hash) — DECLARATIVE,
        // mitigation_layer not_spoofable. The JVM-side ReDroid capture
        // does NOT perform native-side ptrace measurements; the snapshot
        // therefore declares the conservative "no measurement" answer
        // (empty map + zero trampolines). The probe scores zero against
        // this — distinguishable from a real native-side scan returning
        // empty (= measurement happened and saw no hooks).
        prologueHashDeltas = emptyMap(),
        trampolinePatternCount = 0,

        // Power-12 rank-9.8 (integrity.prologue_got_hooks) — DECLARATIVE,
        // mitigation_layer not_spoofable. Same shape as rank-9.7: no
        // native-side GOT/PLT walk was performed at capture time, so
        // the anomaly map and rwxp segment list are both empty.
        gotPltAnomalies = emptyMap(),
        rwxpMemorySegments = emptyList(),

        // Power-13 Gap #3 (root.mount_ns_mismatch) + Gap #10
        // (root.system_rw_mount) + Gap #12 (root.overlayfs_present)
        // — realistic ReDroid 12 + Magisk mountinfo. ReDroid uses
        // Docker's overlay2 storage driver for the rootfs, so
        // `/system` is mounted as overlayfs on the host; the
        // captured container therefore has `overlay on /system`
        // visible from init's mount namespace. A Magisk-rooted
        // ReDroid additionally exposes `/sbin/.magisk` (pre-A11
        // Magisk root) AND `/data/adb` (Magisk modules root) in
        // init's view.
        //
        // Magisk DenyList typically hides these from the app's
        // /proc/self/mountinfo via per-app fork-bombed unmounts
        // — so the canonical UN-SPOOFED dirty state is:
        //   - /proc/1/mountinfo: full mount table with Magisk
        //   - /proc/self/mountinfo: pruned mount table without
        //     Magisk paths (Magisk hides itself by default)
        //
        // The asymmetry is the Power-13 Gap #3 signal — Magisk
        // mounts in init only fires PATTERN_MAGISK_IN_INIT_ONLY
        // at score 0.95.
        // Power-13 Gap #8 (root.magisk_module_dir) — Magisk-rooted
        // ReDroid has /data/adb/modules/ populated with active
        // modules. Modeled here with 3 real-world-common modules:
        // ZygiskNext (the modern Zygisk implementation),
        // safetynet-fix (PI / SafetyNet bypass), and MagiskHide
        // Props Config (the prop-spoofing module that backs many
        // Cloud-phone fingerprint fakes). Power-13 Gap #8 rule
        // PATTERN_MODULES_PRESENT fires at 1.0 on this fixture.
        dirEntries = mapOf(
            "/data/adb/modules" to listOf(
                "zygisksu",
                "safetynet-fix",
                "MagiskHidePropsConf",
            ),
        ),

        // Power-13 Gap #2 (runtime.init_svc_enumeration) — Magisk
        // injects 3 randomized service names at boot via its
        // post-fs-data + service init hooks. The names change per-
        // boot (Magisk-25+ randomized) but the COUNT and SHAPE
        // (hex-only 8-char names) are dispositive. This fixture
        // models a real Magisk-rooted Redroid boot — 3 random-shape
        // services + the standard AOSP set.
        initSvcProps = mapOf(
            // Standard AOSP services (always present)
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
            // Power-13 Gap #2 — 3 Magisk-injected random-shape
            // services. Hex-only 8-char names matching the
            // canonical Magisk-25+ injection pattern. Power-13
            // rule PATTERN_RANDOM_SHAPE_INJECTION fires at 0.95.
            "5a3f1c2b" to "running",
            "8d6e9700" to "running",
            "f1a2b3c4" to "running",
        ),

        // Power-13 Gap #1 (root.magisk_uds) — Magisk-rooted ReDroid
        // ships the magiskd UDS in /proc/net/unix. Pre-Magisk-25
        // canonical: literal `@MAGISK` abstract-namespace socket
        // (the literal string IS dispositive). The probe fires
        // PATTERN_MAGISK_UDS_PRESENT at 0.95 on this fixture.
        procNetUnixSockets = listOf(
            "@android:installd",
            "@android:zygote",
            "@android:netd",
            // Magisk's smoking-gun UDS. Pre-Magisk-25 canonical
            // literal — Magisk-25+ randomizes the name (separate
            // honest false-negative class documented in
            // MagiskUdsProbe KDoc).
            "@MAGISK",
            // Magisk's secondary daemon socket path. File-namespace
            // socket — leaks the runtime root path even when the
            // abstract-namespace name is randomized.
            "/sbin/.magisk/magiskd",
            "/dev/socket/installd",
            "/dev/socket/zygote",
        ),

        mountInfo = mapOf(
            // /proc/self/mountinfo — Magisk-DenyList-pruned view.
            // Same overlay /system (visible to apps because it IS
            // the root filesystem for the app), but Magisk paths
            // are unmounted in the app namespace. Realistic ReDroid
            // shape: a few dozen mount entries, of which only the
            // standard Android filesystem mounts are visible.
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
            // /proc/1/mountinfo — init view, includes Magisk
            // mount-binds. Magisk's signature paths are visible:
            // /sbin/.magisk (Magisk runtime root), /data/adb
            // (Magisk modules + denylist), and magisk_tmp (Magisk
            // workdir). Power-13 Gap #3 PATTERN_MAGISK_IN_INIT_ONLY
            // fires at 0.95 because these substrings appear here
            // but NOT in the self view above.
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

        // Power-16 B3 — IntegrityInstallSourceProbe (inventory rank 10.5,
        // freeRASP T5). Un-spoofed ReDroid 12 containers run app APKs
        // installed via `adb install` (sideload) — the install source is
        // `null` (no installer recorded). Probe scores 0.85 (strong
        // suspicion). This is the realistic captured ground-truth for
        // the container: `pm install -i ...` is not used by the docker
        // bring-up flow, so `getInstallerPackageName()` returns null.
        installSourcePackage = null,
    )
}
