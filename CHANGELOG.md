# Changelog

All notable changes to CF-FAR. Versions follow `MAJOR.MINOR`, `versionCode` is monotonic.

## [1.11] — 2026-10-30

### Fixed
- **Critical regression:** POST bodies were read twice. `readHttpHead()` could consume part of
  the body into its buffer, and the exact-length forwarder then re-read the full
  `Content-Length` — the backend received a mismatched length and dropped the connection.
  Every request with a body failed. Byte counts are now reconciled before forwarding.
  Verified with 1 KB / 100 KB / 500 KB / 1 MB payloads (exact match).

## [1.10] — 2026-10-30

### Added
- **Audit mode** switch ("无限攻击"): disables the brute-force lockout for unlimited manual
  penetration testing. Token validation and all other protections stay active.

## [1.9] — 2026-10-30

### Fixed
- **HTTP pipelining / request smuggling:** a legitimate request followed by a second
  unauthenticated request on the same connection was forwarded without validation. The
  upstream direction is now `shutdownOutput()` after a single request body, so smuggled
  requests are discarded by the kernel.

## [1.8] — 2026-10-30

### Fixed
- **Query parameter name boundary:** `?xtoken=<token>` matched because of `indexOf("token=")`.
  Parameters are now parsed and compared by exact name.
- **Header substring matching:** `X-Auth-Token: <token>XYZ` was accepted. Header values are
  now parsed and compared with `equals`.

## [1.7] — 2026-10-30

### Fixed
- **Keep-alive authentication bypass:** validation ran once per TCP connection, so a second
  request on a reused connection skipped the token check. Requests are now forced to
  `Connection: close` and `--proxy-keepalive-connections 0` is passed to cloudflared.

## [1.6] — 2026-10-30

### Added
- Real client IP in the access banner and logs (`CF-Connecting-IP` → `X-Forwarded-For` → `X-Real-IP`).
- Self-test requests are tagged `X-Self-Test: 1` and no longer pollute the failure counter
  or the red access banner.

## [1.5] — 2026-10-30

### Added
- **Self-test** button: probes the local gateway port and reports whether the token is
  actually enforced.
- Persistent token status line (`🔒 令牌: 已启用` / `⚠ 令牌: 未启用`).

## [1.4] — 2026-10-30

### Fixed
- Changing settings while the tunnel was running was silently ignored
  (`wantRun == true` short-circuit). The app now restarts the tunnel automatically so new
  settings — especially the token — actually take effect.

## [1.3] — 2026-10-30

### Added
- **Copy address** now includes the token: `…?token=<url-encoded token>`.
- **Random token** generator: 32 chars from base62 via `SecureRandom` (~190 bit entropy).
- **Brute-force lockout:** 5 consecutive failures → 60 s (`429` + `Retry-After: 60`).

## [1.2] — 2026-10-30

### Added
- Token gateway (`?token=` / `X-Auth-Token` / `Authorization: Bearer`).
- URL-decoding of tokens so `/`, `%` and similar characters work.
- Red banner when an external client connects.

## [1.1] — 2026-10-30

### Added
- `SSL_CERT_FILE` injection with a merged system CA bundle (fallback: 150 built-in CAs).
- Bundled patched cloudflared (`resolv.conf.data`), removing the need for root DNS.
- `targetSdk 28` to stay in the `untrusted_app_27` domain and allow `exec`.

## [1.0] — 2026-10-30

### Added
- Initial release: local port forwarder, cloudflared process supervision,
  auto-reconnect with exponential backoff, foreground service, wake lock,
  4000-line ring-buffer log with copy/share.
