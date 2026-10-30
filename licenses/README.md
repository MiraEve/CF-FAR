# License texts bundled at distribution

CF-FAR itself is MIT (see [`../LICENSE`](../LICENSE)). It bundles or downloads
third-party components that are **not** MIT; their full license texts are kept
here so that every APK distributed from this repository ships with the notices
those licenses require.

| File | Applies to | Upstream |
|---|---|---|
| `Apache-2.0.txt` | `cloudflared` binary (bundled inside the APK, patched in one place) | https://github.com/cloudflare/cloudflared |
| `MPL-2.0.txt` | `app/assets/cacerts.pem` (Mozilla CA bundle, fallback trust store) | https://curl.se/ca/cacert.pem |

## Modification notice (Apache-2.0, section 4(b))

The `cloudflared` binary redistributed inside CI-built APKs **has been modified**:
the 16-byte literal `/etc/resolv.conf` is overwritten in place with the relative
path `resolv.conf.data`, which is the minimum change needed to make the Go
runtime resolve DNS inside an unprivileged Android app sandbox. No other byte is
altered, and the binary is re-fetched and re-patched locally by
`scripts/fetch-cloudflared.sh` — it is never committed to this repository.

## Trademarks

"Cloudflare", "cloudflared" and the Cloudflare logo are trademarks of Cloudflare,
Inc. This project is an independent client and is **not** affiliated with,
endorsed by, or sponsored by Cloudflare, Inc. See Apache-2.0 section 6.
