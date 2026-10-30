#!/usr/bin/env bash
#
# Download cloudflared (arm64) from Cloudflare's apt mirror and apply the
# "resolv.conf.data" patch so it can resolve DNS on Android without root.
#
set -euo pipefail

VER="${CLOUDFLARED_VERSION:-2026.10.6}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/cf-far-fetch"
DEB="cloudflared_${VER}_arm64.deb"
URL="https://pkg.cloudflare.com/cloudflared/pool/main/c/cloudflared/${DEB}"

echo "[1/4] downloading cloudflared ${VER} (arm64)"
mkdir -p "$WORK"
cd "$WORK"
# Cloudflare occasionally truncates long transfers: resume until the size is stable.
for i in 1 2 3 4 5 6; do
    before=$(stat -c%s "$DEB" 2>/dev/null || echo 0)
    curl -fsSL -C - -o "$DEB" "$URL" || true
    after=$(stat -c%s "$DEB" 2>/dev/null || echo 0)
    echo "      ${before} -> ${after} bytes"
    [ "$before" = "$after" ] && [ "$after" -gt 0 ] && break
done

echo "[2/4] extracting"
dpkg-deb -x "$DEB" extracted
BIN="extracted/usr/bin/cloudflared"
[ -f "$BIN" ] || { echo "binary not found in deb"; exit 1; }

echo "[3/4] patching DNS path"
python3 - "$BIN" "$ROOT/app/assets/cloudflared" <<'PY'
import sys, os
src, dst = sys.argv[1], sys.argv[2]
data = open(src, 'rb').read()
old, new = b'/etc/resolv.conf', b'resolv.conf.data'   # both exactly 16 bytes
if len(old) != len(new):
    sys.exit('patch strings must be the same length')
if data.count(old) != 1:
    sys.exit('expected exactly one occurrence of %r, found %d'
             % (old.decode(), data.count(old)))
out = data.replace(old, new)
assert len(out) == len(data), 'length must not change (would corrupt the ELF)'
os.makedirs(os.path.dirname(dst), exist_ok=True)
open(dst, 'wb').write(out)
print('      patched: %s -> %s (%d bytes)' % (old.decode(), new.decode(), len(out)))
PY

echo "[4/4] done"
ls -lh "$ROOT/app/assets/cloudflared"
echo
echo "NOTE: cloudflared is Apache-2.0 licensed, (c) Cloudflare, Inc."
echo "      It is fetched at build time and is NOT part of this repository's MIT license."
