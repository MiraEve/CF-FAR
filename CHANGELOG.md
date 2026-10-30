# Changelog

All notable changes to CF-FAR. Versions follow `MAJOR.MINOR`, `versionCode` is monotonic.

## [1.17] — 2026-10-31

### Fixed — **opening a link could lock you out of your own tunnel**
- **Browser sub-requests counted as brute-force attempts.** Every `GET /favicon.ico`,
  `/apple-touch-icon.png`, `/manifest.json` that a browser fires automatically arrives *without*
  the token (they are separate requests; the `?token=` only exists on the URL you typed). Each one
  was a 401 that incremented the failure counter. Five of them — trivially reached by opening the
  link a couple of times, or once with a browser that prefetches — hit `MAX_FAIL` and locked the
  gateway for 60 s. During that window **even a correct token got `429 {"error":"locked"}`**.
  Reproduced live against a real instance: `favicon1..4 → 401`, `favicon5..6 → 429`, then
  `?token=<correct> → 429`.
- **Fix:** `authorized()` became `checkAuth()` returning three states instead of a boolean —
  `AUTH_OK` / `AUTH_NO_TOKEN` / `AUTH_BAD_TOKEN`. The failure counter now advances **only** on
  `AUTH_BAD_TOKEN`, i.e. only when a token was actually supplied and was wrong. A request with no
  token anywhere is refused but never counted. Rationale: an unauthenticated request can never
  succeed, so it has no brute-force value; counting it only punishes legitimate visitors.
- In header-only mode a `?token=` in the URL is deliberately *not* treated as "token presented"
  either — that carrier is switched off on purpose, and treating it as an attempt would let a
  stale bookmarked link lock the owner out.

### Verified
- 16/16 logic tests: three-state classification (8), header-only `?token=` handling (2),
  incident repro — 8 consecutive tokenless requests never lock and a correct token still returns
  200 (3), real brute force still locks at the 5th wrong token (2), mixed attacker + browser
  traffic (1).
- Protection is unchanged for actual guessing: 5 wrong tokens still produce `429`.

## [1.16] — 2026-10-31

Two defects found while auditing why "turn off header-only, then open the link" failed.

### Fixed
- **Switching `headerOnly` (or `unlimitedAttack`) while the tunnel was running did nothing.**
  The listener only wrote the value to `SharedPreferences`. What the gateway actually enforces is
  the `headerOnly` field inside the **running** `PortForwarder`, which is set once at start time.
  So the UI said one thing, the prefs said another, and the gateway kept doing whatever it was
  doing at launch — the user turns the switch off and `?token=` still returns 401.
  Both switches now compare the requested value against `TunnelService.headerOnlyActive` /
  `unlimitedAttackActive` and **restart the tunnel automatically** when they differ, reusing the
  existing stop → delay → start path. When the tunnel is not running there is nothing to sync, so
  it only persists. `loadPrefs()` sets `suppressAutoRestart` so an Activity being recreated does
  not trigger a pointless restart.
- **Forwarding failures were silent.** `handle()` caught the exception, logged it, and called
  `closeQuietly(in)` without writing any HTTP response. The client (and Cloudflare) therefore only
  ever saw Cloudflare's own `trycloudflare.com | 502` HTML page — indistinguishable from an auth
  problem to anyone looking at it, which is exactly how this got misreported as an auth-mode bug.
  It now returns a JSON 502 `{"error":"backend_unreachable"}` and surfaces
  `后端不可达 —— 令牌和网关都正常，是 host:port 没有响应` in the red alert banner.
- **`reject()` only ever wrote `401 Unauthorized` or `429 Too Many Requests` as the status line.**
  Passing any other code produced a response whose status line contradicted its own status code.
  Replaced with `statusText(code)` covering 401/404/405/429/500/502/504.

### Added
- **`tvHeaderOnlyState`**: a line under the switch that reports the *gateway's* effective mode, not
  just the checkbox — grey when idle, blue `■ 仅 Header 已生效 —— ?token= 一律 401`, green
  `■ 三种方式都可用 —— 链接可直接打开`, and orange `⚠ 设置与网关不一致，正在重启生效…` if the
  two ever disagree. The state can no longer be silently inconsistent without being visible.

### Diagnosis note
Live evidence from the reported instance (7 interleaved rounds):
`无凭证 → 401` every time, `带令牌 → 502` every time. That proves the gateway was alive, the
auth layer was rejecting correctly, and `?token=` **was** being accepted (otherwise it would have
been 401, not 502) — so the switch itself had taken effect there and the visible failure was the
downstream service not listening. Both the mode-sync bug and the silent-failure bug are now fixed.

## [1.15] — 2026-10-30

Hardening after a fourth round of adversarial testing (55 auth-bypass cases, request
smuggling, CRLF injection, 100-connection slowloris, 50-way concurrency). Nothing was
bypassable; the two changes below close resource-exhaustion surface found by reading the
connection handling rather than by breaking in.

### Security
- **Bounded concurrent connections (`MAX_CONN = 120`).** The worker pool is a
  `newCachedThreadPool()`, which creates threads without limit. Nothing *above* it capped how many
  connections could arrive, so a flood of half-open connections would create one thread each until
  the process died. There is now an `AtomicInteger` gate in the accept loop: past 120 live
  connections the socket is closed immediately and a counter is logged (first drop, then every
  50th, so the log cannot itself be used as a flood amplifier). The counter is decremented in a
  `finally` block, so it cannot drift — verified with a 5000-iteration leak test.
- **Total deadline for reading request headers (`HEAD_DEADLINE_MS = 5000`).** `SoTimeout` alone
  only bounds *each* `read()`, so a client dripping one byte every few seconds held a worker
  thread indefinitely (classic Slowloris). The clock now starts at connect and the head is
  abandoned after 5 s regardless of how the bytes trickle in. `SoTimeout` was aligned to the same
  5 s.

### Notes
- Measured: 100 concurrent slowloris connections did **not** degrade service (0.79–0.98 s for
  normal requests) because Cloudflare terminates them first. The change is defence in depth for
  the paths Cloudflare does not cover — a local app on the same device, or any future change in
  edge behaviour.
- Rejected head reads do **not** count toward the 5-strike lockout, so a slowloris flood cannot be
  used to lock the legitimate owner out.

## [1.14] — 2026-10-30

### Added
- **Three authentication carriers, with a button for each.** Next to the tunnel URL there are now
  three buttons and a one-line hint (`链接 · Header · curl`):
  - **链接** — copies `https://…trycloudflare.com/?token=…`. Shortest to share, clickable in any
    browser. Use it for temporary access.
  - **Header** — copies `Authorization: Bearer <token>`. The URL stays clean; paste the header into
    Postman / your client / a reverse proxy. Use it for long-lived access.
  - **curl** — copies a ready-to-run command: `curl -i -H "Authorization: Bearer <token>" https://…/`
- **"仅 Header 鉴权" switch (header-only mode).** When enabled, the gateway **rejects `?token=`
  outright** and accepts only `Authorization: Bearer` / `X-Auth-Token`. This is the mode to use when
  the URL might end up somewhere you do not control — Cloudflare's request logs, browser history,
  `Referer` headers of pages that link to it, or a screenshot. With header-only on, leaking the URL
  reveals nothing, because the token never appears in it.
  The "链接" button also stops appending the token in this mode, so the two cannot drift apart.
- Startup log now prints the effective auth mode, so a running instance can be audited:
  `鉴权=仅Header（URL令牌禁用）` or `鉴权=URL+Header`.

### Notes
- All three carriers were already accepted by the gateway since v1.1; this release makes them
  equally *usable* and adds the switch that removes the weakest one from the picture.
- Verified with a 13-case logic test: with `headerOnly = false` all three carriers work and
  near-misses (token + suffix, wrong scheme) are rejected; with `headerOnly = true` a correct
  `?token=` is rejected while a correct header still passes.

## [1.13] — 2026-10-30

### Fixed
- **The "无限攻击" (audit mode) switch was pushed off the right edge of the screen and could
  not be tapped — so once enabled it could never be turned off.** The protocol `RadioGroup`
  was declared `layout_width="match_parent"` inside a horizontal `LinearLayout`, which made it
  consume the full row width and squeezed the following `CheckBox` outside the viewport. The
  `RadioGroup` now uses `0dp` + `layout_weight="1"`, and the audit switch has been moved to its
  own row so it stays reachable on any screen width.
- **Audit mode was never persisted.** `loadPrefs()` read `unlimitedAttack` from
  `SharedPreferences` but `savePrefs()` never wrote it — so a `true` value written by an older
  build was read back forever with no way to clear it from the UI. It is now written on every
  toggle (immediately, not only on Start) and on every Start.

### Added
- An orange banner under the switch whenever audit mode is on, stating explicitly that the
  brute-force lockout is disabled and how to turn it back on. The switch label now reads
  `无限攻击（关闭防爆破锁定，用于渗透测试）` instead of just `无限攻击`.

## [1.12] — 2026-10-30

### Fixed
- **The token status line never updated.** `tvTokenState` was inflated and then never
  written to, so the UI kept showing the static "令牌: 未启用" from the layout no matter
  what you typed. You could not tell whether the gateway was armed — the exact confusion
  that has bitten this project before. It is now driven by a live four-state indicator:
  - empty → **red** `⚠️ 令牌: 未启用 —— 任何人拿到地址即可访问`
  - filled, tunnel down → **green** `🔒 令牌: 已填写（N 位），点「启动隧道」后生效`
  - running and matching → **green** `🔒 令牌: 已启用（N 位）· 生效中`
  - running but edited since start → **orange** `⚠️ 令牌已修改，当前运行的是旧令牌（N 位）`
- **Same-length token swaps are now detected.** Comparison uses a SHA-256 fingerprint of
  the token that is actually loaded by the service (`activeTokenSig`), not just its length,
  so changing `aaaa…` to `bbbb…` while running is flagged instead of silently ignored.
  The fingerprint is compared locally only: it is never logged, stored or transmitted.

### Added
- **Version label** on screen (`v1.12 · arm64-v8a`, read from `PackageManager`), so an
  installed build can be identified at a glance.
- `licenses/` with the full Apache-2.0 and MPL-2.0 texts plus the Apache-2.0 §4(b)
  modification notice for the patched `cloudflared` binary.

### Changed
- Removed duplicated `findViewById` / listener / `setText` blocks left over in
  `MainActivity.onCreate()` and `loadPrefs()`.
- Unified the default local port to `8888` (`startTunnel()` said `8080`, the rest said `8888`).
- Dropped the Sponsorship section and `.github/FUNDING.yml`.

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
