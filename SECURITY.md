# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.11.x  | ✅ |
| < 1.11  | ❌ (upgrade — several auth bypasses were fixed) |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's [private vulnerability reporting](https://github.com/MiraEve/CF-FAR/security/advisories/new)
or open an issue titled `security contact` asking for a private channel.

Please include:

- Affected version (`versionName` from the app, or the APK hash)
- A minimal reproduction (raw HTTP request is ideal)
- Impact assessment (what an attacker gains)

You can expect an initial response within 7 days.

## Scope

**In scope**

- The token gateway (`PortForwarder.java`): authentication bypass, smuggling, lockout bypass
- Request handling: header/query parsing, body forwarding, connection lifecycle
- Secret leakage through logs, notifications, or the UI
- Privilege issues in the bundled binary patch (`resolv.conf.data`), certificate injection

**Out of scope**

- Cloudflare Quick Tunnel behaviour (uptime, hostname rotation, edge filtering) — that is
  Cloudflare's infrastructure, not this project
- The security of the service **you** expose through the tunnel
- The fact that a leaked token grants access — that is the design of any bearer token
- Limitations already documented in the README ("Known limitations")

## Security design, in short

1. **Exact matching only.** Header values and query parameter names are compared with `equals`,
   never `indexOf`. `?xtoken=`, `X-Auth-Token: <token>XYZ` and similar tricks do not work.
2. **One connection, one request.** After forwarding a request body, the upstream direction is
   `shutdownOutput()`. HTTP keep-alive reuse and pipelining smuggling have nowhere to go.
3. **Reject before forward.** A failed authentication returns a minimal 401 without ever
   connecting to the backend.
4. **Lockout.** 5 consecutive failures lock the gateway for 60 seconds (disable-able via the
   audit switch, which is intended for testing only).
5. **No secrets in the repository.** The build never embeds tokens or keystores.

## What this project does *not* protect against

Being explicit is part of the policy:

- Global lockout can be abused as a 60-second DoS.
- Tokens in URLs leak via `Referer`, browser history and proxy logs.
- No IP allowlist, no per-IP rate limiting.
- A backend bound to `0.0.0.0` is reachable from the LAN and bypasses the gateway entirely.

See [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md) for the adversarial test record that
produced the current design.
