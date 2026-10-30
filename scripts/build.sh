#!/usr/bin/env bash
#
# Build CF-FAR without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_BT:?set ANDROID_BT to your Android build-tools directory}"
: "${ANDROID_JAR:?set ANDROID_JAR to platforms/android-XX/android.jar}"

AAPT2="$ANDROID_BT/aapt2"
D8="$ANDROID_BT/d8"
ZIPALIGN="$ANDROID_BT/zipalign"
APKSIGNER="$ANDROID_BT/apksigner"

OUT="$ROOT/build"
GEN="$OUT/gen"
CLS="$OUT/cls"
mkdir -p "$GEN" "$CLS"
cd "$OUT"

[ -f "$ROOT/app/assets/cloudflared" ] || {
    echo "app/assets/cloudflared missing — run scripts/fetch-cloudflared.sh first"
    exit 1
}

echo "[1/5] aapt2 compile"
"$AAPT2" compile --dir "$ROOT/app/res" -o res.zip

echo "[2/5] aapt2 link"
"$AAPT2" link -o base.apk -I "$ANDROID_JAR" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    -A "$ROOT/app/assets" --java "$GEN" \
    --min-sdk-version 21 --target-sdk-version 28 res.zip

echo "[3/5] javac"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -nowarn -encoding UTF-8 \
    -d "$CLS" $(find "$ROOT/app/src" "$GEN" -name '*.java')

echo "[4/5] d8"
"$D8" --lib "$ANDROID_JAR" --release --min-api 21 --output . \
    $(find "$CLS" -name '*.class')

echo "[5/5] package + sign"
cp base.apk unsigned.apk
zip -q unsigned.apk classes.dex
"$ZIPALIGN" -f 4 unsigned.apk aligned.apk

if [ -z "${KEYSTORE:-}" ]; then
    echo "      KEYSTORE not set — generating a throwaway debug keystore"
    KEYS="$OUT/debug.keystore"
    [ -f "$KEYS" ] || keytool -genkeypair -keystore "$KEYS" -alias cftunnel \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=CF-FAR Debug" \
        -storepass android -keypass android >/dev/null 2>&1
    KEYSTORE="$KEYS"; KS_PASS=android; KEY_PASS=android
fi

"$APKSIGNER" sign --ks "$KEYSTORE" \
    --ks-pass "pass:${KS_PASS:?set KS_PASS}" \
    --key-pass "pass:${KEY_PASS:-$KS_PASS}" \
    --out CfTunnel-arm64.apk aligned.apk

"$APKSIGNER" verify CfTunnel-arm64.apk
echo
echo "built: $OUT/CfTunnel-arm64.apk"
ls -lh CfTunnel-arm64.apk
