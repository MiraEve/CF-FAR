# CF-FAR

**Cloudflare Tunnel · Android Remote Access**

Expose any local or LAN service running on your Android phone to the internet through a
Cloudflare Quick Tunnel — no account, no domain, no server required. Ships with a built-in
token gateway, brute-force protection and a live access log.

[English](README.md) · [简体中文](README.zh-CN.md)

## ⬇️ Download

[![Download APK](https://img.shields.io/badge/⬇_Download-CfTunnel--v1.11.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/MiraEve/CF-FAR/releases/latest/download/CfTunnel-v1.11.apk)
[![Latest release](https://img.shields.io/github/v/release/MiraEve/CF-FAR?style=for-the-badge&label=release&color=blue)](https://github.com/MiraEve/CF-FAR/releases/latest)
[![License](https://img.shields.io/github/license/MiraEve/CF-FAR?style=for-the-badge&color=orange)](LICENSE)

**Download:** [`CfTunnel-v1.11.apk`](https://github.com/MiraEve/CF-FAR/releases/latest/download/CfTunnel-v1.11.apk) · 18 MB · arm64-v8a · Android 9+

> There is no APK in the source tree on purpose — binaries live in
> **[Releases](https://github.com/MiraEve/CF-FAR/releases/latest)**. Pick `CfTunnel-v1.11.apk` under *Assets*.

**Install:** download → allow *install unknown apps* → open → set local port → **Start tunnel** → **Copy URL + token**.

Verify the download:
```bash
sha256sum CfTunnel-v1.11.apk
# f65ae1df7290e54f9ac97548a68e73938450b2ee40933530f36286dd4b4eb7ed
```

---


---

## Why this exists

`cloudflared` is a Go binary. On Android it fails out of the box for three non-obvious reasons,
and most "just run it in Termux" advice does not survive a locked-down device:

| Problem | Why it breaks | How CF-FAR solves it |
|---|---|---|
| **No DNS** | Android has no `/etc/resolv.conf`. Go falls back to `127.0.0.1:53`, but an ordinary app **cannot bind ports below 1024** (kernel `PROT_SOCK=1024`). A local DNS relay on 53 is impossible without root. | The bundled binary is patched: the hard-coded `/etc/resolv.conf` (16 bytes) is replaced in-place with the relative path `resolv.conf.data`. The app starts the process with its private dir as CWD and writes the live system DNS into that file. |
| **No root CAs** | Go's default certificate path list does **not** include Android's `/system/etc/security/cacerts` (verified against the shipped binary). TLS handshake fails instantly. | On start, system certificates are merged into a PEM bundle (falling back to 150 built-in CAs) and injected via the `SSL_CERT_FILE` environment variable. |
| **No exec** | Android 10+ forbids executing binaries from writable directories. | `targetSdk = 28` keeps the app in the `untrusted_app_27` domain, which is allowed to `exec` on Android 9 and below. |

On top of that, CF-FAR adds the layer that Quick Tunnels lack: **access control**.

---

## Features

- **One-tap tunnel** — pick a local port and a target, get an `https://*.trycloudflare.com` URL
- **Any local port** — the listening port is fully configurable (`127.0.0.1:<your port>`)
- **LAN targets** — forward to `127.0.0.1` or any address reachable from the phone (`192.168.x.x`)
- **Token gateway** — reject unauthenticated requests *before* they reach your service
- **Brute-force protection** — 5 consecutive failures → 60 s lockout (`429` + `Retry-After`)
- **Audit mode** — a switch that disables the lockout so you can run unlimited penetration tests
- **Live access alerts** — a red banner appears the moment someone connects, with the **real client IP** (`CF-Connecting-IP`)
- **Full log** — 4000-line ring buffer, on-disk file, one-tap copy / share
- **Resilient** — auto-restart with exponential backoff, foreground service, wake lock, notification status

---

## Quick start

1. Install `CfTunnel-arm64.apk` (Android 5.0+, **arm64-v8a** only).
2. Set **Local port** (default `8888`) and **Target** (`127.0.0.1:8080`).
3. Tap **Random** to generate a 32-character token (`SecureRandom`, ~190 bit entropy).
4. Tap **Start**. Wait for the green URL, then **Copy address** — the clipboard contains
   `https://xxx.trycloudflare.com?token=<your token>`.
5. Tap **Self-test** to confirm the gateway is actually active.

> **The token box is the lock; the `?token=` in the URL is the key.**
> Setting one without the other does nothing.

---

## Authentication

Three ways to present the token — any one is enough:

```http
GET /?token=<token>
X-Auth-Token: <token>
Authorization: Bearer <token>
```

- Comparison is **exact** (no substring, no prefix matching), parameter names are matched exactly
  (so `?xtoken=` does not work).
- URL-encoded tokens are decoded before comparison.
- On failure the gateway returns a minimal `{"error":"unauthorized"}` — your service is never reached.
- 5 consecutive failures → `429 Too Many Requests` for 60 seconds.

---

## Security model

CF-FAR is a **transport gateway**, not a full WAF. What it guarantees:

| Guarantee | Status |
|---|---|
| Unauthenticated requests never reach the backend | ✅ |
| One TCP connection handles exactly one request (no keep-alive smuggling) | ✅ |
| HTTP pipelining / request smuggling blocked | ✅ |
| Exact token comparison, no prefix/suffix bypass | ✅ |
| Brute-force lockout | ✅ (disable with audit mode) |
| Real client IP surfaced in UI and logs | ✅ |

**Known limitations — read before exposing anything sensitive:**

- The lockout is global, so an attacker can deliberately trigger it (60 s DoS).
- Tokens in URLs leak into Cloudflare logs, browser history and `Referer`. Prefer the header.
- No IP allowlist and no per-IP rate limiting.
- Quick Tunnels have **no uptime guarantee** and a new hostname on every restart.
- CF-FAR cannot protect a service that is also reachable directly on your LAN — bind your
  backend to `127.0.0.1`, not `0.0.0.0`.

See [SECURITY.md](SECURITY.md) for the disclosure policy and
[docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md) for the full adversarial test record
(5 rounds, 60+ attack vectors, 5 real vulnerabilities found and fixed).

---

## Architecture

```
Remote client
     │  HTTPS
     ▼
Cloudflare edge  ──tunnel (HTTP/2)──►  cloudflared (bundled, patched)
                                             │
                                             ▼
                                  127.0.0.1:<local port>   ← token gateway
                                             │
                                             ▼
                                  target (127.0.0.1 / LAN)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

---

## Build

The project deliberately avoids Gradle so it can be built in a minimal environment.
See [docs/BUILD.md](docs/BUILD.md).

```bash
./scripts/fetch-cloudflared.sh   # download + patch the arm64 binary
./scripts/build.sh               # aapt2 → javac → d8 → zipalign → apksigner
```

The APK in this repository is **not** prebuilt — you build it yourself, which keeps the
repository small and the binary auditable.

---

## License

[MIT](LICENSE) © MiraEve

Bundled third-party components (full text in [NOTICE](NOTICE)):

- **cloudflared** — Apache-2.0, © Cloudflare, Inc. Downloaded at build time, patched in place.
- **Mozilla CA bundle** — MPL-2.0, used as a fallback certificate set.

---

## Disclaimer

CF-FAR is intended for exposing **your own** services for **temporary** remote access.
You are responsible for what you expose and for complying with
[Cloudflare's Terms of Service](https://www.cloudflare.com/website-terms/).
Do not use it to bypass access controls on systems you do not own.
