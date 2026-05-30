# Proof Slice 5 — Live ReDroid Baseline Boot Verification

**Result: PASS**

Verification slice 5 of 5 for the PhantomDroid 100% E2E proof. READ-ONLY verification
against the live server. No mutating commands (no rm/stop/restart/exec-write/reboot)
were issued. Baseline confirmed untouched.

- **Server:** paris@195.154.209.133
- **Verification timestamp:** Sat May 30 02:17:26 CEST 2026
- **Mode:** READ-ONLY (fresh evidence collected live)

---

## Evidence

### 1. Kernel version (host)

```
$ uname -r
5.4.0-150-generic
```

- Expected: `5.4.0-150-generic`
- Actual: `5.4.0-150-generic`
- **PASS**

### 2. binderfs mounted (host)

```
$ mount | grep binder
binder on /dev/binderfs type binder (rw,relatime,max=1048576)
```

- Expected: binderfs mounted
- Actual: mounted at `/dev/binderfs` (type binder)
- **PASS**

### 3. Container running (`docker ps`)

```
$ docker ps
CONTAINER ID   IMAGE             COMMAND                  CREATED       STATUS       PORTS                      NAMES
fdcc06a45012   redroid/redroid   "/init qemu=1 androi…"   5 hours ago   Up 5 hours   127.0.0.1:5555->5555/tcp   redroid-test
```

- Expected: `redroid-test` Up
- Actual: `redroid-test` Up 5 hours, port 127.0.0.1:5555->5555/tcp
- **PASS**

### 4. Android boot properties (`docker exec redroid-test getprop ...`)

```
$ docker exec redroid-test getprop sys.boot_completed
1

$ docker exec redroid-test getprop init.svc.zygote
running

$ docker exec redroid-test getprop ro.build.version.release
12
```

| Property                      | Expected  | Actual    | Result |
|-------------------------------|-----------|-----------|--------|
| `sys.boot_completed`          | 1         | 1         | PASS   |
| `init.svc.zygote`             | running   | running   | PASS   |
| `ro.build.version.release`    | 12        | 12        | PASS   |

### 5. Installed package count (`pm list packages | wc -l`)

```
$ docker exec redroid-test pm list packages | wc -l
96
```

- Expected: ~96
- Actual: 96
- **PASS**

---

## Baseline-Untouched Confirmation

```
$ docker inspect -f "StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Running={{.State.Running}}" redroid-test
StartedAt=2026-05-29T18:50:21.788346966Z RestartCount=0 Running=true
```

- `RestartCount=0` — the container has never been restarted.
- `StartedAt=2026-05-29T18:50:21Z` — single continuous uptime (~5h at verification time),
  consistent with `docker ps` "Up 5 hours".
- `Running=true`.
- No mutating commands were issued during this verification (read-only: `uname`, `mount`,
  `docker ps`, `getprop`, `pm list packages`, `docker inspect`).

**Baseline confirmed untouched.**

---

## Summary

| Check                         | Result |
|-------------------------------|--------|
| Kernel `5.4.0-150-generic`    | PASS   |
| binderfs mounted              | PASS   |
| `redroid-test` container Up   | PASS   |
| `sys.boot_completed` == 1     | PASS   |
| `init.svc.zygote` == running  | PASS   |
| `ro.build.version.release`==12| PASS   |
| package count ~96             | PASS   |
| baseline untouched (no restart)| PASS  |

**OVERALL: PASS** — The live ReDroid baseline is fully booted (Android 12, boot completed,
zygote running, 96 packages) on host kernel 5.4.0-150-generic with binderfs mounted.
Baseline left untouched.
