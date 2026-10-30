# Building CF-FAR

The project has **no Gradle, no AndroidX, no third-party libraries**. It builds with the
Android SDK build-tools alone, in a few seconds, fully offline after the first download.

## Requirements

| Tool | Version used | Notes |
|---|---|---|
| JDK | 17 or 25 | `javac --release 8` is *not* used; `-source 8 -target 8 -bootclasspath` is |
| Android build-tools | 34+ (tested 36.1) | provides `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android platform | API 35 `android.jar` | only needed as `-bootclasspath` |
| python3 | any | used by `fetch-cloudflared.sh` to patch the binary |
| dpkg-deb | any (`dpkg` package) | extracts the Cloudflare `.deb` |

Point the build at your SDK:

```bash
export ANDROID_BT=/path/to/build-tools/36.1.0
export ANDROID_JAR=/path/to/platforms/android-35/android.jar
```

## One-time: fetch and patch cloudflared

```bash
./scripts/fetch-cloudflared.sh
```

This downloads the **arm64** `.deb` from Cloudflare's own apt mirror
(`pkg.cloudflare.com`), extracts the static binary, and applies the `resolv.conf.data` patch
(16-byte, length-preserving substitution). It refuses to continue if the substitution would
change the file size — that would produce an invalid ELF.

Output: `app/assets/cloudflared` (≈36 MB). This file is **git-ignored** on purpose.

## Build

```bash
./scripts/build.sh
```

Steps performed:

```
aapt2 compile  --dir app/res -o res.zip
aapt2 link     -o base.apk -I $ANDROID_JAR --manifest app/AndroidManifest.xml \
               -A app/assets --java gen --min-sdk-version 21 --target-sdk-version 28 res.zip
javac          -source 8 -target 8 -bootclasspath $ANDROID_JAR -d cls $(find app/src gen -name '*.java')
d8             --min-api 21 --lib $ANDROID_JAR --output . $(find cls -name '*.class')
zip            base.apk classes.dex
zipalign -f 4  …
apksigner sign --ks $KEYSTORE …
```

Output: `build/CfTunnel-arm64.apk`.

## Signing

Generate a keystore once and **keep it out of the repository**:

```bash
keytool -genkeypair -keystore my-release.keystore -alias cftunnel \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=CF-FAR" -storepass <password> -keypass <password>
```

```bash
export KEYSTORE=/absolute/path/my-release.keystore
export KS_PASS=<password>
export KEY_PASS=<password>
./scripts/build.sh
```

If no keystore is provided, `build.sh` creates a throwaway debug keystore under `build/` — fine
for local testing, **never** for distribution. Losing a release keystore means you can never
update an installed APK again.

## Common issues

| Symptom | Cause | Fix |
|---|---|---|
| `error: --boot-class-path cannot be used with --release` | JDK 17+ rejects the combination | use `-source 8 -target 8 -bootclasspath`, as `build.sh` does |
| `aapt2: command not found` | build-tools not on PATH | export `ANDROID_BT` |
| `Exec format error` on the patched binary | the substitution changed the file length | re-run `fetch-cloudflared.sh`; it asserts equal length |
| APK installs but the app says "not arm64" | wrong ABI binary | fetch the arm64 package, not amd64 |
| `min 4 KB of disk` errors during `d8` | `/tmp` too small | set `TMPDIR` to a roomier path |

## Verifying a build

```bash
$ANDROID_BT/apksigner verify -v build/CfTunnel-arm64.apk     # v1 + v2 + v3 shown as true
$ANDROID_BT/aapt dump badging build/CfTunnel-arm64.apk | head -3
# package: name='com.mil.cftunnel' … sdkVersion:'21' targetSdkVersion:'28'
```
