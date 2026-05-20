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
            "ro.secure" to "1",
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
        // No Android settings or telephony state in this capture — empty maps
        // give the conservative "key not in map → null" answer.
        installedPackages = setOf(
            // ReDroid 12 minimal package set — only AOSP system packages
            // are pre-installed; nothing from the rank-10 marker list
            // would be expected to fire here.
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
    )
}
