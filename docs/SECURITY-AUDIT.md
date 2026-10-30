# Security Audit Record

This document records the adversarial testing that shaped CF-FAR's authentication design.
Every entry below was reproduced against a live tunnel and, where applicable, confirmed on a
local harness with the exact same code.

> **Method.** Two layers were tested separately:
> (1) the gateway, exercised both over the public Cloudflare URL and against a local
> `PortForwarder` instance with a raw-socket harness; (2) the backend service, exercised
> through the MCP endpoint with a valid token. Cloudflare's edge rejects malformed HTTP before
> it reaches the phone, so protocol-level cases were additionally validated locally.

---

## Round 1 — keep-alive reuse (fixed in 1.7)

**Finding.** Validation ran once per TCP connection. A client could authenticate once and then
send unlimited unauthenticated requests on the same `keep-alive` connection.

```
① first request on connection, valid token  -> 200
② same connection, no token                -> 200   ← bypass
③ new connection, no token                 -> 401
```

**Fix.** Requests are rewritten to `Connection: close`; cloudflared is started with
`--proxy-keepalive-connections 0`.

**Verification.** Same-connection follow-up is now closed by the upstream side:
`② same connection, no token -> connection closed`.

---

## Round 2 — parameter boundary and header substring (fixed in 1.8)

**Finding A.** Query parsing used `path.indexOf("token=")`, which also matches `xtoken=`,
`mytoken=`, `atoken=`.

```
GET /?xtoken=<valid token>   -> 200   ← bypass
```

**Finding B.** Header validation used substring search, so any value *beginning* with the token
passed.

```
X-Auth-Token: <valid token>XYZ   -> accepted
```

**Fix.** Query strings are split on `&` and compared by exact parameter name. Header values are
parsed per line and compared with `equals`; `Authorization` requires the `Bearer` scheme
prefix followed by an exactly matching value.

**Verification.**

```
xtoken= / mytoken=            -> 401
X-Auth-Token: <token>XYZ      -> 401
Authorization: Bearer <tok>X  -> 401
```

---

## Round 3 — HTTP pipelining / request smuggling (fixed in 1.9)

**Finding.** After the first request header was validated, the connection entered a bidirectional
pump. A second request written into the same segment was forwarded verbatim.

```
GET /?token=<valid> HTTP/1.1
Host: x
                          ← validated, forwarded
GET /SMUGGLED_MARKER HTTP/1.1
Host: x
                          ← forwarded with no validation
```

**Fix.** The request body is forwarded by exact `Content-Length`, then the upstream direction is
`shutdownOutput()`. Anything the client writes afterwards has nowhere to go.

**Verification (backend access log, not response codes):**

```
127.0.0.1 - - "GET /?token=... HTTP/1.1" 200
SMUGGLED_MARKER occurrences: 0
```

---

## Round 4 — audit mode (1.10)

Added deliberately, not a fix: a switch that disables the brute-force lockout so unlimited
testing is possible. Token validation, smuggling protection and connection handling remain
active.

```
audit mode on : 8 consecutive failures -> 401 ×8, no lockout
audit mode off: 5th failure -> 429, 6th -> 429
```

---

## Round 5 — POST body regression (fixed in 1.11)

**Finding.** Introduced by the round-3 fix. `readHttpHead()` can consume part of the body into
its buffer; the exact-length forwarder then re-read the full `Content-Length`, so the backend
received an inconsistent length and dropped the connection. **Every request with a body failed**,
including all MCP `tools/call` invocations.

```
1 KB / 10 KB / 100 KB POST -> connection closed, no response
```

**Fix.** Reconcile the already-buffered bytes against `Content-Length` and read only the
remainder.

**Verification.** Echo backend reports the byte count it actually received:

```
1 KB   -> RECEIVED:1024
100 KB -> RECEIVED:102400
500 KB -> RECEIVED:512000
1 MB   -> RECEIVED:1048576
```

---

## Backend service review

The gateway is one layer; the exposed service is another. Tested through the MCP endpoint with a
valid token:

| Test | Result |
|---|---|
| `so_open` with `/etc/passwd`, `/proc/self/environ`, `/proc/self/cmdline` | Rejected: `PATH_OUTSIDE_WORK_DIRECTORY` |
| `so_open` with `../../../../etc/passwd` | Rejected: `SO_NOT_FOUND` |
| `build_so` to `/sdcard`, `/data/local/tmp` | Rejected: workspace required |
| Malformed JSON body | `-32700 Parse error` |
| Empty body | `-32700 Parse error` |
| Unknown method `../../etc/passwd` | `-32601 Method not found` |
| Batch JSON-RPC | Handled correctly |
| `meta_info help` leaking internal paths | No `/data/` or `/sdcard` |
| Config dump exposing secrets | **`maskSecrets: false` returns a plaintext `accessToken`** |

The last item is a property of the backend service, not of CF-FAR — but it is recorded here
because it materially changes the risk of exposing that service through a tunnel. Mitigations:
do not expose the backend on `0.0.0.0`, and treat the tunnel token as the only credential.

---

## Summary

| # | Vulnerability | Severity | Fixed in |
|---|---|---|---|
| 1 | Keep-alive reuse bypass | High | 1.7 |
| 2 | Query parameter name boundary (`xtoken=`) | High | 1.8 |
| 3 | Header substring / prefix match | High | 1.8 |
| 4 | HTTP pipelining / request smuggling | High | 1.9 |
| 5 | POST body double-read (all POST broken) | Critical (availability) | 1.11 |

**Attack vectors covered:** 60+ across parameter tampering, header manipulation, URL encoding,
Unicode, protocol smuggling, connection lifecycle, concurrency, resource exhaustion and
backend tool abuse.

**Known residual risks** (by design, documented):

- Global lockout can be abused as a 60-second DoS
- Tokens in URLs leak through `Referer`, history and proxy logs
- No IP allowlist, no per-IP rate limiting
- A backend bound to `0.0.0.0` is reachable from the LAN and bypasses the gateway

---

## Reproducing

The local harness used for these tests is intentionally not shipped (it hard-codes test tokens).
To reproduce a case, start the app with a known token and use a raw TLS socket:

```python
import socket, ssl
ctx = ssl.create_default_context()
s = ctx.wrap_socket(socket.create_connection(("your-tunnel.trycloudflare.com", 443)),
                    server_hostname="your-tunnel.trycloudflare.com")
s.sendall(b"GET /?xtoken=<token> HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
print(s.recv(200))
```

A `401` means the gateway holds. A `200` means you found something — please report it per
[SECURITY.md](../SECURITY.md).
