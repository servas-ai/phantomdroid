# Power-16 B4 — Native Disassembly of `libtoolChecker.so`

**Date:** 2026-05-21
**Branch:** report/CLO-143-weekly-W20
**Scope:** RootBeer 0.1.1 native JNI library, both ARM64 + x86_64 ABI slices.
**Inputs:** AAR from Maven Central — `rootbeer-lib-0.1.1.aar` (SHA256 `3c0484625100c62e201d07b540ae87fc1f3f91cc503502f218b6890097740851`).

This audit closes the last open empirical question from Power-14: **does the native `libtoolChecker.so` hold its own hardcoded suPath table, distinct from the Java-side `Const.suPaths` / `Const.SU_BINARY_PATHS` we replay against?** Power-14 §2.3 deferred this with the note "native disasm pending". B4 settles it from the binary, not from the Java caller.

---

## §1 ELF Header + Section Info

Both .so files are 64-bit ELF DYN (shared objects), built with the same Android NDK toolchain (clang 18.0.1, LLD 18.0.1, build 12077973).

| Field | x86_64 slice | arm64-v8a slice |
|---|---|---|
| File size | 5416 bytes | 5512 bytes |
| ELF Class | ELF64 | ELF64 |
| Machine | Advanced Micro Devices X86-64 | AArch64 |
| Type | DYN (Shared object file) | DYN (Shared object file) |
| Entry point | 0x0 (lib only) | 0x0 (lib only) |
| Section count | 24 | 24 |
| Program headers | 9 | 9 |
| SHA256 | `d45212dc93e3e488802906f9dbbd1698bcf70fb30a12d2d108b1e60211ea3cc9` | `eeb2317e649e287b836b5e5c30cb07700f9eb900a53c297a3823592f15aa6352` |

**Dynamic dependencies (identical, both slices):** `libandroid.so`, `liblog.so`, `libm.so`, `libdl.so`, `libc.so`. SONAME = `libtoolChecker.so`. `BIND_NOW` set (eagerly resolved).

**Build identification (.note.android.ident + .comment):**
```
Android (12027248, +pgo, -bolt, +lto, -mlgo, based on r522817)
clang version 18.0.1
Linker: LLD 18.0.1
```

Toolchain is identical across both architectures — no architecture-specific instrumentation, no debug-build leakage.

---

## §2 Global FUNC Symbols (Java_RootBeerNative_* exports)

Extracted via `readelf -sW <so> | grep -E "FUNC.*GLOBAL.*DEFAULT" | grep -v UND`. Symbol names are **identical** across both ABIs (byte-for-byte). Only address + size differ (codegen difference).

| Slice | Symbol | Address | Size |
|---|---|---|---|
| x86_64 | `Java_com_scottyab_rootbeer_RootBeerNative_setLogDebugMessages` | 0x8b0 | 45 bytes |
| x86_64 | `_Z6existsPKc` (C++ mangled `exists(char const*)`) | 0x8e0 | 166 bytes |
| x86_64 | `Java_com_scottyab_rootbeer_RootBeerNative_checkForRoot` | 0x990 | 333 bytes |
| arm64-v8a | `Java_com_scottyab_rootbeer_RootBeerNative_setLogDebugMessages` | 0x8a8 | 72 bytes |
| arm64-v8a | `_Z6existsPKc` | 0x8f0 | 188 bytes |
| arm64-v8a | `Java_com_scottyab_rootbeer_RootBeerNative_checkForRoot` | 0x9ac | 388 bytes |

**JNI surface count: 2 exports** (`setLogDebugMessages`, `checkForRoot`) + 1 internal C++ helper (`exists`). **NO `checkForBinary`** native symbol exists — the planning brief's mention of "checkForBinary" reflects the Java-side method name; the native side names it `checkForRoot`, which takes a `String[]` path-set as the second JNI arg and iterates it.

**ABI parity verified:** both slices export the exact same 2-function JNI surface. Diff between x86_64 and arm64-v8a symbol tables is empty (modulo addresses + sizes, which are codegen artifacts).

---

## §3 String Table Summary (Paths Only)

Extracted via `strings <so> | grep -E "(su$|magisk|/system/|/data/|/sbin|/dev/|/proc/|/vendor|/cache|/su/)"`.

**Result for both slices: zero matches.** The native lib contains no suPath strings whatsoever.

The complete `.rodata` string inventory across both slices (modulo `.dynstr` symbol-name strings):

| String | Purpose |
|---|---|
| `LOOKING FOR BINARY: %s Absent :(\n` | log message, "not found" branch |
| `LOOKING FOR BINARY: %s PRESENT!!!\n` | log message, "found" branch |
| `RootBeer` | log tag passed to `__android_log_print` |
| `r` | fopen mode (read-only, embedded at 0x6db) |

(`.dynstr` separately contains the JNI symbol names + libc imports listed in §2 + §1.)

**Implication:** the native library is data-free with respect to detection paths. It is a **path-existence engine** that takes paths from its caller.

---

## §4 Native vs. Java-side suPath Diff

### Java-side canonical list (14 paths)

From `RootBeerReplayTest.kt:167-182` (mirrors shipping `com.scottyab.rootbeer.Const.suPaths`):

```
/data/local/
/data/local/bin/
/data/local/xbin/
/sbin/
/su/bin/
/system/bin/
/system/bin/.ext/
/system/bin/failsafe/
/system/sd/xbin/
/system/usr/we-need-root/
/system/xbin/
/cache/
/data/
/dev/
```

Count: **14**.

### Native-side path inventory

Disassembling `Java_com_scottyab_rootbeer_RootBeerNative_checkForRoot` (see `disasm/x86_64-r2-checkForRoot.txt`, `disasm/arm64-v8a-r2-checkForRoot.txt`) shows the following data flow for both slices:

1. `arg1 = JNIEnv*`, `arg3 = jobjectArray paths` (the Java `String[]`).
2. `GetArrayLength(env, paths)` is called via JNIEnv vtable offset `[rax + 0x558]` (x86_64) / equivalent on arm64.
3. Loop counter `r13d` (x86_64) / `w22` (arm64) iterates `0 .. arrayLength-1`.
4. Inside loop:
   - `GetObjectArrayElement(env, paths, i)` via vtable offset `[rax + 0x568]` → returns `jstring`.
   - `GetStringUTFChars(env, jstring, NULL)` via `[rax + 0x548]` → returns `const char *path`.
   - `fopen(path, "r")` — pointer to the string `"r"` is the **only** native-side path-adjacent constant.
   - On non-null return: increment hit-counter `ebp` / `w26`, optionally log via `__android_log_print` (gated by global debug flag `LOAD2[0]`).
   - `ReleaseStringUTFChars(env, jstring, path)` via `[rax + 0x550]`.
5. After loop: `return (hitCount != 0) ? 1 : 0`.

### Diff result

| Question | Answer |
|---|---|
| Native suPath count | **0** (zero hardcoded paths) |
| Java-side suPath count | 14 |
| Native paths NOT in Java list | none (the set is empty) |
| Java paths NOT in native list | not meaningful — native has no list |
| Functional gap | **none** — `checkForRoot` is a pure path-existence iterator over the Java-supplied `String[]` |

**The Java-side `Const.suPaths` IS the canonical list.** The native side is a transparent JNI dispatcher to `fopen` + path-existence semantics. There is no hidden second path list anywhere in the binary.

This is the cleanest possible audit result: **what you replay against the Java surface (14 paths) is the complete set of paths checked**, byte-for-byte.

---

## §5 Findings

### F-1 (positive): no hidden native path table

The native `libtoolChecker.so` carries **zero hardcoded detection paths**. Confirmed by:
- exhaustive string-table scan (§3): no `/system/`, `/data/`, `/sbin`, `/su/`, `/cache`, `/dev/`, `/proc/`, `/vendor`, or `magisk`-prefixed strings.
- function disassembly (§4): `checkForRoot` consumes a `jobjectArray` from JNI, iterates `GetStringUTFChars` → `fopen` per element. No `.rodata` references to path-strings inside the loop body.

**Reverse-engineering implication for Power-14 replay:** the 14-suPath `RootBeerReplayTest.suPathsScan()` is **complete with respect to native code**. There is no native bypass surface to add; the only way to extend RootBeer's path list is for the Java caller to pass more paths via `Const.suPaths` (an upstream Java change). Our `SuDetectionProbe.SU_BINARY_PATHS` (14 paths) matches RootBeer's full operational surface.

### F-2 (positive): ABI parity, no slice-specific instrumentation

Both ARM64 and x86_64 slices:
- Export exactly the same 2-function JNI symbol set (§2).
- Reference exactly the same 4 `.rodata` strings (§3).
- Implement `checkForRoot` with the same `fopen`-based existence test (§4).

No architecture-specific anti-tamper, no debug-build divergence, no x86-only or arm-only paths. This rules out the conjecture that RootBeer could ship different detection logic per ABI to evade replay on emulator-targeted x86 builds.

### F-3 (positive): no `system(2)`, no `execve(2)`, no `which`, no `/proc` reads

The dynamic import table (§1 / `_*-readelf-d.txt`) shows libc functions used: `fopen`, `fclose`, `__cxa_atexit`, `__cxa_finalize`, `__stack_chk_fail`. Plus liblog's `__android_log_print`. **Nothing else.** No `system`, no `execvp`, no `fork`, no `open`, no `readdir`, no `popen`. The native side is functionally restricted to path-existence-via-fopen. This corroborates the Power-14 finding that `Const.pathThatExist` (Runtime.exec-based) is a **separate, Java-side** code path — not delegated to native.

### F-4 (no gap): `setLogDebugMessages` is a no-op for detection

The second JNI export (`setLogDebugMessages`) writes a single byte to a global flag (`LOAD2[0]` in x86_64 disasm, addr `0x8d70`; equivalent in arm64). The flag gates the `__android_log_print` calls in both `exists()` and `checkForRoot`. It does not affect detection semantics — only verbosity. Confirmed irrelevant for replay-fidelity.

### F-5 (artifact-quality note): objdump cannot disassemble aarch64 in this sandbox

`/usr/bin/objdump` (binutils 2.42) on this Ubuntu host lacks the aarch64 backend ("`can't disassemble for architecture UNKNOWN`"). The arm64 disasm in `disasm/arm64-v8a-r2-*.txt` was produced via `radare2` 5.x instead, which is the supported alternative. x86_64 disasm via `objdump --disassembler-options=intel` was unaffected. No analysis was deferred — full coverage achieved.

### Documented gaps: NONE

There are no hidden findings, no "real gaps", no honest amendments owed back to Power-14 §2.1. The Java-side 14-suPath surface fully covers the native code path. If a future RootBeer version changes `Const.suPaths`, the diff applies to the Java side only — the native lib does not need to be re-audited unless the JNI signature itself changes.

---

## §6 Reproducibility

### Tool versions

| Tool | Path | Version |
|---|---|---|
| objdump | /usr/bin/objdump | GNU Binutils 2.42 (Ubuntu) |
| readelf | /usr/bin/readelf | GNU Binutils 2.42 (Ubuntu) |
| nm | /usr/bin/nm | GNU Binutils 2.42 (Ubuntu) |
| strings | /usr/bin/strings | GNU Binutils 2.42 (Ubuntu) |
| radare2 | /usr/bin/r2 | 5.x (Ubuntu apt) |

### Input artifacts

| File | SHA256 |
|---|---|
| `rootbeer.aar` (Maven Central, scottyab/rootbeer-lib 0.1.1) | `3c0484625100c62e201d07b540ae87fc1f3f91cc503502f218b6890097740851` |
| `jni/x86_64/libtoolChecker.so` | `d45212dc93e3e488802906f9dbbd1698bcf70fb30a12d2d108b1e60211ea3cc9` |
| `jni/arm64-v8a/libtoolChecker.so` | `eeb2317e649e287b836b5e5c30cb07700f9eb900a53c297a3823592f15aa6352` |

### Commands (re-run from any host)

```bash
# 1. fetch AAR + extract
mkdir -p /tmp/power16-native && cd /tmp/power16-native
curl -sL -o rootbeer.aar https://repo1.maven.org/maven2/com/scottyab/rootbeer-lib/0.1.1/rootbeer-lib-0.1.1.aar
sha256sum rootbeer.aar  # expect 3c0484625100c62e201d07b540ae87fc1f3f91cc503502f218b6890097740851
unzip -o rootbeer.aar -d rootbeer-extract/

# 2. headers, sections, symbols
for arch in x86_64 arm64-v8a; do
    LIB="rootbeer-extract/jni/$arch/libtoolChecker.so"
    readelf -h "$LIB"
    readelf -d "$LIB"
    readelf -sW "$LIB" | grep -E "FUNC.*GLOBAL.*DEFAULT" | grep -v UND
    strings "$LIB"
done

# 3. x86_64 disasm via objdump
objdump -d rootbeer-extract/jni/x86_64/libtoolChecker.so --disassembler-options=intel

# 4. arm64 disasm via radare2 (objdump on this host lacks aarch64 backend)
r2 -q -e bin.cache=true -e scr.color=0 -c "aaa; pdf @ sym.Java_com_scottyab_rootbeer_RootBeerNative_checkForRoot" rootbeer-extract/jni/arm64-v8a/libtoolChecker.so
r2 -q -e bin.cache=true -e scr.color=0 -c "aaa; pdf @ sym.exists_char_const_" rootbeer-extract/jni/arm64-v8a/libtoolChecker.so
```

### Persisted artifacts (sandbox, /tmp)

All under `/tmp/power16-native/disasm/`:

| File | Description |
|---|---|
| `{arch}-readelf-h.txt` | ELF header |
| `{arch}-readelf-d.txt` | dynamic section (NEEDED libs, flags) |
| `{arch}-symbols.txt` | filtered FUNC symbols |
| `{arch}-symbols-all.txt` | full symbol table |
| `{arch}-strings-all.txt` | all printable strings |
| `{arch}-strings-paths.txt` | path-filtered (empty for both — see §3) |
| `{arch}-objdump.txt` | objdump disasm (x86_64 only; arm64 file empty due to F-5) |
| `{arch}-r2-checkForRoot.txt` | radare2 disasm of `checkForRoot` |
| `{arch}-r2-exists.txt` | radare2 disasm of `exists()` |
| `{arch}-r2-setLogDebug.txt` | radare2 disasm of `setLogDebugMessages` |

(Sandbox files are not committed; the commands above regenerate them deterministically.)

---

## Conclusion

The native `libtoolChecker.so` is a thin, transparent JNI wrapper around `fopen`-based path-existence checks. It carries no hardcoded paths, no architecture-specific instrumentation, and no syscalls beyond libc `fopen`/`fclose` + Android `__android_log_print`. **The Power-14 14-suPath replay surface is byte-complete with respect to the native code path.** No honest amendment owed; no real gap to document. Closes Power-16 B4.
