# Contributing to CF-FAR

Thanks for taking the time to contribute.

## Ground rules

- **Never commit secrets.** No tokens, no keystores, no `cloudflared` binaries.
  GitHub push protection is enabled; a leaked PAT is revoked within seconds and you will
  lose write access.
- **Security fixes go through [SECURITY.md](SECURITY.md)**, not a public PR, until a fix ships.
- Keep the project dependency-free. No AndroidX, no Gradle, no third-party libraries.
  The whole point is that it builds with `aapt2 + javac + d8` and nothing else.

## Development setup

See [docs/BUILD.md](docs/BUILD.md). You need:

- JDK 8+ (tested with 17 and 25)
- Android SDK build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`)
- `platforms/android-35/android.jar`

```bash
./scripts/fetch-cloudflared.sh
./scripts/build.sh
```

## Code style

- Java 8 syntax, anonymous inner classes (no lambdas — d8 desugaring is intentionally avoided)
- All network work off the main thread
- Every log line goes through `Logger`, never `System.out`
- Prefer explicit error messages over generic ones — the log is the primary debugging surface

## Pull request checklist

- [ ] `./scripts/build.sh` succeeds
- [ ] `apksigner verify` passes
- [ ] Manually tested on Android 9 (API 28) or later
- [ ] Tested with **and without** a token configured
- [ ] If touching `PortForwarder`, re-ran the adversarial cases in
      [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md) and stated the results

## Commit messages

```
<area>: <what changed>

<why it changed, if not obvious>
```

Areas: `fwd`, `service`, `ui`, `dns`, `cert`, `build`, `docs`.

## Reporting bugs

Open an issue with:

- App version and Android version
- The `[sys]` and `[cf]` log lines around the failure (use the **Copy log** button)
- Whether the token was enabled

Redact your token before pasting logs.
