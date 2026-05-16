# hide-frida-maps — L4 Xposed module

**Mutation proposal:** `019e2f10-37cb-7c8b-bbfb-90e573cfe302`
**Layer:** L4 (runtime anti-instrumentation)
**Probe targeted:** `runtime.frida_memory_maps`
**Framework:** Vector (JingMatrix/Vector v2.0) atop NeoZygisk (JingMatrix/NeoZygisk v2.3)
**Version:** 0.1.0

## What it does

Intercepts all reads of `/proc/<pid>/maps`, `/proc/<pid>/smaps`, and related pseudo-files
and removes lines that contain any of the Frida/Gadget artefact patterns before returning
content to the reader. This prevents detection libraries from observing Frida's presence
in the target process's address space.

### Redaction patterns

| Pattern | Matches |
|---|---|
| `frida-` | frida-agent-64.so, frida-server, etc. |
| `frida-agent-` | frida-agent-{arch}.so variants |
| `frida-gadget-` | frida-gadget-{arch}.so |
| `gadget` | gadget-less-specific match |
| `gum-js-loop` | Frida GumJS event loop thread name |
| `gmain` | GLib main loop thread (frida-core dependency) |
| `linjector` | linjector-based injection artefacts |
| `libfrida` | libfrida-{component}.so |

### Hook surface

| Hook | Coverage |
|---|---|
| `FileInputStream(String)` constructor | Java-side maps file open |
| `BufferedReader.readLine()` | Per-line Java reader |
| `Runtime.exec(String[])` | Shell-spawned `cat /proc/.../maps` |
| `libhide_frida_maps_native.so` | Native libc hooks via shadowhook (PLT) |

### Scope

Applied only to processes in the Vector scope list for this module. The Vector framework
enforces scope; this module additionally guards with a software denylist for
`android`, `com.android.systemui`, and `com.android.settings`.

`system_server`, `zygote`, and `init` are never in the scope list (hard rule from proposal).

## Build

```bash
cd stack/L4/hide-frida-maps
./gradlew assembleRelease
```

Output APK is installed as a Zygisk module via Vector:
```bash
adb push build/outputs/apk/release/hide-frida-maps-release.apk /data/local/tmp/
adb shell su -c 'pm install /data/local/tmp/hide-frida-maps-release.apk'
adb shell su -c 'cmd vector enable dev.cloudphone.hide.frida.maps'
```

Feature flag:
```bash
adb shell su -c 'echo 1 > /data/adb/modules/vector/conf/hide-frida-maps.enabled'
adb shell su -c 'cmd vector reload-modules'
```

## Rollback

```bash
adb shell su -c 'echo 0 > /data/adb/modules/vector/conf/hide-frida-maps.enabled'
adb shell su -c 'cmd vector reload-modules'
# or full uninstall:
adb shell su -c 'cmd vector disable dev.cloudphone.hide.frida.maps'
adb shell su -c 'rm -rf /data/adb/modules/vector/modules/hide-frida-maps'
adb reboot
```

## Composition safety

- TEESimulator: NOT composed
- TrickyStore: NOT composed
- L0a (ReDroid baseline): NOT touched
- No keybox, no paid SDK, no proprietary binary, no `:latest` image tag
- All module dependencies pinned by SHA (see `stack/image-pins.yml`)
