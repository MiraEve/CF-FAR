# Architecture

CF-FAR is a single-process Android app (no services, no daemons) that supervises one
`cloudflared` child process and one TCP forwarder.

```
┌──────────────────────────── Android device ────────────────────────────┐
│                                                                        │
│  Remote client                                                         │
│       │ HTTPS                                                          │
│       ▼                                                                │
│  Cloudflare edge ──QUIC/HTTP2 tunnel──► cloudflared (child process)    │
│                                              │ HTTP/1.1                │
│                                              ▼                         │
│                                    127.0.0.1:8888                      │
│                                    ┌──────────────────┐                │
│                                    │  PortForwarder   │  ← token gate  │
│                                    │  · auth          │                │
│                                    │  · single-req    │                │
│                                    └────────┬─────────┘                │
│                                             ▼                          │
│                                   target 127.0.0.1:8080                │
│                                   (or 192.168.x.x:port)                │
└────────────────────────────────────────────────────────────────────────┘
```

## Components

| File | Responsibility |
|---|---|
| `MainActivity.java` | UI, input validation, log rendering, copy/share, token generation, self-test |
| `TunnelService.java` | Foreground service; orchestrates DNS → forwarder → cloudflared; restart policy; notification; wake lock |
| `CfProcess.java` | Spawns `cloudflared`, streams its output line by line, extracts the `trycloudflare.com` URL |
| `PortForwarder.java` | TCP listener + HTTP-aware token gateway |
| `DnsBridge.java` | Optional 53/udp relay (only works if the device allows binding low ports; the primary DNS path is `resolv.conf.data`) |
| `Logger.java` | 4000-line ring buffer, on-disk file with rotation, listener callbacks |
| `Config.java` | Plain settings object |

## Startup sequence

```
onStartCommand
  └─ thread "tunnel-start"
       ├─ acquireWakeLock
       ├─ ensureBinary()        extract assets/cloudflared → files/cloudflared (chmod +x)
       ├─ ensureCaBundle()      merge /system/etc/security/cacerts → files/ca-bundle.pem
       │                        (fallback: assets/cacerts.pem)
       ├─ prepareDns()          write files/resolv.conf.data with live system DNS
       ├─ forwarder.start()     bind 127.0.0.1:<localPort>, install token gate
       ├─ launchCf()            spawn cloudflared --url http://127.0.0.1:<localPort>
       └─ watchFirstUrl()       warn if no hostname within 60 s
```

## The three Android-specific workarounds

### 1. DNS — the `resolv.conf.data` patch

Go's resolver reads `/etc/resolv.conf`. Android does not ship one; the fallback is
`127.0.0.1:53`, which an unprivileged app cannot bind (`PROT_SOCK = 1024`).

The shipped binary is patched byte-for-byte:

```
/etc/resolv.conf      (16 bytes)
resolv.conf.data      (16 bytes)   ← same length, no NUL, so no ELF damage
```

Because the result is a **relative** path, the process resolves it against its working
directory — set to the app's private dir — where `prepareDns()` writes:

```
nameserver <system dns>
nameserver 223.5.5.5
nameserver 1.1.1.1
```

### 2. Certificates — `SSL_CERT_FILE`

The Go binary's built-in certificate directory list does not contain
`/system/etc/security/cacerts` (verified: 0 occurrences in the binary). Without intervention,
every TLS handshake fails with `x509: certificate signed by unknown authority`.

`ensureCaBundle()` concatenates every PEM found in the system store into one file and exports it
as `SSL_CERT_FILE`. If the store is unreadable, 150 bundled Mozilla CAs are used instead.

### 3. Exec — `targetSdk = 28`

Android 10+ blocks `exec` from writable directories for apps targeting API 29+. Staying at
API 28 keeps the app in the `untrusted_app_27` SELinux domain, where executing a binary from
`filesDir` is permitted.

## Token gateway flow

```
accept()
  ├─ read request head (≤32 KB, 8 s timeout)
  ├─ extract method / path / CF-Connecting-IP
  ├─ if locked (and not audit mode)      → 429 + Retry-After
  ├─ if !authorized(head, path)          → count += 1
  │     ├─ count ≥ 5 → set lock,         → 429
  │     └─ otherwise                     → 401 (backend never contacted)
  ├─ forward head with Connection: close
  ├─ forward exactly Content-Length-已读 bytes of body
  ├─ shutdownOutput()      ← kills pipelining and smuggling
  └─ stream response downstream only
```

## Process supervision

`cloudflared` exiting unexpectedly triggers a restart with backoff `2,4,6…30 s`, up to 8 times.
A generation counter invalidates callbacks from superseded processes so a stale exit handler
cannot restart a tunnel the user just stopped.

## Why no Gradle

The whole app is ~2 400 lines of Java with zero dependencies. `aapt2 + javac + d8 + apksigner`
produces an identical APK in seconds, works offline, and keeps the build auditable — and it runs
on machines where a full Android SDK install is impractical.
